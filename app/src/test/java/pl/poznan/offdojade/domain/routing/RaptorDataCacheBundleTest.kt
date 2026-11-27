package pl.poznan.offdojade.domain.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import pl.poznan.offdojade.data.local.entity.RouteEntity
import pl.poznan.offdojade.data.local.entity.StopEntity
import pl.poznan.offdojade.data.local.entity.StopTimeEntity
import pl.poznan.offdojade.data.local.entity.TransferEntity
import pl.poznan.offdojade.data.local.entity.TripEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RaptorDataCacheBundleTest {

    @Test
    fun installPrebuiltBundle_unpacksDeduplicatedDaysAndPatchesHeaders() {
        val tempDir = Files.createTempDirectory("raptor_bundle_test").toFile()
        try {
            val stops = listOf(
                StopEntity("S1", "01", "Most Teatralny", 52.411, 16.912, "A", "1"),
                StopEntity("S2", "02", "Rondo Śródka", 52.408, 16.955, "A", "2")
            )
            val routes = listOf(
                RouteEntity("12", "1", "12", "Os. Sobieskiego - Starołęka", 0, "1565C0", "FFFFFF")
            )
            val weekdayGraph = TransitGraphBuilder.build(
                serviceDateYyyymmdd = 0,
                stops = stops,
                routes = routes,
                trips = listOf(TripEntity("T_WK", "12", "WK", "Starołęka", 0, "SH1", 1)),
                stopTimes = listOf(
                    StopTimeEntity("T_WK", 1, "S1", 8 * 3600, 8 * 3600, 0),
                    StopTimeEntity("T_WK", 2, "S2", 8 * 3600 + 600, 8 * 3600 + 600, 1)
                ),
                transfers = listOf(TransferEntity("S1", "S2", 2, 180, 220))
            )
            val weekendGraph = TransitGraphBuilder.build(
                serviceDateYyyymmdd = 0,
                stops = stops,
                routes = routes,
                trips = listOf(TripEntity("T_WE", "12", "WE", "Starołęka", 0, "SH1", 1)),
                stopTimes = listOf(
                    StopTimeEntity("T_WE", 1, "S1", 9 * 3600, 9 * 3600, 0),
                    StopTimeEntity("T_WE", 2, "S2", 9 * 3600 + 600, 9 * 3600 + 600, 0)
                ),
                transfers = listOf(TransferEntity("S1", "S2", 2, 180, 220))
            )

            val wkFile = File(tempDir, "raw_wk.bin")
            val weFile = File(tempDir, "raw_we.bin")
            RaptorDataCache.save(wkFile, 0L, weekdayGraph)
            RaptorDataCache.save(weFile, 0L, weekendGraph)
            val wkBytes = wkFile.readBytes()
            val weBytes = weFile.readBytes()

            // Собираем ODRB v1 bundle: 3 дня -> 2 уникальных графа
            val baos = ByteArrayOutputStream()
            GZIPOutputStream(baos).use { gz ->
                DataOutputStream(gz).use { out ->
                    out.writeInt(0x4F445242) // "ODRB"
                    out.writeInt(1)          // version
                    out.writeInt(3)          // 3 dates
                    out.writeInt(20261127); out.writeInt(0)
                    out.writeInt(20261128); out.writeInt(1)
                    out.writeInt(20261130); out.writeInt(0)
                    out.writeInt(2)          // 2 unique graphs
                    out.writeInt(wkBytes.size); out.write(wkBytes)
                    out.writeInt(weBytes.size); out.write(weBytes)
                }
            }

            val cacheDir = File(tempDir, "cache")
            val fingerprint = 0x123456789ABCDEF0L
            val installed = RaptorDataCache.installPrebuiltBundle(
                gzipStream = ByteArrayInputStream(baos.toByteArray()),
                cacheDir = cacheDir,
                dbFingerprint = fingerprint
            )
            assertEquals(3, installed)

            val loadedFri = RaptorDataCache.load(
                file = File(cacheDir, "raptor_graph_v1_20261127.bin"),
                expectedDateYyyymmdd = 20261127,
                expectedDbFingerprint = fingerprint
            )
            assertNotNull(loadedFri)
            assertEquals(20261127, loadedFri!!.serviceDateYyyymmdd)
            assertEquals("Rondo Śródka", loadedFri.stopNames[1])
            assertEquals("T_WK", loadedFri.tripIds[0])
            assertEquals(1.toByte(), loadedFri.stopTimeOnDemand[1])

            val loadedSat = RaptorDataCache.load(
                file = File(cacheDir, "raptor_graph_v1_20261128.bin"),
                expectedDateYyyymmdd = 20261128,
                expectedDbFingerprint = fingerprint
            )
            assertNotNull(loadedSat)
            assertEquals(20261128, loadedSat!!.serviceDateYyyymmdd)
            assertEquals("T_WE", loadedSat.tripIds[0])
            assertEquals(9 * 3600, loadedSat.departureTimesSec[0])

            // Неверный fingerprint отклоняется
            assertNull(
                RaptorDataCache.load(
                    file = File(cacheDir, "raptor_graph_v1_20261127.bin"),
                    expectedDateYyyymmdd = 20261127,
                    expectedDbFingerprint = fingerprint + 1L
                )
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun pythonBuilderPrebuiltBundle_isByteCompatibleWithKotlinLoader() {
        val scriptFile = listOf(
            File("scripts/build_gtfs_db.py"),
            File("../scripts/build_gtfs_db.py")
        ).firstOrNull { it.exists() }
        assumeTrue("build_gtfs_db.py not found", scriptFile != null)

        val tempDir = Files.createTempDirectory("py_raptor_compat").toFile()
        try {
            val ztmZip = File(tempDir, "ztm.zip")
            ZipOutputStream(ztmZip.outputStream()).use { zos ->
                fun addCsv(name: String, content: String) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                addCsv(
                    "agency.txt",
                    "agency_id,agency_name,agency_url,agency_timezone\n1,ZTM Poznań,https://ztm.poznan.pl,Europe/Warsaw\n"
                )
                addCsv(
                    "routes.txt",
                    "route_id,agency_id,route_short_name,route_long_name,route_type,route_color\n12,1,12,Os. Sobieskiego - Starołęka,0,1565C0\n"
                )
                addCsv(
                    "stops.txt",
                    "stop_id,stop_code,stop_name,stop_lat,stop_lon,zone_id,platform_code\n" +
                        "S1,01,Poznań Główny,52.402,16.912,A,1\n" +
                        "S2,02,Rondo Śródka,52.408,16.955,A,2\n"
                )
                addCsv(
                    "calendar.txt",
                    "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                        "WK,1,1,1,1,1,1,1,20260101,20271231\n"
                )
                addCsv(
                    "trips.txt",
                    "trip_id,route_id,service_id,trip_headsign,direction_id,shape_id,wheelchair_accessible\n" +
                        "T1,12,WK,Starołęka Mała,0,SH1,1\n"
                )
                addCsv(
                    "stop_times.txt",
                    "trip_id,arrival_time,departure_time,stop_id,stop_sequence,pickup_type,drop_off_type\n" +
                        "T1,08:15:00,08:15:00,S1,1,0,0\n" +
                        "T1,08:25:00,08:25:00,S2,2,3,3\n"
                )
                addCsv(
                    "shapes.txt",
                    "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nSH1,52.402,16.912,1\nSH1,52.408,16.955,2\n"
                )
            }

            val outDir = File(tempDir, "out")
            val proc = runCatching {
                ProcessBuilder(
                    "python",
                    scriptFile!!.absolutePath,
                    "--zip", ztmZip.absolutePath,
                    "--no-rail",
                    "--output-dir", outDir.absolutePath
                ).redirectErrorStream(true).start()
            }.getOrNull()
            assumeTrue("python not available in PATH", proc != null)
            val exitCode = proc!!.waitFor()
            val stdout = proc.inputStream.bufferedReader().readText()
            assertEquals("Python builder failed:\n$stdout", 0, exitCode)

            val bundleFile = File(outDir, "raptor_graphs.bundle.gz")
            assertTrue("raptor_graphs.bundle.gz was not created", bundleFile.exists() && bundleFile.length() > 0L)

            val cacheDir = File(tempDir, "android_cache")
            val fp = 9876543210L
            val installedCount = FileInputStream(bundleFile).use { fis ->
                RaptorDataCache.installPrebuiltBundle(fis, cacheDir, fp)
            }
            assertEquals(7, installedCount)

            val binFiles = cacheDir.listFiles { f -> f.name.startsWith("raptor_graph_v1_") && f.name.endsWith(".bin") }
                .orEmpty()
                .sortedBy { it.name }
            assertEquals(7, binFiles.size)

            val firstFile = binFiles.first()
            val expectedDate = firstFile.name.removePrefix("raptor_graph_v1_").removeSuffix(".bin").toInt()
            val loaded = RaptorDataCache.load(firstFile, expectedDate, fp)
            assertNotNull("Kotlin failed to load Python-built RaptorData binary!", loaded)
            assertEquals(2, loaded!!.stopCount)
            assertEquals("Poznań Główny", loaded.stopNames[0])
            assertEquals("Rondo Śródka", loaded.stopNames[1])
            assertEquals(1, loaded.patternCount)
            assertEquals("12", loaded.patternRouteShortName[0])
            assertEquals("Starołęka Mała", loaded.patternHeadsign[0])
            assertEquals(1, loaded.tripCount)
            assertEquals("T1", loaded.tripIds[0])
            assertEquals(8 * 3600 + 15 * 60, loaded.departureTimesSec[0])
            assertEquals(1.toByte(), loaded.stopTimeOnDemand[1])
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
