package pl.poznan.offdojade.domain.tariff

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.poznan.offdojade.config.ZtmTariffConfig
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.LegStop

class ZtmTicketCalculatorTest {

    @Test
    fun bufferCheck_13minPureBecomes18minAndRecommends45minTicket() {
        val rec = ZtmTicketCalculator.calculate(
            pureTransitMinutes = 13,
            delayMinutes = 0,
            totalStopsPassed = 6,
            zones = setOf("A")
        )

        assertEquals(18, rec.totalEstimatedMinutes)
        assertEquals("t45", rec.recommendedTimeTicket.id)
        assertEquals(6.00, rec.recommendedTimeTicket.pricePln, 0.001)
        assertNotNull("Должно быть предупреждение, что 15-минутный билет впритык", rec.tightFitWarning)
        assertTrue(rec.tightFitWarning!!.contains("15 мин"))
        assertTrue(rec.tightFitWarning!!.contains("45 мин"))
    }

    @Test
    fun bufferCheck_10minPureNoDelayFits15minTicketWithoutWarning() {
        val rec = ZtmTicketCalculator.calculate(
            pureTransitMinutes = 10,
            delayMinutes = 0,
            totalStopsPassed = 4,
            zones = setOf("A")
        )

        assertEquals(15, rec.totalEstimatedMinutes)
        assertEquals("t15", rec.recommendedTimeTicket.id)
        assertEquals(4.00, rec.recommendedTimeTicket.pricePln, 0.001)
        assertNull("При 10 + 5 = 15 мин предупреждения быть не должно", rec.tightFitWarning)
    }

    @Test
    fun delayPushes10minTripOver15minThreshold() {
        val rec = ZtmTicketCalculator.calculate(
            pureTransitMinutes = 10,
            delayMinutes = 3,
            totalStopsPassed = 5,
            zones = setOf("A")
        )

        assertEquals(18, rec.totalEstimatedMinutes)
        assertEquals("t45", rec.recommendedTimeTicket.id)
        assertNotNull(rec.tightFitWarning)
        assertTrue(rec.tightFitWarning!!.contains("+3 мин"))
    }

    @Test
    fun pekaDegressivePricing_matchesZtmStopTiers() {
        // 1 ост = 0.72
        assertEquals(0.72, ZtmTariffConfig.calculatePekaCostPln(1), 0.001)
        // 3 ост = 0.72 + 0.60 + 0.60 = 1.92
        assertEquals(1.92, ZtmTariffConfig.calculatePekaCostPln(3), 0.001)
        // 6 ост = 1.92 + 3 * 0.42 = 3.18
        assertEquals(3.18, ZtmTariffConfig.calculatePekaCostPln(6), 0.001)
        // 10 ост = 3.18 + 4 * 0.30 = 4.38
        assertEquals(4.38, ZtmTariffConfig.calculatePekaCostPln(10), 0.001)
        // 20 ост = 4.38 + 10 * 0.24 = 6.78
        assertEquals(6.78, ZtmTariffConfig.calculatePekaCostPln(20), 0.001)
        // 22 ост = 6.78 + 2 * 0.12 = 7.02
        assertEquals(7.02, ZtmTariffConfig.calculatePekaCostPln(22), 0.001)
    }

    @Test
    fun comparison_highlightsPekaForShortTripAndTimeTicketForLongTrip() {
        // Короткая поездка: 5 остановок (PEKA = 2.76 zł) vs временной 15 мин (4.00 zł) -> выгоднее PEKA
        val shortRec = ZtmTicketCalculator.calculate(
            pureTransitMinutes = 8,
            delayMinutes = 0,
            totalStopsPassed = 5,
            zones = setOf("A")
        )
        assertEquals(CheapestTicketChoice.PEKA_TPORTMONETKA, shortRec.cheapestChoice)
        assertEquals(2.76, shortRec.pekaCostPln, 0.001)

        // Длинная поездка: 22 остановки (PEKA = 7.02 zł) vs временной 45 мин (6.00 zł) -> выгоднее временной
        val longRec = ZtmTicketCalculator.calculate(
            pureTransitMinutes = 30,
            delayMinutes = 0,
            totalStopsPassed = 22,
            zones = setOf("A", "B")
        )
        assertEquals(CheapestTicketChoice.TIME_TICKET, longRec.cheapestChoice)
        assertEquals("Зоны: A + B", longRec.zonesBadgeText)
    }

    @Test
    fun ulgowy50Percent_halvesTimeTicketsAndPekaAndRespects18And9PlnCaps() {
        // Обычный потолок tPortmonetka: при 200 остановках не превышает 18.00 zł
        assertEquals(18.00, ZtmTariffConfig.calculatePekaCostPln(200, isUlgowy = false), 0.001)
        // Льготный (Ulgowy 50%) потолок tPortmonetka: при 200 остановках не превышает 9.00 zł
        assertEquals(9.00, ZtmTariffConfig.calculatePekaCostPln(200, isUlgowy = true), 0.001)

        // 5 остановок: нормальный = 2.76 zł -> ulgowy = 1.38 zł; билет 15 мин: 4.00 zł -> 2.00 zł
        val ulgowyShort = ZtmTicketCalculator.calculate(
            pureTransitMinutes = 8,
            delayMinutes = 0,
            totalStopsPassed = 5,
            zones = setOf("A"),
            isUlgowy = true
        )
        assertTrue(ulgowyShort.isUlgowy)
        assertEquals(1.38, ulgowyShort.pekaCostPln, 0.001)
        assertEquals(2.00, ulgowyShort.recommendedTimeTicketPricePln, 0.001)
        assertEquals(CheapestTicketChoice.PEKA_TPORTMONETKA, ulgowyShort.cheapestChoice)
    }

    @Test
    fun pekaTransferRule_continuesWithin20MinAndResetsOver20Min() {
        fun makeLeg(id: String, stopCount: Int, startMin: Int, endMin: Int): JourneyLeg.TransitLeg {
            val stops = (0..stopCount).map { idx ->
                val t = (startMin + idx) * 60
                LegStop("S_${id}_$idx", "Stop $idx", "1", "A", 52.4, 16.9, t, t)
            }
            return JourneyLeg.TransitLeg(
                tripId = id,
                routeShortName = id,
                routeType = 3,
                routeColorHex = null,
                headsign = "Test",
                shapeId = "",
                delaySeconds = 0,
                stops = stops,
                startTimeSec = startMin * 60,
                endTimeSec = endMin * 60
            )
        }

        // Пересадка ровно 15 мин (<= 20 мин): 6 + 4 = 10 остановок в одном непрерывном тарифе = 4.38 zł
        val continuous = ZtmTicketCalculator.calculateForLegs(
            listOf(
                makeLeg("L1", stopCount = 6, startMin = 600, endMin = 610),
                makeLeg("L2", stopCount = 4, startMin = 625, endMin = 635)
            ),
            isUlgowy = false
        )
        assertEquals(listOf(10), continuous.pekaStopGroups)
        assertEquals(4.38, continuous.pekaCostPln, 0.001)

        // Пересадка 21 мин (> 20 мин): счётчик сбрасывается — 6 ост (3.18) + 4 ост (2.34) = 5.52 zł
        val split = ZtmTicketCalculator.calculateForLegs(
            listOf(
                makeLeg("L1", stopCount = 6, startMin = 600, endMin = 610),
                makeLeg("L2", stopCount = 4, startMin = 631, endMin = 641)
            ),
            isUlgowy = false
        )
        assertEquals(listOf(6, 4), split.pekaStopGroups)
        assertEquals(5.52, split.pekaCostPln, 0.001)

        // Льготный (Ulgowy 50%) для пересадки > 20 мин: 1.59 + 1.17 = 2.76 zł
        val splitUlgowy = ZtmTicketCalculator.calculateForLegs(
            listOf(
                makeLeg("L1", stopCount = 6, startMin = 600, endMin = 610),
                makeLeg("L2", stopCount = 4, startMin = 631, endMin = 641)
            ),
            isUlgowy = true
        )
        assertEquals(2.76, splitUlgowy.pekaCostPln, 0.001)
    }
}
