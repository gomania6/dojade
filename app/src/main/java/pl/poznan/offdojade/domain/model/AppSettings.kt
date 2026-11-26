package pl.poznan.offdojade.domain.model

enum class AppLanguage(val code: String, val nativeName: String) {
    RU("ru", "Русский"),
    UK("uk", "Українська"),
    PL("pl", "Polski"),
    EN("en", "English"),
    DE("de", "Deutsch");

    companion object {
        fun fromCode(code: String?): AppLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: RU
    }
}

enum class ThemeMode(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark");

    companion object {
        fun fromKey(key: String?): ThemeMode =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: SYSTEM
    }
}

enum class RoutePreference(val key: String) {
    CONVENIENT("convenient"),
    BEST("best"),
    FASTEST("fastest");

    companion object {
        fun fromKey(key: String?): RoutePreference =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) } ?: BEST
    }
}

/**
 * Все виды транспорта, встречающиеся в GTFS и на транспортных схемах.
 */
enum class TransportMode(val key: String, val gtfsRouteType: Int?) {
    TRAM("tram", 0),
    BUS("bus", 3),
    NIGHT("night", null),
    RAIL("rail", 2),
    SUBWAY("subway", 1),
    TROLLEYBUS("trolleybus", 11),
    FERRY("ferry", 4);

    companion object {
        val ALL: Set<TransportMode> = entries.toSet()

        fun fromKey(key: String): TransportMode? =
            entries.firstOrNull { it.key.equals(key, ignoreCase = true) }

        /**
         * Определяет, является ли линия ночной (в ZTM Poznań это номера 201–259 или начинающиеся на N).
         */
        fun isNightLine(shortName: String): Boolean {
            val trimmed = shortName.trim().uppercase()
            if (trimmed.startsWith("N") && trimmed.length >= 2 && trimmed[1].isDigit()) return true
            val num = trimmed.toIntOrNull()
            return num != null && num in 201..259
        }

        fun matches(routeType: Int, shortName: String, enabled: Set<TransportMode>): Boolean {
            if (isNightLine(shortName) && NIGHT !in enabled) return false
            val modeForType = when (routeType) {
                0, 900 -> TRAM
                1, 400 -> SUBWAY
                2, 100, 109 -> RAIL
                3, 700 -> BUS
                4, 1000 -> FERRY
                11, 800 -> TROLLEYBUS
                else -> BUS
            }
            return modeForType in enabled
        }
    }
}

data class TripSettings(
    val routePreference: RoutePreference = RoutePreference.BEST,
    val avoidTransfers: Boolean = false,
    val enabledTransportModes: Set<TransportMode> = TransportMode.ALL,
    val excludeBuses: Boolean = false,
    val excludedLines: Set<String> = emptySet(),
    val preferredLines: Set<String> = emptySet(),
    val excludedZones: Set<String> = emptySet(),
    val lowFloorOnly: Boolean = false,
    val minTransferMinutes: Int = 2,
    val isUlgowyTicket: Boolean = false
) {
    val effectiveMaxTransfers: Int
        get() = if (avoidTransfers) 1 else 3

    val minTransferSeconds: Int
        get() = minTransferMinutes.coerceIn(0, 30) * 60

    fun isLineAllowed(routeType: Int, shortName: String): Boolean {
        val normalized = shortName.trim().uppercase()
        if (excludedLines.any { it.trim().equals(normalized, ignoreCase = true) }) {
            return false
        }
        if (excludeBuses && (routeType == 3 || routeType == 700)) {
            return false
        }
        return TransportMode.matches(routeType, shortName, enabledTransportModes)
    }

    fun isLinePreferred(shortName: String): Boolean {
        if (preferredLines.isEmpty()) return false
        val normalized = shortName.trim().uppercase()
        return preferredLines.any { it.trim().equals(normalized, ignoreCase = true) }
    }

    fun isZoneAllowed(zoneId: String): Boolean {
        if (excludedZones.isEmpty()) return true
        val normalized = zoneId.trim().uppercase().ifEmpty { "A" }
        return excludedZones.none { it.trim().equals(normalized, ignoreCase = true) }
    }

    val hasActiveFilters: Boolean
        get() = routePreference != RoutePreference.BEST ||
            avoidTransfers ||
            excludeBuses ||
            enabledTransportModes != TransportMode.ALL ||
            excludedLines.isNotEmpty() ||
            preferredLines.isNotEmpty() ||
            excludedZones.isNotEmpty() ||
            lowFloorOnly ||
            minTransferMinutes != 2
}

data class AppSettings(
    val language: AppLanguage = AppLanguage.RU,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val tripSettings: TripSettings = TripSettings()
)
