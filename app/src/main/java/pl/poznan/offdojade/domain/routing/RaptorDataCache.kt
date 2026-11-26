package pl.poznan.offdojade.domain.routing

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Сверхбыстрый бинарный кэш графа [RaptorData] на диске (`cacheDir`).
 * Позволяет загружать дневной граф за ~50–120 мс вместо 4–6 секунд парсинга 350 000 строк из SQLite.
 * Автоматически инвалидируется при смене дня или обновлении файла базы SQLite (`dbFingerprint`).
 */
object RaptorDataCache {

    private const val MAGIC = 0x4F445243 // "ODRC"
    private const val FORMAT_VERSION = 1

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
