package pl.poznan.offdojade.domain.tariff

import pl.poznan.offdojade.config.ZtmTariffConfig
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.JourneyLeg
import pl.poznan.offdojade.domain.model.TicketRecommendation
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * Умный калькулятор билетов ZTM Poznań:
 * 1. Временные билеты (15 / 45 / 90 мин / 24 ч) с учетом задержек GTFS-RT и буфера +5 мин.
 * 2. PEKA tPortmonetka (дегрессивный тариф за количество проезжаемых остановок).
 * 3. Автоматическое определение тарифных зон (A, B, C, D).
 */
object ZtmTicketCalculator {

    fun calculateForLegs(legs: List<JourneyLeg>, isUlgowy: Boolean = false): TicketRecommendation {
        val transitLegs = legs.filterIsInstance<JourneyLeg.TransitLeg>()
        if (transitLegs.isEmpty()) {
            return calculate(
                pureTransitMinutes = 0,
                delayMinutes = 0,
                totalStopsPassed = 0,
                zones = setOf("A"),
                isUlgowy = isUlgowy
            )
        }

        // Чистое время поездки по расписанию: от первой посадки до последней высадки без положительных опозданий
        val firstTransit = transitLegs.first()
        val lastTransit = transitLegs.last()
        val scheduledStart = firstTransit.boardingStop.scheduledTimeSec
        val scheduledEnd = lastTransit.alightingStop.scheduledTimeSec
        val pureSec = (scheduledEnd - scheduledStart).coerceAtLeast(60)
        val pureMinutes = ceil(pureSec / 60.0).toInt()

        // Суммарное положительное опоздание на сегментах маршрута (или максимальное влияние на поездку)
        val totalPositiveDelaySec = transitLegs
            .sumOf { it.delaySeconds.coerceAtLeast(0) }
        val delayMinutes = ceil(totalPositiveDelaySec / 60.0).toInt()

        val totalStopsPassed = transitLegs.sumOf { it.stopsPassed }

        // Группируем остановки по правилу PEKA tPortmonetka:
        // пересадка <= 20 мин (и до 3 пересадок) продолжает дегрессивный тариф;
        // если пересадка > 20 мин — начинается новый отсчёт с 1-й остановки.
        val pekaStopGroups = ArrayList<Int>(transitLegs.size)
        var currentGroupStops = transitLegs.first().stopsPassed
        var transfersInGroup = 0
        for (i in 1 until transitLegs.size) {
            val prevLeg = transitLegs[i - 1]
            val curLeg = transitLegs[i]
            val transferGapSec = (curLeg.startTimeSec - prevLeg.endTimeSec).coerceAtLeast(0)
            if (transferGapSec <= ZtmTariffConfig.PEKA_MAX_TRANSFER_WINDOW_SEC &&
                transfersInGroup < ZtmTariffConfig.PEKA_MAX_CONTINUOUS_TRANSFERS
            ) {
                currentGroupStops += curLeg.stopsPassed
                transfersInGroup++
            } else {
                pekaStopGroups.add(currentGroupStops)
                currentGroupStops = curLeg.stopsPassed
                transfersInGroup = 0
            }
        }
        pekaStopGroups.add(currentGroupStops)

        val zones = linkedSetOf<String>()
        for (leg in transitLegs) {
            for (stop in leg.stops) {
                val z = stop.zoneId.trim().uppercase(Locale.ROOT)
                if (z.isNotEmpty()) zones.add(z)
            }
        }

        return calculate(
            pureTransitMinutes = pureMinutes,
            delayMinutes = delayMinutes,
            totalStopsPassed = totalStopsPassed,
            zones = zones.ifEmpty { setOf("A") },
            isUlgowy = isUlgowy,
            pekaStopGroups = pekaStopGroups
        )
    }

    fun calculate(
        pureTransitMinutes: Int,
        delayMinutes: Int,
        totalStopsPassed: Int,
        zones: Set<String>,
        isUlgowy: Boolean = false,
        pekaStopGroups: List<Int> = if (totalStopsPassed > 0) listOf(totalStopsPassed) else emptyList()
    ): TicketRecommendation {
        val safePure = pureTransitMinutes.coerceAtLeast(0)
        val safeDelay = delayMinutes.coerceAtLeast(0)
        val buffer = ZtmTariffConfig.SAFETY_BUFFER_MINUTES
        val totalEstimated = safePure + safeDelay + buffer

        val tickets = ZtmTariffConfig.TIME_TICKETS
        val pureTicket = tickets.firstOrNull { safePure <= it.maxMinutes } ?: tickets.last()
        val recommendedTicket = tickets.firstOrNull { totalEstimated <= it.maxMinutes } ?: tickets.last()
        val recommendedPrice = recommendedTicket.priceFor(isUlgowy)

        val tightFitWarning = if (recommendedTicket.maxMinutes > pureTicket.maxMinutes) {
            val delayPart = if (safeDelay > 0) "опоздания +$safeDelay мин и " else ""
            "Чистое время $safePure мин, но с учетом ${delayPart}буфера +$buffer мин ($totalEstimated мин) " +
                "билет на ${pureTicket.label} впритык — рекомендуем ${recommendedTicket.label} " +
                "(${formatPln(recommendedPrice)})"
        } else {
            null
        }

        val effectiveGroups = pekaStopGroups.ifEmpty {
            if (totalStopsPassed > 0) listOf(totalStopsPassed) else emptyList()
        }
        val pekaCost = ZtmTariffConfig.calculatePekaCostForGroupsPln(effectiveGroups, isUlgowy = isUlgowy)
        val diff = round((recommendedPrice - pekaCost) * 100.0) / 100.0
        val cheapest = when {
            totalStopsPassed <= 0 -> CheapestTicketChoice.TIME_TICKET
            diff > 0.005 -> CheapestTicketChoice.PEKA_TPORTMONETKA
            diff < -0.005 -> CheapestTicketChoice.TIME_TICKET
            else -> CheapestTicketChoice.EQUAL
        }

        val sortedZones = zones
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()

        return TicketRecommendation(
            pureDurationMinutes = safePure,
            delayMinutes = safeDelay,
            bufferMinutes = buffer,
            totalEstimatedMinutes = totalEstimated,
            recommendedTimeTicket = recommendedTicket,
            tightFitWarning = tightFitWarning,
            totalStopsPassed = totalStopsPassed,
            pekaCostPln = pekaCost,
            cheapestChoice = cheapest,
            savingsPln = round(abs(diff) * 100.0) / 100.0,
            zones = sortedZones.ifEmpty { listOf("A") },
            isUlgowy = isUlgowy,
            recommendedTimeTicketPricePln = recommendedPrice,
            pekaStopGroups = effectiveGroups
        )
    }

    fun formatPln(amount: Double): String = String.format(Locale.US, "%.2f zł", amount)
}
