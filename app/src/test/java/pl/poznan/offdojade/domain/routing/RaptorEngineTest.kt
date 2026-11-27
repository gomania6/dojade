package pl.poznan.offdojade.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.poznan.offdojade.data.gtfs.FootpathGenerator
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import pl.poznan.offdojade.data.local.entity.TripEntity
import pl.poznan.offdojade.domain.model.AppLanguage
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.RoutePreference
import pl.poznan.offdojade.domain.model.TripSettings
import pl.poznan.offdojade.ui.components.TimeFormatUtils
import pl.poznan.offdojade.ui.localization.stringsFor

class RaptorEngineTest {

    private val engine = RaptorEngine()

    private fun buildSampleGraph(): RaptorData {
        // Координаты реального Познани (для проверки Хаверсина и пеших пересадок < 300 м)
        val stops = listOf(
            StopEntity("S_KOM", "KOM01", "Komorniki", 52.3400, 16.8100, "B", "1"),
            StopEntity("S_GOR", "GOR01", "Górczyn PKM", 52.3820, 16.8780, "A", "2"),
            // Остановка в ~110 м от Górczyn PKM (должна сгенерироваться пешая пересадка < 300 м)
            StopEntity("S_GOR_TRAM", "GOR02", "Górczyn Tramwaj", 52.3829, 16.8788, "A", "1"),
            StopEntity("S_KAP", "KAP01", "Rondo Kaponiera", 52.4082, 16.9125, "A", "3"),
            StopEntity("S_RYN", "RYN01", "Plac Wielkopolski", 52.4105, 16.9310, "A", "1")
        )

        val routes = listOf(
            RouteEntity("R_702", "ztm", "702", "Komorniki - Górczyn", 3, "1565C0", "FFFFFF"),
            RouteEntity("R_5", "ztm", "5", "Górczyn - Plac Wielkopolski", 0, "007A5E", "FFFFFF")
        )

        val trips = listOf(
            // Автобус 702: 08:00 -> 08:12
            TripEntity("T_702_1", "R_702", "WK", "Górczyn PKM", 0, "SH_702"),
            // Трамвай 5 (ранний рейс): 08:15 -> 08:25 -> 08:30
            TripEntity("T_5_1", "R_5", "WK", "Plac Wielkopolski", 0, "SH_5"),
            // Трамвай 5 (следующий рейс): 08:25 -> 08:35 -> 08:40
            TripEntity("T_5_2", "R_5", "WK", "Plac Wielkopolski", 0, "SH_5")
        )

        fun sec(h: Int, m: Int): Int = h * 3600 + m * 60

        val stopTimes = listOf(
            // T_702_1
            StopTimeEntity("T_702_1", 1, "S_KOM", sec(8, 0), sec(8, 0)),
            StopTimeEntity("T_702_1", 2, "S_GOR", sec(8, 12), sec(8, 12)),

            // T_5_1
            StopTimeEntity("T_5_1", 1, "S_GOR_TRAM", sec(8, 15), sec(8, 15)),
            StopTimeEntity("T_5_1", 2, "S_KAP", sec(8, 25), sec(8, 25)),
            StopTimeEntity("T_5_1", 3, "S_RYN", sec(8, 30), sec(8, 30)),

            // T_5_2
            StopTimeEntity("T_5_2", 1, "S_GOR_TRAM", sec(8, 25), sec(8, 25)),
            StopTimeEntity("T_5_2", 2, "S_KAP", sec(8, 35), sec(8, 35)),
            StopTimeEntity("T_5_2", 3, "S_RYN", sec(8, 40), sec(8, 40))
        )

        val footpaths = FootpathGenerator.generateFootpaths(stops)

        return TransitGraphBuilder.build(
            serviceDateYyyymmdd = 20261126,
            stops = stops,
            routes = routes,
            trips = trips,
            stopTimes = stopTimes,
            transfers = footpaths
        )
    }

    @Test
    fun directRoute_findsEarliestTripUnder50ms() {
        val graph = buildSampleGraph()
        val depSec = 8 * 3600 + 10 * 60 // 08:10

        val results = engine.searchFromStop(
            data = graph,
            originStopId = "S_GOR_TRAM",
            targetStopId = "S_RYN",
            departureTimeSec = depSec
        )

        assertTrue("Должен найти прямой маршрут", results.isNotEmpty())
        val best = results.first()
        assertEquals(0, best.transferCount)
        assertEquals(1, best.transitLegs.size)
        assertEquals("5", best.transitLegs.first().routeShortName)
        assertEquals(8 * 3600 + 15 * 60, best.departureTimeSec)
        assertEquals(8 * 3600 + 30 * 60, best.arrivalTimeSec)
        assertTrue("Поиск RAPTOR должен быть быстрее 50 мс", best.searchDurationMs < 50L)
    }

    @Test
    fun transferRoute_usesGeneratedFootpathAndDetectsZonesAB() {
        val graph = buildSampleGraph()
        val depSec = 7 * 3600 + 55 * 60 // 07:55

        val results = engine.searchFromStop(
            data = graph,
            originStopId = "S_KOM",
            targetStopId = "S_RYN",
            departureTimeSec = depSec
        )

        assertTrue("Должен найти маршрут с пересадкой через footpath", results.isNotEmpty())
        val best = results.first()
        assertEquals(1, best.transferCount)
        assertEquals(2, best.transitLegs.size)
        assertEquals("702", best.transitLegs[0].routeShortName)
        assertEquals("5", best.transitLegs[1].routeShortName)
        assertTrue(best.legs.any { it is JourneyLeg.WalkLeg })
        assertEquals(8 * 3600 + 30 * 60, best.arrivalTimeSec)
        assertEquals(listOf("A", "B"), best.ticketRecommendation.zones)
    }

    @Test
    fun realtimeDelay_shiftsArrivalAndBreaksTightTransfer() {
        val graph = buildSampleGraph()
        val depSec = 7 * 3600 + 55 * 60 // 07:55

        // Автобус 702 опаздывает на +5 минут (300 сек): прибывает в 08:17 вместо 08:12
        // Значит на трамвай T_5_1 (08:15) пользователь уже не успевает и должен пересесть на T_5_2 (08:25 -> 08:40)
        val delays = mapOf("T_702_1" to 300)

        val results = engine.searchFromStop(
            data = graph,
            originStopId = "S_KOM",
            targetStopId = "S_RYN",
            departureTimeSec = depSec,
            tripDelays = delays
        )

        assertTrue(results.isNotEmpty())
        val best = results.first()
        assertEquals(300, best.transitLegs[0].delaySeconds)
        assertEquals("T_5_2", best.transitLegs[1].tripId)
        assertEquals(8 * 3600 + 40 * 60, best.arrivalTimeSec)
    }

    @Test
    fun searchFromCoordinates_computesOriginWalkAndCatchability() {
        val graph = buildSampleGraph()
        // Пользователь стоит примерно в ~150 м от S_GOR_TRAM
        val results = engine.searchFromCoordinates(
            data = graph,
            originLat = 52.3840,
            originLon = 16.8788,
            targetStopId = "S_KAP",
            departureTimeSec = 8 * 3600 + 10 * 60
        )

        assertTrue(results.isNotEmpty())
        val best = results.first()
        assertNotNull("Должен рассчитываться индикатор 'Успею ли добежать'", best.catchability)
        assertTrue(best.legs.first() is JourneyLeg.WalkLeg)
    }

    @Test
    fun mergedStopNames_routesAtoBAcrossDifferentPlatformsWithSameName() {
        val stops = listOf(
            // Две разные платформы с одинаковым названием "Rondo Kaponiera"
            StopEntity("RKAP01", "RKAP01", "Rondo Kaponiera", 52.4080, 16.9120, "A", "1"),
            StopEntity("RKAP42", "RKAP42", "Rondo Kaponiera", 52.4084, 16.9129, "A", "42"),
            // Две разные платформы с одинаковым названием "Most Teatralny"
            StopEntity("MTEA01", "MTEA01", "Most Teatralny", 52.4120, 16.9150, "A", "1"),
            StopEntity("MTEA02", "MTEA02", "Most Teatralny", 52.4123, 16.9154, "A", "2")
        )
        val routes = listOf(
            RouteEntity("R_12", "ztm", "12", "Rondo Kaponiera - Most Teatralny", 0, "007A5E", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T_12_1", "R_12", "WK", "Most Teatralny", 0, "SH_12")
        )
        // Трамвай идет от платформы RKAP42 до платформы MTEA02
        val stopTimes = listOf(
            StopTimeEntity("T_12_1", 1, "RKAP42", 9 * 3600, 9 * 3600),
            StopTimeEntity("T_12_1", 2, "MTEA02", 9 * 3600 + 240, 9 * 3600 + 240)
        )
        val graph = TransitGraphBuilder.build(
            serviceDateYyyymmdd = 20261126,
            stops = stops,
            routes = routes,
            trips = trips,
            stopTimes = stopTimes,
            transfers = emptyList()
        )

        // Группировка должна объединить 4 платформы в 2 логические остановки
        val groups = TransitGraphRepository.groupStopsByName(stops)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].platformCount)
        assertEquals(2, groups[1].platformCount)

        // Пользователь выбирает Пункт А = RKAP01, Пункт Б = MTEA01 — движок автоматически находит рейс RKAP42 -> MTEA02
        val results = engine.searchFromStop(
            data = graph,
            originStopId = "RKAP01",
            targetStopId = "MTEA01",
            departureTimeSec = 8 * 3600 + 55 * 60
        )
        assertTrue("Должен найти прямой рейс между платформами с одинаковым названием", results.isNotEmpty())
        assertEquals(0, results.first().transferCount)
        assertEquals("12", results.first().transitLegs.first().routeShortName)

        // Табло ближайших остановок также объединяет одинаковые названия в одну карточку
        val nearby = engine.findNearbyStopBoards(
            data = graph,
            userLat = 52.4081,
            userLon = 16.9122,
            currentTimeSec = 8 * 3600 + 55 * 60,
            maxStops = 3
        )
        assertEquals(2, nearby.size)
        assertEquals("Rondo Kaponiera", nearby[0].stopName)
        assertEquals(2, nearby[0].platformCount)
        assertEquals(1, nearby[0].departures.size)
    }

    @Test
    fun tripSettings_excludesBusesExcludedLinesZonesAndRespectsLowFloor() {
        val stops = listOf(
            StopEntity("S1", "S1", "Górczyn", 52.3820, 16.8780, "A", "1"),
            StopEntity("S_B", "SB", "Luboń", 52.3450, 16.8850, "B", "1"),
            StopEntity("S2", "S2", "Dębiec", 52.3710, 16.9120, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R_BUS", "ztm", "149", "Bus 149", 3, "1565C0", "FFFFFF"),
            RouteEntity("R_TRAM_HF", "ztm", "10", "Tram 10", 0, "007A5E", "FFFFFF"),
            RouteEntity("R_TRAM_LF", "ztm", "11", "Tram 11", 0, "007A5E", "FFFFFF")
        )
        val trips = listOf(
            // Прямой автобус (не низкопольный)
            TripEntity("T_BUS", "R_BUS", "WK", "Dębiec", 0, "SH1", wheelchairAccessible = 2),
            // Трамвай 10 через зону B (высокий пол)
            TripEntity("T_10", "R_TRAM_HF", "WK", "Dębiec", 0, "SH2", wheelchairAccessible = 2),
            // Трамвай 11 только по зоне A (низкий пол)
            TripEntity("T_11", "R_TRAM_LF", "WK", "Dębiec", 0, "SH3", wheelchairAccessible = 1)
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T_BUS", 1, "S1", sec(9, 0), sec(9, 0)),
            StopTimeEntity("T_BUS", 2, "S2", sec(9, 10), sec(9, 10)),

            StopTimeEntity("T_10", 1, "S1", sec(9, 2), sec(9, 2)),
            StopTimeEntity("T_10", 2, "S_B", sec(9, 7), sec(9, 7)),
            StopTimeEntity("T_10", 3, "S2", sec(9, 12), sec(9, 12)),

            StopTimeEntity("T_11", 1, "S1", sec(9, 5), sec(9, 5)),
            StopTimeEntity("T_11", 2, "S2", sec(9, 16), sec(9, 16))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        // 1. Исключаем автобусы -> 149 не должен предлагаться
        val noBus = engine.searchFromStop(
            data = graph,
            originStopId = "S1",
            targetStopId = "S2",
            departureTimeSec = sec(8, 55),
            tripSettings = TripSettings(excludeBuses = true)
        )
        assertTrue(noBus.isNotEmpty())
        assertTrue(noBus.none { j -> j.transitLegs.any { it.routeShortName == "149" } })

        // 2. Исключаем зону B -> трамвай 10 через Luboń (B) отфильтровывается
        val noZoneB = engine.searchFromStop(
            data = graph,
            originStopId = "S1",
            targetStopId = "S2",
            departureTimeSec = sec(8, 55),
            tripSettings = TripSettings(excludeBuses = true, excludedZones = setOf("B"))
        )
        assertTrue(noZoneB.isNotEmpty())
        assertEquals("11", noZoneB.first().transitLegs.first().routeShortName)

        // 3. Только с низким полом -> только трамвай 11 (wheelchairAccessible == 1)
        val lowFloor = engine.searchFromStop(
            data = graph,
            originStopId = "S1",
            targetStopId = "S2",
            departureTimeSec = sec(8, 55),
            tripSettings = TripSettings(lowFloorOnly = true)
        )
        assertEquals(1, lowFloor.size)
        assertEquals("11", lowFloor.first().transitLegs.first().routeShortName)
        assertTrue(lowFloor.first().isAllLowFloor)

        // 4. Исключить линию 149 -> 149 не возвращается
        val excl149 = engine.searchFromStop(
            data = graph,
            originStopId = "S1",
            targetStopId = "S2",
            departureTimeSec = sec(8, 55),
            tripSettings = TripSettings(excludedLines = setOf("149"))
        )
        assertTrue(excl149.none { j -> j.transitLegs.any { it.routeShortName == "149" } })
    }

    @Test
    fun diverseModes_gorczynToDebiec_offersDirectBusAndShortTramTransfer_rejectsCityWideDetour() {
        // Реальные координаты Познани: Гурчин -> Дембец (~2.6 км напрямую)
        val stops = listOf(
            StopEntity("GOR", "GOR", "Górczyn", 52.3820, 16.8780, "A", "1"),
            StopEntity("HUB", "HUB", "Wierzbięcice", 52.3935, 16.9150, "A", "1"),
            StopEntity("DEB", "DEB", "Dębiec", 52.3710, 16.9120, "A", "1"),
            // Далекий район на севере города (Piątkowo / Sobieskiego ~8 км от Гурчина)
            StopEntity("NORTH", "NTH", "Os. Sobieskiego", 52.4630, 16.9160, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R_149", "ztm", "149", "Górczyn - Dębiec", 3, "1565C0", "FFFFFF"),
            RouteEntity("R_5", "ztm", "5", "Górczyn - Wierzbięcice", 0, "007A5E", "FFFFFF"),
            RouteEntity("R_2", "ztm", "2", "Wierzbięcice - Dębiec", 0, "007A5E", "FFFFFF"),
            RouteEntity("R_14", "ztm", "14", "Górczyn - Os. Sobieskiego", 0, "007A5E", "FFFFFF"),
            RouteEntity("R_16", "ztm", "16", "Os. Sobieskiego - Dębiec", 0, "007A5E", "FFFFFF")
        )
        val trips = listOf(
            // Прямой автобус 149: 10:00 -> 10:12 (12 мин)
            TripEntity("T_149", "R_149", "WK", "Dębiec", 0, ""),
            // Короткий вариант трамваями с пересадкой: 5 (10:01 -> 10:08) + 2 (10:10 -> 10:16) (15 мин)
            TripEntity("T_5", "R_5", "WK", "Wierzbięcice", 0, ""),
            TripEntity("T_2", "R_2", "WK", "Dębiec", 0, ""),
            // Безумный крюк трамваями через весь город на север: 14 (10:00 -> 10:25) + 16 (10:27 -> 10:55) (55 мин)
            TripEntity("T_14", "R_14", "WK", "Os. Sobieskiego", 0, ""),
            TripEntity("T_16", "R_16", "WK", "Dębiec", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T_149", 1, "GOR", sec(10, 0), sec(10, 0)),
            StopTimeEntity("T_149", 2, "DEB", sec(10, 12), sec(10, 12)),

            StopTimeEntity("T_5", 1, "GOR", sec(10, 1), sec(10, 1)),
            StopTimeEntity("T_5", 2, "HUB", sec(10, 8), sec(10, 8)),

            StopTimeEntity("T_2", 1, "HUB", sec(10, 11), sec(10, 11)),
            StopTimeEntity("T_2", 2, "DEB", sec(10, 17), sec(10, 17)),

            StopTimeEntity("T_14", 1, "GOR", sec(10, 0), sec(10, 0)),
            StopTimeEntity("T_14", 2, "NORTH", sec(10, 25), sec(10, 25)),

            StopTimeEntity("T_16", 1, "NORTH", sec(10, 27), sec(10, 27)),
            StopTimeEntity("T_16", 2, "DEB", sec(10, 55), sec(10, 55))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchFromStop(
            data = graph,
            originStopId = "GOR",
            targetStopId = "DEB",
            departureTimeSec = sec(9, 58),
            tripSettings = TripSettings(minTransferMinutes = 1)
        )

        val signatures = results.map { j -> j.transitLegs.map { it.routeShortName } }
        assertTrue("Должен быть прямой автобус 149", listOf("149") in signatures)
        assertTrue("Должен быть короткий трамвайный вариант 5 -> 2", listOf("5", "2") in signatures)
        assertFalse("Крюк через весь город 14 -> 16 должен быть отсеян", listOf("14", "16") in signatures)
    }

    @Test
    fun timelineSearch_returnsNearest5_andLoadsEarlierDepartedAndLaterRoutes() {
        val stops = listOf(
            StopEntity("A", "A", "Stop A", 52.40, 16.90, "A", "1"),
            StopEntity("B", "B", "Stop B", 52.41, 16.92, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R1", "ztm", "1", "A - B", 0, "007A5E", "FFFFFF")
        )
        // 15 рейсов каждые 10 минут с 08:00 до 10:20
        val trips = (0 until 15).map { i ->
            TripEntity("T_$i", "R1", "WK", "Stop B", 0, "")
        }
        val stopTimes = (0 until 15).flatMap { i ->
            val dep = 8 * 3600 + i * 10 * 60
            listOf(
                StopTimeEntity("T_$i", 1, "A", dep, dep),
                StopTimeEntity("T_$i", 2, "B", dep + 600, dep + 600)
            )
        }
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        // Сейчас 09:05 -> ближайшие 5 рейсов: 09:10, 09:20, 09:30, 09:40, 09:50
        val nowSec = 9 * 3600 + 5 * 60
        val upcoming = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "A",
            originLat = 52.40,
            originLon = 16.90,
            targetStopId = "B",
            departureTimeSec = nowSec,
            limit = 5
        )
        assertEquals(5, upcoming.size)
        assertEquals(9 * 3600 + 10 * 60, upcoming.first().departureTimeSec)
        assertEquals(9 * 3600 + 50 * 60, upcoming.last().departureTimeSec)

        // Листаем вверх -> уже отъехавшие до 09:10 (08:20..09:00)
        val earlier = engine.searchEarlierJourneys(
            data = graph,
            originStopId = "A",
            originLat = 52.40,
            originLon = 16.90,
            targetStopId = "B",
            beforeDepartureSec = upcoming.first().departureTimeSec,
            limit = 5
        )
        assertEquals(5, earlier.size)
        assertTrue(earlier.all { it.departureTimeSec < upcoming.first().departureTimeSec })
        assertEquals(9 * 3600, earlier.last().departureTimeSec)

        // Листаем вниз -> следующие после 09:50 (10:00, 10:10, 10:20)
        val later = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "A",
            originLat = 52.40,
            originLon = 16.90,
            targetStopId = "B",
            departureTimeSec = upcoming.last().departureTimeSec + 60,
            limit = 5
        )
        assertEquals(3, later.size)
        assertEquals(10 * 3600, later.first().departureTimeSec)
        assertEquals(10 * 3600 + 20 * 60, later.last().departureTimeSec)
    }

    @Test
    fun relativeTimeFormatting_showsMinutesWithinHourAndExactClockOverHour() {
        val ru = stringsFor(AppLanguage.RU)
        val now = 10 * 3600 // 10:00

        // Через 15 мин -> «через 15 мин»
        assertEquals("через 15 мин", TimeFormatUtils.formatRelativeDeparture(now + 15 * 60, now, ru))
        // Через ровно 60 мин -> «через 60 мин»
        assertEquals("через 60 мин", TimeFormatUtils.formatRelativeDeparture(now + 60 * 60, now, ru))
        // Более чем через час (11:15) -> точное время «11:15», а не «через 75 мин»
        assertEquals("11:15", TimeFormatUtils.formatRelativeDeparture(now + 75 * 60, now, ru))
        // Уже отъехал 10 мин назад -> «10 мин назад»
        assertEquals("10 мин назад", TimeFormatUtils.formatRelativeDeparture(now - 10 * 60, now, ru))
        // Отъехал более часа назад (08:30) -> точное время «08:30»
        assertEquals("08:30", TimeFormatUtils.formatRelativeDeparture(now - 90 * 60, now, ru))
    }

    @Test
    fun polishDiacriticsAndFuzzySearch_matchesWithoutAccentsAndWithMinorTypos() {
        assertEquals("gorczyn pkm", TextSearchUtils.foldDiacritics("Górczyn PKM"))
        assertEquals("debiec", TextSearchUtils.foldDiacritics("Dębiec"))
        assertEquals("lazarz", TextSearchUtils.foldDiacritics("Łazarz"))
        assertEquals("zrodlo", TextSearchUtils.foldDiacritics("Źródło"))
        assertEquals("sw. marcin", TextSearchUtils.foldDiacritics("Św. Marcin"))
        assertEquals("zoliborz", TextSearchUtils.foldDiacritics("Żoliborz"))

        assertTrue(TextSearchUtils.matchScore("gorczyn", "Górczyn PKM") > 500)
        assertTrue(TextSearchUtils.matchScore("debiec", "Dębiec PKM") > 500)
        assertTrue(TextSearchUtils.matchScore("sw marcin", "Św. Marcin") > 300)
        assertTrue(TextSearchUtils.matchScore("kap rondo", "Rondo Kaponiera") > 300)
        // Мелкая опечатка (1 символ)
        assertTrue(TextSearchUtils.matchScore("kaponera", "Rondo Kaponiera") > 300)
        assertEquals(0, TextSearchUtils.matchScore("krakow", "Rondo Kaponiera"))
    }

    @Test
    fun addressRouting_generatesInitialAndFinalWalkLegs() {
        val graph = buildSampleGraph()
        // Старт от адреса ~120 м от Górczyn Tramwaj, финиш по адресу ~120 м от Rondo Kaponiera
        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = null,
            originLat = 52.3839,
            originLon = 16.8788,
            targetStopId = null,
            departureTimeSec = 8 * 3600 + 10 * 60,
            originName = "ul. Głogowska 200",
            targetLat = 52.4090,
            targetLon = 16.9130,
            targetName = "ul. Św. Marcin 80"
        )

        assertTrue("Должен найти маршрут между двумя адресами", results.isNotEmpty())
        val first = results.first()
        assertTrue("Первый сегмент должен быть пешим до остановки", first.legs.first() is JourneyLeg.WalkLeg)
        assertTrue("Последний сегмент должен быть пешим до адреса назначения", first.legs.last() is JourneyLeg.WalkLeg)
        assertEquals("ul. Św. Marcin 80", (first.legs.last() as JourneyLeg.WalkLeg).toName)
    }

    @Test
    fun shapeSlicer_trimsOutsideRouteToAtMostOneAdjacentStop() {
        // Линия из 5 остановок: S0 -- S1 -- S2 -- S3 -- S4
        val shape = listOf(
            52.370 to 16.900, // 0: S0 (далеко до посадки — НЕ должна попасть!)
            52.375 to 16.900, // 1: между S0 и S1
            52.380 to 16.900, // 2: S1 (prevStop — ближайшая до посадки)
            52.385 to 16.900, // 3: между S1 и S2
            52.390 to 16.900, // 4: S2 (boardingStop)
            52.395 to 16.900, // 5: между S2 и S3
            52.400 to 16.900, // 6: S3 (alightingStop)
            52.405 to 16.900, // 7: между S3 и S4
            52.410 to 16.900, // 8: S4 (nextStop — ближайшая после высадки)
            52.420 to 16.900  // 9: S5 (далеко после высадки — НЕ должна попасть!)
        )

        val sliced = ShapeSlicer.slice(
            shapePoints = shape,
            legStops = listOf(52.390 to 16.900, 52.400 to 16.900),
            prevStop = 52.380 to 16.900,
            nextStop = 52.410 to 16.900
        )

        // Активный участок: ровно индексы 4..6
        assertEquals(listOf(shape[4], shape[5], shape[6]), sliced.activeCoordinates)
        // Вне маршрута: ровно 2 полупрозрачных сегмента (2..4 и 6..8), без 0, 1 и 9!
        assertEquals(2, sliced.outsideSegments.size)
        assertEquals(listOf(shape[2], shape[3], shape[4]), sliced.outsideSegments[0])
        assertEquals(listOf(shape[6], shape[7], shape[8]), sliced.outsideSegments[1])
    }

    @Test
    fun arriveBySearch_returnsLatestDeparturesArrivingOnOrBeforeTargetTime() {
        val stops = listOf(
            StopEntity("A", "A", "Stop A", 52.40, 16.90, "A", "1"),
            StopEntity("B", "B", "Stop B", 52.41, 16.92, "A", "1")
        )
        val routes = listOf(RouteEntity("R1", "ztm", "1", "A - B", 0, "007A5E", "FFFFFF"))
        // Рейсы каждые 15 мин с 08:00 до 10:00, в пути 10 мин
        val trips = (0..8).map { i -> TripEntity("T_$i", "R1", "WK", "Stop B", 0, "") }
        val stopTimes = (0..8).flatMap { i ->
            val dep = 8 * 3600 + i * 15 * 60
            listOf(
                StopTimeEntity("T_$i", 1, "A", dep, dep),
                StopTimeEntity("T_$i", 2, "B", dep + 600, dep + 600)
            )
        }
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        // Нужно приехать к 09:30 -> самый поздний подходящий рейс: 09:15 -> 09:25 (рейс 09:30 -> 09:40 уже поздно!)
        val targetArrival = 9 * 3600 + 30 * 60
        val results = engine.searchArrivingByJourneys(
            data = graph,
            originStopId = "A",
            originLat = 52.40,
            originLon = 16.90,
            targetStopId = "B",
            arriveByTimeSec = targetArrival,
            limit = 3
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.arrivalTimeSec <= targetArrival })
        assertEquals(9 * 3600 + 15 * 60, results.last().departureTimeSec)
        assertEquals(9 * 3600 + 25 * 60, results.last().arrivalTimeSec)
    }

    @Test
    fun bestMode_hidesTwoTransferVariantWhenOneTransferExists_fastestModeKeepsIt() {
        val stops = listOf(
            StopEntity("S_KOM", "S1", "Komorniki", 52.340, 16.810, "B", "1"),
            StopEntity("S_GOR", "S2", "Górczyn", 52.382, 16.878, "A", "1"),
            StopEntity("S_MID", "S3", "Bałtyk", 52.404, 16.906, "A", "1"),
            StopEntity("S_KAP", "S4", "Rondo Kaponiera", 52.408, 16.912, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R702", "ztm", "702", "702", 3, "1565C0", "FFFFFF"),
            RouteEntity("R5", "ztm", "5", "5", 0, "007A5E", "FFFFFF"),
            RouteEntity("R168", "ztm", "168", "168", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T702", "R702", "WK", "Górczyn", 0, ""),
            TripEntity("T5", "R5", "WK", "Kaponiera", 0, ""),
            TripEntity("T168", "R168", "WK", "Kaponiera", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            // 702: 20:36 -> 21:00
            StopTimeEntity("T702", 1, "S_KOM", sec(20, 36), sec(20, 36)),
            StopTimeEntity("T702", 2, "S_GOR", sec(21, 0), sec(21, 0)),
            // 5: 21:03 -> 21:18 (Bałtyk) -> 21:26 (Kaponiera)  [1 пересадка, 50 мин]
            StopTimeEntity("T5", 1, "S_GOR", sec(21, 3), sec(21, 3)),
            StopTimeEntity("T5", 2, "S_MID", sec(21, 18), sec(21, 18)),
            StopTimeEntity("T5", 3, "S_KAP", sec(21, 26), sec(21, 26)),
            // 168: 21:20 (Bałtyk) -> 21:24 (Kaponiera)  [2 пересадки, 48 мин — на 2 мин быстрее!]
            StopTimeEntity("T168", 1, "S_MID", sec(21, 20), sec(21, 20)),
            StopTimeEntity("T168", 2, "S_KAP", sec(21, 24), sec(21, 24))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        // Режим «Лучшее» (BEST): вариант с 2 пересадками (702->5->168) НЕ показывается
        val bestResults = engine.searchFromStop(
            data = graph,
            originStopId = "S_KOM",
            targetStopId = "S_KAP",
            departureTimeSec = sec(20, 30),
            tripSettings = TripSettings(routePreference = RoutePreference.BEST, minTransferMinutes = 1)
        )
        assertEquals(1, bestResults.size)
        assertEquals(1, bestResults.first().transferCount)
        assertEquals(listOf("702", "5"), bestResults.first().transitLegs.map { it.routeShortName })

        // Режим «Быстрое» (FASTEST): экономия всего 2 мин при лишней 2-й пересадке теперь отклоняется
        // в пользу минимальных пересадок (702->5)
        val fastestResults = engine.searchFromStop(
            data = graph,
            originStopId = "S_KOM",
            targetStopId = "S_KAP",
            departureTimeSec = sec(20, 30),
            tripSettings = TripSettings(routePreference = RoutePreference.FASTEST, minTransferMinutes = 1)
        )
        assertEquals(1, fastestResults.size)
        assertEquals(1, fastestResults.first().transferCount)
        assertEquals(listOf("702", "5"), fastestResults.first().transitLegs.map { it.routeShortName })
    }

    @Test
    fun twoTransferRoute_isShownAsFallbackWhenNoSimpleRoutesExist() {
        val stops = listOf(
            StopEntity("S1", "S1", "Komorniki", 52.340, 16.810, "B", "1"),
            StopEntity("S2", "S2", "Górczyn", 52.382, 16.878, "A", "1"),
            StopEntity("S3", "S3", "Bałtyk", 52.404, 16.906, "A", "1"),
            StopEntity("S4", "S4", "Rondo Kaponiera", 52.408, 16.912, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R250", "ztm", "250", "250", 3, "000000", "FFFFFF"),
            RouteEntity("R201", "ztm", "201", "201", 0, "000000", "FFFFFF"),
            RouteEntity("R213", "ztm", "213", "213", 3, "000000", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T250", "R250", "WK", "Górczyn", 0, ""),
            TripEntity("T201", "R201", "WK", "Bałtyk", 0, ""),
            TripEntity("T213", "R213", "WK", "Kaponiera", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            // Ночной рейс с 2 пересадками — единственный доступный вариант поздно вечером
            StopTimeEntity("T250", 1, "S1", sec(23, 10), sec(23, 10)),
            StopTimeEntity("T250", 2, "S2", sec(23, 25), sec(23, 25)),
            StopTimeEntity("T201", 1, "S2", sec(23, 28), sec(23, 28)),
            StopTimeEntity("T201", 2, "S3", sec(23, 40), sec(23, 40)),
            StopTimeEntity("T213", 1, "S3", sec(23, 43), sec(23, 43)),
            StopTimeEntity("T213", 2, "S4", sec(23, 50), sec(23, 50))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "S1",
            originLat = 52.340,
            originLon = 16.810,
            targetStopId = "S4",
            departureTimeSec = sec(23, 0),
            tripSettings = TripSettings(routePreference = RoutePreference.BEST, minTransferMinutes = 1),
            limit = 5
        )
        assertEquals(1, results.size)
        assertEquals(2, results.first().transferCount)
        assertEquals(listOf("250", "201", "213"), results.first().transitLegs.map { it.routeShortName })
    }

    @Test
    fun shortHopUnder500m_stillFindsDirectTram_andParetoPrunesStrictlyDominatedBus() {
        val stops = listOf(
            // ~400 м между остановками (меньше 500 м радиуса nearbyAccesses!)
            StopEntity("RKAP", "RKAP", "Rondo Kaponiera", 52.4080, 16.9120, "A", "1"),
            StopEntity("MTEA", "MTEA", "Most Teatralny", 52.4116, 16.9128, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R_SLOW", "ztm", "163", "Slow Bus", 3, "1565C0", "FFFFFF"),
            RouteEntity("R_FAST", "ztm", "12", "Fast Tram", 0, "007A5E", "FFFFFF")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val trips = listOf(
            // Медленный автобус: выезд 10:00, приезд 10:25 (строго доминируется трамваем 10:05 -> 10:10!)
            TripEntity("T_SLOW", "R_SLOW", "WK", "Most Teatralny", 0, ""),
            // Быстрый трамвай: выезд ПОЗЖЕ (10:05), приезд РАНЬШЕ (10:10)
            TripEntity("T_FAST", "R_FAST", "WK", "Most Teatralny", 0, "")
        )
        val stopTimes = listOf(
            StopTimeEntity("T_SLOW", 1, "RKAP", sec(10, 0), sec(10, 0)),
            StopTimeEntity("T_SLOW", 2, "MTEA", sec(10, 25), sec(10, 25)),

            StopTimeEntity("T_FAST", 1, "RKAP", sec(10, 5), sec(10, 5)),
            StopTimeEntity("T_FAST", 2, "MTEA", sec(10, 10), sec(10, 10))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "RKAP",
            originLat = 52.4080,
            originLon = 16.9120,
            targetStopId = "MTEA",
            departureTimeSec = sec(9, 55),
            limit = 5
        )

        assertTrue("Короткая поездка < 500 м должна находить транспорт", results.isNotEmpty())
        assertEquals(1, results.size)
        assertEquals("12", results.first().transitLegs.first().routeShortName)
    }

    @Test
    fun neverWalksBackwardsToEarlierStopOfSameTrip_andNeverAlightsEarlyBeforeTransferHub() {
        // Реальный сценарий со скриншота:
        // 182: Śląska (22:35) -> Uniwersytet Przyrodniczy (22:36) -> Częstochowska (22:59) -> Górczyn PKM перрон 1 (23:01)
        // 704: Górczyn PKM перрон 2 (23:10) -> Komorniki/Malinowa (23:23)
        // Пассажир стартует на Uniwersytet Przyrodniczy: НЕ должен идти пешком назад 355 м на Śląska
        // и НЕ должен выходить раньше на Częstochowska, чтобы идти 265 м до Górczyn PKM.
        val stops = listOf(
            StopEntity("SLA", "SLA", "Śląska", 52.4285, 16.9010, "A", "1"),
            StopEntity("UPR", "UPR", "Uniwersytet Przyrodniczy", 52.4262, 16.9045, "A", "1"), // ~350 м от Śląska
            StopEntity("CZE", "CZE", "Częstochowska", 52.3840, 16.8795, "A", "1"),
            StopEntity("GOR1", "GOR1", "Górczyn PKM", 52.3822, 16.8780, "A", "1"), // перрон прибытия 182
            StopEntity("GOR2", "GOR2", "Górczyn PKM", 52.3818, 16.8778, "A", "2"), // перрон отправления 704 (~46 м от GOR1, ~270 м от CZE)
            StopEntity("KOM", "KOM", "Komorniki/Malinowa", 52.3400, 16.8100, "B", "1")
        )
        val routes = listOf(
            RouteEntity("R182", "ztm", "182", "182", 3, "1565C0", "FFFFFF"),
            RouteEntity("R704", "ztm", "704", "704", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T182", "R182", "WK", "Górczyn PKM", 0, ""),
            TripEntity("T704", "R704", "WK", "Komorniki", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T182", 1, "SLA", sec(22, 35), sec(22, 35)),
            StopTimeEntity("T182", 2, "UPR", sec(22, 36), sec(22, 36)),
            StopTimeEntity("T182", 3, "CZE", sec(22, 59), sec(22, 59), isOnDemand = 1),
            StopTimeEntity("T182", 4, "GOR1", sec(23, 1), sec(23, 1)),

            StopTimeEntity("T704", 1, "GOR2", sec(23, 10), sec(23, 10)),
            StopTimeEntity("T704", 2, "KOM", sec(23, 23), sec(23, 23))
        )
        val transfers = listOf(
            TransferEntity("CZE", "GOR2", 2, 220, 265), // 22:59 + 220s = 23:02:40 (на 20 сек раньше, чем через GOR1!)
            TransferEntity("GOR1", "GOR2", 2, 120, 46)  // 23:01 + 120s = 23:03:00 (гораздо короче пешком!)
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, transfers)

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "UPR",
            originLat = 52.4262,
            originLon = 16.9045,
            targetStopId = "KOM",
            departureTimeSec = sec(22, 26),
            limit = 5
        )

        assertTrue(results.isNotEmpty())
        val journey = results.first()
        val firstTransit = journey.transitLegs.first()
        // 1. Посадка строго на Uniwersytet Przyrodniczy, без похода назад на Śląska!
        assertEquals("Uniwersytet Przyrodniczy", firstTransit.boardingStop.stopName)
        assertEquals(sec(22, 36), firstTransit.startTimeSec)
        assertTrue(journey.legs.first() is pl.poznan.offdojade.domain.model.JourneyLeg.TransitLeg)
        // 2. Высадка на Górczyn PKM, без преждевременного выхода на Częstochowska!
        assertEquals("Górczyn PKM", firstTransit.alightingStop.stopName)
        assertEquals(sec(23, 1), firstTransit.endTimeSec)
        // 3. Флаг остановки по требованию (ПТ / NŻ) сохраняется для промежуточной остановки Częstochowska
        assertTrue(firstTransit.stops.any { it.stopName == "Częstochowska" && it.isOnDemand })
        assertFalse(firstTransit.boardingStop.isOnDemand)

        // 4. Бинарный кэш RaptorDataCache сохраняет и восстанавливает граф без потерь
        val tmpFile = java.io.File.createTempFile("raptor_cache_test", ".bin")
        try {
            RaptorDataCache.save(tmpFile, dbFingerprint = 123456L, data = graph)
            val restored = RaptorDataCache.load(tmpFile, expectedDateYyyymmdd = 20261126, expectedDbFingerprint = 123456L)
            assertNotNull(restored)
            assertEquals(graph.stopCount, restored!!.stopCount)
            assertEquals(graph.tripCount, restored.tripCount)
            assertEquals(graph.arrivalTimesSec.size, restored.arrivalTimesSec.size)
            val restoredResults = engine.searchUpcomingJourneys(
                data = restored,
                originStopId = "UPR",
                originLat = 52.4262,
                originLon = 16.9045,
                targetStopId = "KOM",
                departureTimeSec = sec(22, 26),
                limit = 5
            )
            assertEquals(1, restoredResults.size)
            assertTrue(restoredResults.first().transitLegs.first().stops.any { it.stopName == "Częstochowska" && it.isOnDemand })
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun nearbyEgressStopBeforeDestination_doesNotPruneActualDestinationStop() {
        // Автобус 182 едет: Start (08:00) -> Wojska Polskiego (08:15, 220м до цели) -> Uniwersytet Przyrodniczy (08:16, цель!)
        // Раньше Wojska Polskiego попадала в targetEgressMap и обрезала targetBest=08:15 без учёта пешего времени,
        // из-за чего Uniwersytet Przyrodniczy (08:16) отбрасывался и пользователя высаживали на 1 остановку раньше!
        val stops = listOf(
            StopEntity("S_START", "S1", "Górczyn PKM", 52.3820, 16.8780, "A", "1"),
            StopEntity("S_PREV", "S2", "Wojska Polskiego", 52.4243, 16.9045, "A", "1"), // ~210 м до цели
            StopEntity("S_DEST", "S3", "Uniwersytet Przyrodniczy", 52.4262, 16.9045, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R182", "ztm", "182", "182", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T182", "R182", "WK", "Uniwersytet Przyrodniczy", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T182", 1, "S_START", sec(8, 0), sec(8, 0)),
            StopTimeEntity("T182", 2, "S_PREV", sec(8, 15), sec(8, 15)),
            StopTimeEntity("T182", 3, "S_DEST", sec(8, 16), sec(8, 16))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "S_START",
            originLat = 52.3820,
            originLon = 16.8780,
            targetStopId = "S_DEST",
            departureTimeSec = sec(7, 55),
            limit = 5
        )

        assertEquals(1, results.size)
        val journey = results.first()
        assertEquals("Uniwersytet Przyrodniczy", journey.transitLegs.last().alightingStop.stopName)
        assertEquals(sec(8, 16), journey.arrivalTimeSec)
        assertTrue(journey.legs.last() is pl.poznan.offdojade.domain.model.JourneyLeg.TransitLeg)
    }

    @Test
    fun absurdSameHubHop_rondoKaponieraToRondoKaponiera_isRejected() {
        // Реальный абсурдный кейс со скриншота:
        // Пользователь на ул. Bukowska (~180 м от Bałtyk) строит маршрут до Bałtyk.
        // Автобус 163 идёт с одной платформы Rondo Kaponiera (254 м от пользователя)
        // на другую платформу Rondo Kaponiera (295 м от Bałtyk) — 1 остановка, 2 мин.
        // Такая поездка должна быть полностью отсеяна!
        val stops = listOf(
            StopEntity("RKAP_W", "RKAP1", "Rondo Kaponiera", 52.4084, 16.9094, "A", "1"),
            StopEntity("RKAP_E", "RKAP2", "Rondo Kaponiera", 52.4080, 16.9126, "A", "2"),
            StopEntity("BAL", "BAL1", "Bałtyk", 52.4055, 16.9114, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R163", "ztm", "163", "163", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T163", "R163", "WK", "Garbary PKM", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T163", 1, "RKAP_W", sec(20, 32), sec(20, 32)),
            StopTimeEntity("T163", 2, "RKAP_E", sec(20, 34), sec(20, 34))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = null,
            originLat = 52.4063, // Bukowska (~180 м от Bałtyk)
            originLon = 16.9094,
            targetStopId = "BAL",
            departureTimeSec = sec(20, 27),
            limit = 8
        )

        assertTrue(
            "Абсурдная поездка Rondo Kaponiera -> Rondo Kaponiera ради пешей цели Bałtyk должна быть отклонена",
            results.isEmpty()
        )
    }

    @Test
    fun absurdWalkDominatedTrip_withDifferentStopNames_isRejected() {
        // Даже если у платформ РАЗНЫЕ названия (Zwierzyniecka -> Rondo Kaponiera),
        // поездка, где суммарная ходьба (~520 м) больше прямой дистанции до цели (~200 м),
        // должна быть отсеяна!
        val stops = listOf(
            StopEntity("ZWI", "ZWI1", "Zwierzyniecka", 52.4084, 16.9094, "A", "1"),
            StopEntity("RKAP", "RKAP2", "Rondo Kaponiera", 52.4080, 16.9126, "A", "2"),
            StopEntity("BAL", "BAL1", "Bałtyk", 52.4055, 16.9114, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R163", "ztm", "163", "163", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T163", "R163", "WK", "Garbary PKM", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T163", 1, "ZWI", sec(20, 32), sec(20, 32)),
            StopTimeEntity("T163", 2, "RKAP", sec(20, 34), sec(20, 34))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = null,
            originLat = 52.4063,
            originLon = 16.9094,
            targetStopId = "BAL",
            departureTimeSec = sec(20, 27),
            limit = 8
        )

        assertTrue(
            "Поездка с пешей частью больше прямой дистанции до цели должна быть отклонена",
            results.isEmpty()
        )
    }

    @Test
    fun sameHubTwoPlatforms_stillRoutesCleanlyToNextRealStopWithoutInternalHop() {
        // Автобус идёт: Rondo Kaponiera (платф. 1) -> Rondo Kaponiera (платф. 2) -> Most Teatralny.
        // При поиске Rondo Kaponiera -> Most Teatralny должен сесть сразу на платф. 2 (1 ост.), а не ехать через 2 платф.
        val stops = listOf(
            StopEntity("RKAP_1", "RK1", "Rondo Kaponiera", 52.4084, 16.9094, "A", "1"),
            StopEntity("RKAP_2", "RK2", "Rondo Kaponiera", 52.4080, 16.9126, "A", "2"),
            StopEntity("MTEA", "MT1", "Most Teatralny", 52.4120, 16.9145, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R163", "ztm", "163", "163", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T163", "R163", "WK", "Most Teatralny", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T163", 1, "RKAP_1", sec(10, 0), sec(10, 0)),
            StopTimeEntity("T163", 2, "RKAP_2", sec(10, 2), sec(10, 2)),
            StopTimeEntity("T163", 3, "MTEA", sec(10, 5), sec(10, 5))
        )
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, emptyList())

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "RKAP_1",
            originLat = 52.4084,
            originLon = 16.9094,
            targetStopId = "MTEA",
            departureTimeSec = sec(9, 55),
            limit = 5
        )

        assertEquals(1, results.size)
        val leg = results.first().transitLegs.first()
        assertEquals("RKAP_2", leg.boardingStop.stopId)
        assertEquals("MTEA", leg.alightingStop.stopId)
        assertEquals(1, leg.stopsPassed)
    }

    @Test
    fun convenientMode_doesNotForceWalkingToNeighboringOriginStopViaFootpath() {
        // Пользователь выбрал остановку S_ORIG в режиме CONVENIENT.
        // Соседняя остановка S_NEAR в 200 м имеет чуть более ранний автобус, но режим CONVENIENT
        // не должен заставлять идти пешком на S_NEAR в раунде 0, если с S_ORIG есть прямой рейс.
        val stops = listOf(
            StopEntity("S_ORIG", "O1", "Orig Stop", 52.4000, 16.9000, "A", "1"),
            StopEntity("S_NEAR", "N1", "Near Stop", 52.4018, 16.9000, "A", "1"), // ~200 м
            StopEntity("S_DEST", "D1", "Dest Stop", 52.4200, 16.9000, "A", "1")
        )
        val routes = listOf(
            RouteEntity("R1", "ztm", "1", "1", 0, "007A5E", "FFFFFF"),
            RouteEntity("R2", "ztm", "2", "2", 3, "1565C0", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T_NEAR", "R2", "WK", "Dest Stop", 0, ""),
            TripEntity("T_ORIG", "R1", "WK", "Dest Stop", 0, "")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T_NEAR", 1, "S_NEAR", sec(10, 4), sec(10, 4)),
            StopTimeEntity("T_NEAR", 2, "S_DEST", sec(10, 14), sec(10, 14)),
            StopTimeEntity("T_ORIG", 1, "S_ORIG", sec(10, 6), sec(10, 6)),
            StopTimeEntity("T_ORIG", 2, "S_DEST", sec(10, 18), sec(10, 18))
        )
        val footpaths = FootpathGenerator.generateFootpaths(stops)
        val graph = TransitGraphBuilder.build(20261126, stops, routes, trips, stopTimes, footpaths)

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "S_ORIG",
            originLat = 52.4000,
            originLon = 16.9000,
            targetStopId = "S_DEST",
            departureTimeSec = sec(10, 0),
            tripSettings = TripSettings(routePreference = RoutePreference.CONVENIENT),
            limit = 5
        )

        assertTrue(results.isNotEmpty())
        assertEquals("S_ORIG", results.first().transitLegs.first().boardingStop.stopId)
        assertTrue(results.first().legs.first() is JourneyLeg.TransitLeg)
    }

    @Test
    fun geoUtils_haversineAntipodalPoints_neverReturnsNaN() {
        val dist = pl.poznan.offdojade.data.gtfs.GeoUtils.haversineMeters(0.0, 0.0, 0.0, 180.0)
        assertFalse(dist.isNaN())
        assertTrue(dist > 20_000_000.0)
    }

    @Test
    fun multimodalBusPlusRail_combinesZtmBusAndKwTrainWithStationTransfer_andMarksRailTicket() {
        // Plewiska (автобус 716) -> Junikowo (пешая пересадка 360 м на станцию ж/д) -> PKM2 KW -> Poznań Główny за 8 мин
        val stops = listOf(
            StopEntity("ZTM_PLE", "PLE1", "Plewiska/Szkoła", 52.3690, 16.8050, "B", "1"),
            StopEntity("ZTM_JUN", "JUN1", "Junikowo PKM", 52.3820, 16.8260, "A", "1"),
            StopEntity("rail_JUN", "RJUN", "Poznań Junikowo", 52.3852, 16.8260, "A", "1"), // ~355 м от ZTM_JUN
            StopEntity("rail_PG", "RPG", "Poznań Główny", 52.4021, 16.9122, "A", "2")
        )
        val routes = listOf(
            RouteEntity("R_716", "ztm", "716", "Plewiska - Junikowo", 3, "1565C0", "FFFFFF"),
            RouteEntity("rail_KW_PKM2", "rail_KW", "PKM2", "Nowy Tomyśl - Poznań", 2, "EA1A35", "FFFFFF")
        )
        val trips = listOf(
            TripEntity("T_716", "R_716", "WK", "Junikowo PKM", 0, ""),
            TripEntity("rail_T_PKM2", "rail_KW_PKM2", "rail_WK", "Poznań Główny", 0, "rail_SH_PKM2")
        )
        fun sec(h: Int, m: Int) = h * 3600 + m * 60
        val stopTimes = listOf(
            StopTimeEntity("T_716", 1, "ZTM_PLE", sec(7, 30), sec(7, 30)),
            StopTimeEntity("T_716", 2, "ZTM_JUN", sec(7, 36), sec(7, 36)),
            StopTimeEntity("rail_T_PKM2", 1, "rail_JUN", sec(7, 43), sec(7, 43)),
            StopTimeEntity("rail_T_PKM2", 2, "rail_PG", sec(7, 51), sec(7, 51))
        )
        // Пересадка 355 м (> 220 м стандартного лимита BEST, но разрешена до 450 м для ж/д станций)
        val transfers = listOf(
            TransferEntity("ZTM_JUN", "rail_JUN", 2, 300, 355)
        )
        val graph = TransitGraphBuilder.build(20261127, stops, routes, trips, stopTimes, transfers)

        val results = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "ZTM_PLE",
            originLat = 52.3690,
            originLon = 16.8050,
            targetStopId = "rail_PG",
            departureTimeSec = sec(7, 25),
            tripSettings = TripSettings(routePreference = RoutePreference.BEST),
            limit = 5
        )

        assertEquals(1, results.size)
        val journey = results.first()
        assertEquals(listOf("716", "PKM2"), journey.transitLegs.map { it.routeShortName })
        assertTrue(journey.transitLegs[1].isRail)
        assertTrue(journey.ticketRecommendation.hasRailLeg)
        assertEquals(sec(7, 51), journey.arrivalTimeSec)

        // Если отключить электрички в фильтрах — маршрут с поездом не предлагается
        val noRailResults = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = "ZTM_PLE",
            originLat = 52.3690,
            originLon = 16.8050,
            targetStopId = "rail_PG",
            departureTimeSec = sec(7, 25),
            tripSettings = TripSettings(
                routePreference = RoutePreference.BEST,
                enabledTransportModes = setOf(
                    pl.poznan.offdojade.domain.model.TransportMode.TRAM,
                    pl.poznan.offdojade.domain.model.TransportMode.BUS
                )
            ),
            limit = 5
        )
        assertTrue(noRailResults.isEmpty())
    }

    @Test
    fun walkingPaceSetting_adjustsInitialWalkDurationAndCatchability() {
        val graph = buildSampleGraph()
        // Пользователь ~120 м от Górczyn Tramwaj
        val slowResults = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = null,
            originLat = 52.3840,
            originLon = 16.8788,
            targetStopId = "S_KAP",
            departureTimeSec = 8 * 3600 + 10 * 60,
            tripSettings = TripSettings(walkingPace = pl.poznan.offdojade.domain.model.WalkingPace.SLOW),
            limit = 3
        )
        val fastResults = engine.searchUpcomingJourneys(
            data = graph,
            originStopId = null,
            originLat = 52.3840,
            originLon = 16.8788,
            targetStopId = "S_KAP",
            departureTimeSec = 8 * 3600 + 10 * 60,
            tripSettings = TripSettings(walkingPace = pl.poznan.offdojade.domain.model.WalkingPace.FAST),
            limit = 3
        )
        assertTrue(slowResults.isNotEmpty() && fastResults.isNotEmpty())
        val slowWalk = slowResults.first().legs.first() as JourneyLeg.WalkLeg
        val fastWalk = fastResults.first().legs.first() as JourneyLeg.WalkLeg
        assertTrue("Медленный темп должен давать больше секунд пешком, чем быстрый", slowWalk.durationSec > fastWalk.durationSec)
    }
}
