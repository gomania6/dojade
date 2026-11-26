package pl.poznan.offdojade.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import pl.poznan.offdojade.data.local.dao.GtfsDao
import pl.poznan.offdojade.data.local.entity.AgencyEntity
import pl.poznan.offdojade.data.local.entity.CalendarDateEntity
import pl.poznan.offdojade.data.local.entity.CalendarEntity
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.ShapePointEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import pl.poznan.offdojade.data.local.entity.TripEntity

@Database(
    entities = [
        AgencyEntity::class,
        RouteEntity::class,
        StopEntity::class,
        CalendarEntity::class,
        CalendarDateEntity::class,
        TripEntity::class,
        StopTimeEntity::class,
        ShapePointEntity::class,
        TransferEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class GtfsDatabase : RoomDatabase() {
    abstract fun gtfsDao(): GtfsDao
}
