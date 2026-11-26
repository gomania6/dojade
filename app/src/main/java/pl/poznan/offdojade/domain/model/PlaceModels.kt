package pl.poznan.offdojade.domain.model

/**
 * Найденный адрес в Познани и агломерации (Geocoder / Nominatim).
 */
data class AddressResult(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val latitude: Double,
    val longitude: Double
)

/**
 * Унифицированная точка маршрута (Пункт А или Пункт Б):
 * либо объединённая остановка [Stop], либо произвольный адрес [Address].
 */
sealed interface RouteEndpoint {
    val displayName: String
    val latitude: Double
    val longitude: Double

    data class Stop(val group: StopGroup) : RouteEndpoint {
        override val displayName: String get() = group.displayName
        override val latitude: Double get() = group.latitude
        override val longitude: Double get() = group.longitude
    }

    data class Address(val address: AddressResult) : RouteEndpoint {
        override val displayName: String get() = address.displayName
        override val latitude: Double get() = address.latitude
        override val longitude: Double get() = address.longitude
    }
}

enum class TimeQueryMode {
    DEPART_AT,
    ARRIVE_BY
}

/**
 * Запланированное время поездки:
 * - [timeSec] == null -> «Сейчас» (текущее время)
 * - [mode] == DEPART_AT -> выезд в [timeSec]
 * - [mode] == ARRIVE_BY -> прибытие не позже [timeSec]
 */
data class PlannedTime(
    val mode: TimeQueryMode = TimeQueryMode.DEPART_AT,
    val timeSec: Int? = null
) {
    val isNow: Boolean get() = timeSec == null
}

/**
 * Параметры запроса маршрута, передаваемые между HomeScreen и RouteResultsScreen.
 */
data class RouteSearchRequest(
    /** `null` = старт от текущего GPS-местоположения */
    val from: RouteEndpoint? = null,
    val to: RouteEndpoint,
    val plannedTime: PlannedTime = PlannedTime()
)
