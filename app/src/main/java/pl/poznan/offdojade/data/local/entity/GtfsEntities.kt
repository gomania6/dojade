package pl.poznan.offdojade.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "agencies")
data class AgencyEntity(
    @PrimaryKey
    @ColumnInfo(name = "agency_id") val agencyId: String,
    @ColumnInfo(name = "agency_name") val agencyName: String,
    @ColumnInfo(name = "agency_url") val agencyUrl: String,
    @ColumnInfo(name = "agency_timezone") val agencyTimezone: String
)

@Entity(
    tableName = "routes",
    indices = [Index(value = ["short_name"])]
)
data class RouteEntity(
    @PrimaryKey
    @ColumnInfo(name = "route_id") val routeId: String,
    @ColumnInfo(name = "agency_id") val agencyId: String,
    @ColumnInfo(name = "short_name") val shortName: String,
    @ColumnInfo(name = "long_name") val longName: String,
    /** GTFS route_type: 0 = Tram, 3 = Bus, 2 = Rail */
    @ColumnInfo(name = "route_type") val routeType: Int,
    @ColumnInfo(name = "route_color") val routeColor: String?,
    @ColumnInfo(name = "route_text_color") val routeTextColor: String?
)

@Entity(
    tableName = "stops",
    indices = [
        Index(value = ["stop_name"]),
        Index(value = ["stop_lat", "stop_lon"])
    ]
)
data class StopEntity(
    @PrimaryKey
    @ColumnInfo(name = "stop_id") val stopId: String,
    @ColumnInfo(name = "stop_code") val stopCode: String,
    @ColumnInfo(name = "stop_name") val stopName: String,
    @ColumnInfo(name = "stop_lat") val stopLat: Double,
    @ColumnInfo(name = "stop_lon") val stopLon: Double,
    /** Тарифная зона ZTM Poznań: A, B, C, D */
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "platform_code") val platformCode: String
)

@Entity(tableName = "calendars")
data class CalendarEntity(
    @PrimaryKey
    @ColumnInfo(name = "service_id") val serviceId: String,
    val monday: Boolean,
    val tuesday: Boolean,
    val wednesday: Boolean,
    val thursday: Boolean,
    val friday: Boolean,
    val saturday: Boolean,
    val sunday: Boolean,
    /** YYYYMMDD */
    @ColumnInfo(name = "start_date") val startDate: Int,
    /** YYYYMMDD */
    @ColumnInfo(name = "end_date") val endDate: Int
)

@Entity(
    tableName = "calendar_dates",
    primaryKeys = ["service_id", "date"],
    indices = [
        Index(value = ["date"]),
        Index(value = ["service_id"])
    ]
)
data class CalendarDateEntity(
    @ColumnInfo(name = "service_id") val serviceId: String,
    /** YYYYMMDD */
    val date: Int,
    /** 1 = added, 2 = removed */
    @ColumnInfo(name = "exception_type") val exceptionType: Int
)

@Entity(
    tableName = "trips",
    indices = [
        Index(value = ["service_id"]),
        Index(value = ["route_id"]),
        Index(value = ["shape_id"])
    ]
)
data class TripEntity(
    @PrimaryKey
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "route_id") val routeId: String,
    @ColumnInfo(name = "service_id") val serviceId: String,
    @ColumnInfo(name = "trip_headsign") val tripHeadsign: String,
    @ColumnInfo(name = "direction_id") val directionId: Int,
    @ColumnInfo(name = "shape_id") val shapeId: String,
    /** GTFS wheelchair_accessible: 0 = unknown, 1 = low-floor / accessible, 2 = not accessible */
    @ColumnInfo(name = "wheelchair_accessible", defaultValue = "0") val wheelchairAccessible: Int = 0
)

@Entity(
    tableName = "stop_times",
    primaryKeys = ["trip_id", "stop_sequence"],
    indices = [
        Index(value = ["stop_id"]),
        Index(value = ["trip_id"])
    ]
)
data class StopTimeEntity(
    @ColumnInfo(name = "trip_id") val tripId: String,
    @ColumnInfo(name = "stop_sequence") val stopSequence: Int,
    @ColumnInfo(name = "stop_id") val stopId: String,
    /** Секунды от начала суток (может быть >= 86400 для ночных рейсов после полуночи) */
    @ColumnInfo(name = "arrival_time_sec") val arrivalTimeSec: Int,
    @ColumnInfo(name = "departure_time_sec") val departureTimeSec: Int,
    /** 1 = остановка по требованию (GTFS pickup_type = 3 или drop_off_type = 3) */
    @ColumnInfo(name = "is_on_demand", defaultValue = "0") val isOnDemand: Int = 0
)

@Entity(
    tableName = "shapes",
    primaryKeys = ["shape_id", "sequence"],
    indices = [Index(value = ["shape_id"])]
)
data class ShapePointEntity(
    @ColumnInfo(name = "shape_id") val shapeId: String,
    val sequence: Int,
    val lat: Double,
    val lon: Double
)

@Entity(
    tableName = "transfers",
    primaryKeys = ["from_stop_id", "to_stop_id"],
    indices = [Index(value = ["from_stop_id"])]
)
data class TransferEntity(
    @ColumnInfo(name = "from_stop_id") val fromStopId: String,
    @ColumnInfo(name = "to_stop_id") val toStopId: String,
    @ColumnInfo(name = "transfer_type") val transferType: Int,
    @ColumnInfo(name = "min_transfer_time_sec") val minTransferTimeSec: Int,
    @ColumnInfo(name = "distance_meters") val distanceMeters: Int
)
