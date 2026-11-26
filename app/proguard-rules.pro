# OffDojade R8 / ProGuard rules

# Square Wire & GTFS-RT protobuf models
-keep class pl.poznan.offdojade.gtfsrt.** { *; }
-keep class com.squareup.wire.** { *; }
-dontwarn com.squareup.wire.**

# MapLibre Native Android JNI & GeoJSON
-keep class org.maplibre.** { *; }
-dontwarn org.maplibre.**

# Room Database & Entities
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class * { *; }
-keep class pl.poznan.offdojade.data.local.entity.** { *; }
-keep class pl.poznan.offdojade.data.local.GtfsDatabase_Impl { *; }

# WorkManager workers (instantiated via reflection)
-keep class pl.poznan.offdojade.data.worker.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class pl.poznan.offdojade.**$$serializer { *; }
-keepclassmembers class pl.poznan.offdojade.** {
    *** Companion;
}
-keepclasseswithmembers class pl.poznan.offdojade.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio optional platform warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
