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

/**
 * Параметры запроса маршрута, передаваемые между HomeScreen и RouteResultsScreen.
 */
data class RouteSearchRequest(
    /** `null` = старт от текущего GPS-местоположения */
    val from: RouteEndpoint? = null,
    val to: RouteEndpoint
)
