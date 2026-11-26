package pl.poznan.offdojade.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.poznan.offdojade.data.gtfs.FootpathGenerator
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TripEntity
import pl.poznan.offdojade.domain.model.JourneyLeg

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
}
