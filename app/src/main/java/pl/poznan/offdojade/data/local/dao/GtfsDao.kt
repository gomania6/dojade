package pl.poznan.offdojade.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import pl.poznan.offdojade.data.local.entity.AgencyEntity
import pl.poznan.offdojade.data.local.entity.CalendarDateEntity
import pl.poznan.offdojade.data.local.entity.CalendarEntity
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.ShapePointEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import pl.poznan.offdojade.data.local.entity.TripEntity

@Dao
interface GtfsDao {

    // --- Batch Inserts (вызываются внутри @Transaction батчами по 2000–5000 записей) ---

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgencies(items: List<AgencyEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutes(items: List<RouteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStops(items: List<StopEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendars(items: List<CalendarEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalendarDates(items: List<CalendarDateEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrips(items: List<TripEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStopTimes(items: List<StopTimeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertShapes(items: List<ShapePointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransfers(items: List<TransferEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertGeneratedFootpaths(items: List<TransferEntity>)

    // --- Clear ---

    @Query("DELETE FROM agencies")
    suspend fun clearAgencies()

    @Query("DELETE FROM routes")
    suspend fun clearRoutes()

    @Query("DELETE FROM stops")
    suspend fun clearStops()

    @Query("DELETE FROM calendars")
    suspend fun clearCalendars()

    @Query("DELETE FROM calendar_dates")
    suspend fun clearCalendarDates()

    @Query("DELETE FROM trips")
    suspend fun clearTrips()

    @Query("DELETE FROM stop_times")
    suspend fun clearStopTimes()

    @Query("DELETE FROM shapes")
    suspend fun clearShapes()

    @Query("DELETE FROM transfers")
    suspend fun clearTransfers()

    @Transaction
    suspend fun clearAllGtfsData() {
        clearStopTimes()
        clearTrips()
        clearCalendarDates()
        clearCalendars()
        clearTransfers()
        clearShapes()
        clearStops()
        clearRoutes()
        clearAgencies()
    }

    // --- Queries ---

    @Query("SELECT COUNT(*) FROM stops")
    suspend fun getStopCount(): Int

    @Query("SELECT COUNT(*) FROM trips")
    suspend fun getTripCount(): Int

    @Query("SELECT * FROM stops")
    suspend fun getAllStops(): List<StopEntity>

    @Query("SELECT * FROM stops WHERE stop_id = :stopId LIMIT 1")
    suspend fun getStopById(stopId: String): StopEntity?

    @Query(
        """
        SELECT * FROM stops
        WHERE stop_name LIKE '%' || :query || '%' OR stop_code LIKE '%' || :query || '%'
        ORDER BY
            CASE WHEN stop_name LIKE :query || '%' THEN 0 ELSE 1 END,
            stop_name ASC
        LIMIT :limit
        """
    )
    suspend fun searchStops(query: String, limit: Int = 25): List<StopEntity>

    @Query("SELECT * FROM routes")
    suspend fun getAllRoutes(): List<RouteEntity>

    @Query("SELECT * FROM calendars")
    suspend fun getAllCalendars(): List<CalendarEntity>

    @Query("SELECT * FROM calendar_dates WHERE date = :dateYyyymmdd")
    suspend fun getCalendarDatesForDate(dateYyyymmdd: Int): List<CalendarDateEntity>

    @Query("SELECT DISTINCT date FROM calendar_dates WHERE service_id LIKE 'rail_%' AND exception_type = 1 ORDER BY date ASC")
    suspend fun getDistinctRailCalendarDates(): List<Int>

    @Query("SELECT * FROM transfers")
    suspend fun getAllTransfers(): List<TransferEntity>

    @Query("SELECT * FROM trips WHERE service_id IN (:serviceIds)")
    suspend fun getTripsForServices(serviceIds: List<String>): List<TripEntity>

    @Query(
        """
        SELECT st.* FROM stop_times st
        INNER JOIN trips t ON st.trip_id = t.trip_id
        WHERE t.service_id IN (:serviceIds)
        ORDER BY st.trip_id ASC, st.stop_sequence ASC
        """
    )
    suspend fun getStopTimesForServices(serviceIds: List<String>): List<StopTimeEntity>

    @Query("SELECT * FROM shapes WHERE shape_id = :shapeId ORDER BY sequence ASC")
    suspend fun getShapePoints(shapeId: String): List<ShapePointEntity>

    @Query("SELECT * FROM trips WHERE route_id = :routeId AND service_id IN (:serviceIds)")
    suspend fun getTripsForRouteAndServices(routeId: String, serviceIds: List<String>): List<TripEntity>

    @Query("SELECT DISTINCT route_id FROM trips WHERE service_id IN (:serviceIds)")
    suspend fun getActiveRouteIdsForServices(serviceIds: List<String>): List<String>

    @Query("SELECT * FROM stop_times WHERE trip_id IN (:tripIds) ORDER BY trip_id ASC, stop_sequence ASC")
    suspend fun getStopTimesForTrips(tripIds: List<String>): List<StopTimeEntity>
}
