package pl.poznan.offdojade.domain.model

import pl.poznan.offdojade.config.ZtmTariffConfig

enum class CatchabilityStatus {
    COMFORTABLE,
    HURRY,
    TOO_TIGHT
}

data class CatchabilityInfo(
    val walkDistanceMeters: Int,
    val walkTimeSeconds: Int,
    val secondsUntilDeparture: Int,
    val marginSeconds: Int,
    val status: CatchabilityStatus
)

enum class CheapestTicketChoice {
    PEKA_TPORTMONETKA,
    TIME_TICKET,
    EQUAL
}

data class TicketRecommendation(
    val pureDurationMinutes: Int,
    val delayMinutes: Int,
    val bufferMinutes: Int,
    val totalEstimatedMinutes: Int,
    val recommendedTimeTicket: ZtmTariffConfig.TimeTicketOption,
    val tightFitWarning: String?,
    val totalStopsPassed: Int,
    val pekaCostPln: Double,
    val cheapestChoice: CheapestTicketChoice,
    val savingsPln: Double,
    val zones: List<String>,
    val isUlgowy: Boolean = false,
    val recommendedTimeTicketPricePln: Double = recommendedTimeTicket.priceFor(isUlgowy),
    /** Группы остановок для PEKA tPortmonetka (разделяются, если пересадка > 20 мин) */
    val pekaStopGroups: List<Int> = if (totalStopsPassed > 0) listOf(totalStopsPassed) else emptyList(),
    /** true, если маршрут содержит линии PKS (тариф ZTM не применяется — цену скрываем) */
    val hasNonZtmPksLeg: Boolean = false,
    /** true, если маршрут содержит пригородную электричку (Koleje Wielkopolskie / Polregio) */
    val hasRailLeg: Boolean = false
) {
    val zonesBadgeText: String
        get() = if (zones.isEmpty()) "Зона: A" else "Зоны: ${zones.joinToString(" + ")}"
}

data class LegStop(
    val stopId: String,
    val stopName: String,
    val platformCode: String,
    val zoneId: String,
    val latitude: Double,
    val longitude: Double,
    val scheduledTimeSec: Int,
    val effectiveTimeSec: Int,
    /** Остановка по требованию (Przystanek na żądanie — GTFS pickup_type=3 / drop_off_type=3) */
    val isOnDemand: Boolean = false
)

sealed interface JourneyLeg {
    val startTimeSec: Int
    val endTimeSec: Int
    val durationSec: Int get() = (endTimeSec - startTimeSec).coerceAtLeast(0)

    data class WalkLeg(
        val fromName: String,
        val toStopId: String,
        val toName: String,
        val fromLat: Double,
        val fromLon: Double,
        val toLat: Double,
        val toLon: Double,
        val distanceMeters: Int,
        override val startTimeSec: Int,
        override val endTimeSec: Int
    ) : JourneyLeg

    data class TransitLeg(
        val tripId: String,
        val routeShortName: String,
        /** GTFS route_type: 0 = Tram, 3 = Bus */
        val routeType: Int,
        val routeColorHex: String?,
        val headsign: String,
        val shapeId: String,
        val delaySeconds: Int,
        val stops: List<LegStop>,
        override val startTimeSec: Int,
        override val endTimeSec: Int,
        /** GTFS wheelchair_accessible: 1 = low-floor */
        val wheelchairAccessible: Int = 0
    ) : JourneyLeg {
        val isTram: Boolean get() = routeType == 0 || routeType == 900
        val isRail: Boolean get() = routeType == 2 || routeType == 100 || routeType == 109
        val isLowFloor: Boolean get() = wheelchairAccessible == 1
        /** Количество проезжаемых остановок (интервалов между посадкой и высадкой) */
        val stopsPassed: Int get() = (stops.size - 1).coerceAtLeast(0)
        val boardingStop: LegStop get() = stops.first()
        val alightingStop: LegStop get() = stops.last()
    }
}

enum class JourneyAlternativeTag {
    STANDARD,
    DIRECT,
    TRAM_ONLY,
    BUS_ONLY
}

data class TransitJourney(
    val id: String,
    val legs: List<JourneyLeg>,
    val departureTimeSec: Int,
    val arrivalTimeSec: Int,
    val transferCount: Int,
    val ticketRecommendation: TicketRecommendation,
    val catchability: CatchabilityInfo?,
    val searchDurationMs: Long = 0L,
    val alternativeTag: JourneyAlternativeTag = JourneyAlternativeTag.STANDARD
) {
    val totalDurationMinutes: Int
        get() = ((arrivalTimeSec - departureTimeSec).coerceAtLeast(0) + 59) / 60

    val transitLegs: List<JourneyLeg.TransitLeg>
        get() = legs.filterIsInstance<JourneyLeg.TransitLeg>()

    /** Время отправления первого автобуса/трамвая (без учёта пешего подхода до остановки) */
    val transitDepartureTimeSec: Int
        get() = transitLegs.firstOrNull()?.startTimeSec ?: departureTimeSec

    val isAllLowFloor: Boolean
        get() = transitLegs.isNotEmpty() && transitLegs.all { it.isLowFloor }

    val totalWalkMeters: Int
        get() = legs.filterIsInstance<JourneyLeg.WalkLeg>().sumOf { it.distanceMeters }

    /** true, если маршрут запланирован на будущий день (сдвинут через `d1_`, `d2_` и т.д.) */
    val isFutureDay: Boolean
        get() = id.startsWith("d") && id.indexOf('_') > 1
}

data class UpcomingDeparture(
    val tripId: String,
    val routeShortName: String,
    val routeType: Int,
    val routeColorHex: String?,
    val headsign: String,
    val scheduledTimeSec: Int,
    val delaySeconds: Int,
    val effectiveTimeSec: Int,
    val minutesUntilDeparture: Int
)

data class StopGroup(
    val displayName: String,
    val primaryStopId: String,
    val stopIds: List<String>,
    val zones: List<String>,
    val platformCount: Int,
    val latitude: Double,
    val longitude: Double
) {
    val zonesLabel: String
        get() = if (zones.isEmpty()) "Зона A" else "Зона ${zones.joinToString("/")}"
}

data class NearbyStopBoard(
    val stopId: String,
    val stopName: String,
    val zoneId: String,
    val platformCode: String,
    val distanceMeters: Int,
    val walkTimeSeconds: Int,
    val departures: List<UpcomingDeparture>,
    val platformCount: Int = 1
)
