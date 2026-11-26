package pl.poznan.offdojade.data.gtfs

/**
 * Потоковый парсер строк CSV (RFC 4180) с поддержкой кавычек, экранированных кавычек `""`
 * и обрезки UTF-8 BOM. Не загружает файлы целиком в память.
 */
object CsvLineParser {

    fun parseHeader(headerLine: String): Map<String, Int> {
        val clean = headerLine.removePrefix("\uFEFF")
        val cols = parseLine(clean)
        val map = HashMap<String, Int>(cols.size * 2)
        for (i in cols.indices) {
            map[cols[i].trim()] = i
        }
        return map
    }

    fun parseLine(line: String): List<String> {
        val result = ArrayList<String>(12)
        val current = StringBuilder(32)
        var inQuotes = false
        var i = 0
        val len = line.length

        while (i < len) {
            val ch = line[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < len && line[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    current.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> inQuotes = true
                    ',' -> {
                        result.add(current.toString())
                        current.setLength(0)
                    }
                    else -> current.append(ch)
                }
            }
            i++
        }
        result.add(current.toString())
        return result
    }

    /**
     * Парсит GTFS-время формата `HH:MM:SS` (поддерживает часы >= 24 для рейсов после полуночи)
     * в количество секунд от начала суток.
     */
    fun parseGtfsTimeToSeconds(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        val firstColon = trimmed.indexOf(':')
        if (firstColon <= 0) return null
        val secondColon = trimmed.indexOf(':', firstColon + 1)
        if (secondColon <= firstColon + 1) return null

        val hours = trimmed.substring(0, firstColon).toIntOrNull() ?: return null
        val minutes = trimmed.substring(firstColon + 1, secondColon).toIntOrNull() ?: return null
        val seconds = trimmed.substring(secondColon + 1).toIntOrNull() ?: return null
        return hours * 3600 + minutes * 60 + seconds
    }
}
