package pl.poznan.offdojade.domain.routing

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Сверхбыстрый бинарный кэш графа [RaptorData] на диске (`cacheDir`).
 * Позволяет загружать дневной граф за ~50–120 мс вместо 4–6 секунд парсинга 350 000 строк из SQLite.
 * Автоматически инвалидируется при смене дня или обновлении файла базы SQLite (`dbFingerprint`).
 */
object RaptorDataCache {

    private const val MAGIC = 0x4F445243 // "ODRC"
    private const val FORMAT_VERSION = 1
    private const val BUNDLE_MAGIC = 0x4F445242 // "ODRB"
    private const val BUNDLE_VERSION = 1

    /**
     * Распаковывает предсобранный на сервере (GitHub Actions) бандл готовых графов RAPTOR
     * (`raptor_graphs.bundle.gz`) прямо в [cacheDir], прописывая локальный [dbFingerprint]
     * и целевую дату `serviceDateYyyymmdd` в заголовок каждого файла.
     * Возвращает количество успешно установленных дневных графов.
     */
    fun installPrebuiltBundle(
        gzipStream: InputStream,
        cacheDir: File,
        dbFingerprint: Long
    ): Int {
        if (dbFingerprint == 0L) return 0
        if (!cacheDir.exists()) cacheDir.mkdirs()

        return DataInputStream(
            GZIPInputStream(BufferedInputStream(gzipStream, 256 * 1024), 256 * 1024)
        ).use { inp ->
            if (inp.readInt() != BUNDLE_MAGIC) return 0
            if (inp.readInt() != BUNDLE_VERSION) return 0

            val dateCount = inp.readInt()
            if (dateCount <= 0 || dateCount > 31) return 0

            val dateToGraphIdx = ArrayList<Pair<Int, Int>>(dateCount)
            for (i in 0 until dateCount) {
                val dateYyyymmdd = inp.readInt()
                val graphIdx = inp.readInt()
                dateToGraphIdx.add(dateYyyymmdd to graphIdx)
            }

            val uniqueCount = inp.readInt()
            if (uniqueCount <= 0 || uniqueCount > 31) return 0

            val payloads = ArrayList<ByteArray>(uniqueCount)
            for (i in 0 until uniqueCount) {
                val len = inp.readInt()
                if (len < 32 || len > 96 * 1024 * 1024) return 0
                val bytes = ByteArray(len)
                inp.readFully(bytes)
                // Проверяем заголовок вложенного файла ODRC v1
                val pMagic = readBigEndianInt(bytes, 0)
                val pVer = readBigEndianInt(bytes, 4)
                if (pMagic != MAGIC || pVer != FORMAT_VERSION) return 0
                payloads.add(bytes)
            }

            var installed = 0
            for ((dateYyyymmdd, graphIdx) in dateToGraphIdx) {
                val payload = payloads.getOrNull(graphIdx) ?: continue
                // Патчим 8 байт dbFingerprint (offset 8..15) и 4 байта serviceDate (offset 16..19)
                writeBigEndianLong(payload, 8, dbFingerprint)
                writeBigEndianInt(payload, 16, dateYyyymmdd)

                val targetFile = File(cacheDir, "raptor_graph_v1_$dateYyyymmdd.bin")
                val tmpFile = File(cacheDir, "${targetFile.name}.tmp")
                runCatching {
                    BufferedOutputStream(FileOutputStream(tmpFile), 128 * 1024).use { out ->
                        out.write(payload)
                        out.flush()
                    }
                    if (targetFile.exists()) targetFile.delete()
                    if (tmpFile.renameTo(targetFile)) {
                        installed++
                    } else {
                        tmpFile.delete()
                    }
                }.onFailure {
                    runCatching { tmpFile.delete() }
                }
            }
            installed
        }
    }

    private fun readBigEndianInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeBigEndianInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = ((value ushr 24) and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 3] = (value and 0xFF).toByte()
    }

    private fun writeBigEndianLong(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = ((value ushr ((7 - i) * 8)) and 0xFFL).toByte()
        }
    }

    fun save(file: File, dbFingerprint: Long, data: RaptorData) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 64 * 1024)).use { out ->
                out.writeInt(MAGIC)
                out.writeInt(FORMAT_VERSION)
                out.writeLong(dbFingerprint)
                out.writeInt(data.serviceDateYyyymmdd)

                // Stops
                out.writeInt(data.stopCount)
                writeStringArray(out, data.stopIds)
                writeStringArray(out, data.stopNames)
                writeDoubleArray(out, data.stopLats)
                writeDoubleArray(out, data.stopLons)
                writeStringArray(out, data.stopZones)
                writeStringArray(out, data.stopPlatforms)

                // Patterns
                out.writeInt(data.patternCount)
                writeIntArray(out, data.patternStopOffsets)
                writeIntArray(out, data.patternStops)
                writeIntArray(out, data.patternTripOffsets)
                writeStringArray(out, data.patternRouteShortName)
                writeIntArray(out, data.patternRouteType)
                writeNullableStringArray(out, data.patternRouteColor)
                writeStringArray(out, data.patternHeadsign)

                // Trips
                out.writeInt(data.tripCount)
                writeStringArray(out, data.tripIds)
                writeStringArray(out, data.tripShapeIds)
                writeIntArray(out, data.tripPatternIndex)
                writeIntArray(out, data.tripWheelchairAccessible)
                writeIntArray(out, data.tripStopTimeOffsets)
                writeIntArray(out, data.arrivalTimesSec)
                writeIntArray(out, data.departureTimesSec)
                writeByteArray(out, data.stopTimeOnDemand)

                // Reverse index
                writeIntArray(out, data.stopToPatternOffsets)
                writeIntArray(out, data.stopToPatterns)
                writeIntArray(out, data.stopToPatternPos)

                // Footpaths
                writeIntArray(out, data.footpathOffsets)
                writeIntArray(out, data.footpathToStop)
                writeIntArray(out, data.footpathWalkSec)
                writeIntArray(out, data.footpathDistanceMeters)
            }
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }.onFailure {
            runCatching { tmp.delete() }
        }
    }

    fun load(file: File, expectedDateYyyymmdd: Int, expectedDbFingerprint: Long): RaptorData? {
        if (!file.exists() || file.length() < 32L) return null
        return runCatching {
            DataInputStream(BufferedInputStream(file.inputStream(), 64 * 1024)).use { inp ->
                if (inp.readInt() != MAGIC) return null
                if (inp.readInt() != FORMAT_VERSION) return null
                if (inp.readLong() != expectedDbFingerprint) return null
                val serviceDate = inp.readInt()
                if (serviceDate != expectedDateYyyymmdd) return null

                // Stops
                val stopCount = inp.readInt()
                val stopIds = readStringArray(inp)
                val stopNames = readStringArray(inp)
                val stopLats = readDoubleArray(inp)
                val stopLons = readDoubleArray(inp)
                val stopZones = readStringArray(inp)
                val stopPlatforms = readStringArray(inp)
                val stopIdToIndex = HashMap<String, Int>(stopCount * 2)
                for (i in 0 until stopCount) {
                    stopIdToIndex[stopIds[i]] = i
                }

                // Patterns
                val patternCount = inp.readInt()
                val patternStopOffsets = readIntArray(inp)
                val patternStops = readIntArray(inp)
                val patternTripOffsets = readIntArray(inp)
                val patternRouteShortName = readStringArray(inp)
                val patternRouteType = readIntArray(inp)
                val patternRouteColor = readNullableStringArray(inp)
                val patternHeadsign = readStringArray(inp)

                // Trips
                val tripCount = inp.readInt()
                val tripIds = readStringArray(inp)
                val tripShapeIds = readStringArray(inp)
                val tripPatternIndex = readIntArray(inp)
                val tripWheelchairAccessible = readIntArray(inp)
                val tripStopTimeOffsets = readIntArray(inp)
                val arrivalTimesSec = readIntArray(inp)
                val departureTimesSec = readIntArray(inp)
                val stopTimeOnDemand = readByteArray(inp)

                // Reverse index
                val stopToPatternOffsets = readIntArray(inp)
                val stopToPatterns = readIntArray(inp)
                val stopToPatternPos = readIntArray(inp)

                // Footpaths
                val footpathOffsets = readIntArray(inp)
                val footpathToStop = readIntArray(inp)
                val footpathWalkSec = readIntArray(inp)
                val footpathDistanceMeters = readIntArray(inp)

                RaptorData(
                    serviceDateYyyymmdd = serviceDate,
                    stopCount = stopCount,
                    stopIds = stopIds,
                    stopNames = stopNames,
                    stopLats = stopLats,
                    stopLons = stopLons,
                    stopZones = stopZones,
                    stopPlatforms = stopPlatforms,
                    stopIdToIndex = stopIdToIndex,
                    patternCount = patternCount,
                    patternStopOffsets = patternStopOffsets,
                    patternStops = patternStops,
                    patternTripOffsets = patternTripOffsets,
                    patternRouteShortName = patternRouteShortName,
                    patternRouteType = patternRouteType,
                    patternRouteColor = patternRouteColor,
                    patternHeadsign = patternHeadsign,
                    tripCount = tripCount,
                    tripIds = tripIds,
                    tripShapeIds = tripShapeIds,
                    tripPatternIndex = tripPatternIndex,
                    tripWheelchairAccessible = tripWheelchairAccessible,
                    tripStopTimeOffsets = tripStopTimeOffsets,
                    arrivalTimesSec = arrivalTimesSec,
                    departureTimesSec = departureTimesSec,
                    stopTimeOnDemand = stopTimeOnDemand,
                    stopToPatternOffsets = stopToPatternOffsets,
                    stopToPatterns = stopToPatterns,
                    stopToPatternPos = stopToPatternPos,
                    footpathOffsets = footpathOffsets,
                    footpathToStop = footpathToStop,
                    footpathWalkSec = footpathWalkSec,
                    footpathDistanceMeters = footpathDistanceMeters
                )
            }
        }.getOrNull()
    }

    private fun writeIntArray(out: DataOutputStream, arr: IntArray) {
        out.writeInt(arr.size)
        for (v in arr) out.writeInt(v)
    }

    private fun readIntArray(inp: DataInputStream): IntArray {
        val len = inp.readInt()
        return IntArray(len) { inp.readInt() }
    }

    private fun writeDoubleArray(out: DataOutputStream, arr: DoubleArray) {
        out.writeInt(arr.size)
        for (v in arr) out.writeDouble(v)
    }

    private fun readDoubleArray(inp: DataInputStream): DoubleArray {
        val len = inp.readInt()
        return DoubleArray(len) { inp.readDouble() }
    }

    private fun writeByteArray(out: DataOutputStream, arr: ByteArray) {
        out.writeInt(arr.size)
        out.write(arr)
    }

    private fun readByteArray(inp: DataInputStream): ByteArray {
        val len = inp.readInt()
        val arr = ByteArray(len)
        inp.readFully(arr)
        return arr
    }

    private fun writeStringArray(out: DataOutputStream, arr: Array<String>) {
        out.writeInt(arr.size)
        for (s in arr) out.writeUTF(s)
    }

    private fun readStringArray(inp: DataInputStream): Array<String> {
        val len = inp.readInt()
        return Array(len) { inp.readUTF() }
    }

    private fun writeNullableStringArray(out: DataOutputStream, arr: Array<String?>) {
        out.writeInt(arr.size)
        for (s in arr) {
            if (s == null) {
                out.writeBoolean(false)
            } else {
                out.writeBoolean(true)
                out.writeUTF(s)
            }
        }
    }

    private fun readNullableStringArray(inp: DataInputStream): Array<String?> {
        val len = inp.readInt()
        return Array(len) {
            if (inp.readBoolean()) inp.readUTF() else null
        }
    }
}
