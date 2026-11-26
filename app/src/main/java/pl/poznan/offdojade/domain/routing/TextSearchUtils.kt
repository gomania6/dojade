package pl.poznan.offdojade.domain.routing

import java.text.Normalizer
import java.util.Locale

/**
 * Утилита нормализации польских/европейских диакритических символов и нечёткого поиска остановок.
 * Позволяет искать «Górczyn» по запросу «gorczyn», «Dębiec» по «debiec», «Łazarz» по «lazarz»,
 * «Źródło» по «zrodlo», «Św. Marcin» по «sw marcin», а также допускает мелкие опечатки.
 */
object TextSearchUtils {

    private val DIACRITIC_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")

    fun foldDiacritics(input: String): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder(input.length)
        for (ch in input.lowercase(Locale.ROOT)) {
            when (ch) {
                'ł' -> sb.append('l')
                'ą' -> sb.append('a')
                'ć' -> sb.append('c')
                'ę' -> sb.append('e')
                'ń' -> sb.append('n')
                'ó' -> sb.append('o')
                'ś' -> sb.append('s')
                'ź', 'ż' -> sb.append('z')
                'ß' -> sb.append("ss")
                'ä' -> sb.append('a')
                'ö' -> sb.append('o')
                'ü' -> sb.append('u')
                else -> sb.append(ch)
            }
        }
        val normalized = Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
        return DIACRITIC_REGEX.replace(normalized, "").trim()
    }

    fun tokenize(folded: String): List<String> =
        NON_ALNUM_REGEX.split(folded).filter { it.isNotEmpty() }

    /**
     * Возвращает оценку совпадения (чем больше, тем релевантнее), или `0` если совпадения нет.
     */
    fun matchScore(rawQuery: String, candidateName: String, candidateCode: String = ""): Int {
        val foldedQuery = foldDiacritics(rawQuery)
        if (foldedQuery.isEmpty()) return 1

        val foldedName = foldDiacritics(candidateName)
        val foldedCode = foldDiacritics(candidateCode)

        // 1. Точное совпадение
        if (foldedName == foldedQuery || (foldedCode.isNotEmpty() && foldedCode == foldedQuery)) {
            return 1000
        }
        // 2. Название начинается с запроса
        if (foldedName.startsWith(foldedQuery)) {
            return 850 - (foldedName.length - foldedQuery.length).coerceAtMost(200)
        }
        // 3. Прямое вхождение подстроки
        val subIdx = foldedName.indexOf(foldedQuery)
        if (subIdx >= 0) {
            val wordBoundaryBonus = if (subIdx == 0 || !foldedName[subIdx - 1].isLetterOrDigit()) 80 else 0
            return 650 + wordBoundaryBonus - subIdx.coerceAtMost(100)
        }
        if (foldedCode.isNotEmpty() && foldedCode.contains(foldedQuery)) {
            return 600
        }

        // 4. Пословное совпадение (в любом порядке + допуск 1 опечатки для слов >= 4 символов)
        val queryTokens = tokenize(foldedQuery)
        val nameTokens = tokenize(foldedName)
        if (queryTokens.isEmpty() || nameTokens.isEmpty()) return 0

        var totalTokenScore = 0
        for (qTok in queryTokens) {
            var bestTokScore = 0
            for (nTok in nameTokens) {
                val score = when {
                    nTok == qTok -> 150
                    nTok.startsWith(qTok) -> 125
                    nTok.contains(qTok) -> 95
                    qTok.length >= 4 && isWithinEditDistanceOne(qTok, nTok.take(qTok.length + 1)) -> 70
                    qTok.length >= 4 && isWithinEditDistanceOne(qTok, nTok) -> 65
                    else -> 0
                }
                if (score > bestTokScore) bestTokScore = score
            }
            if (bestTokScore == 0) return 0
            totalTokenScore += bestTokScore
        }

        return 300 + totalTokenScore / queryTokens.size
    }

    /**
     * Быстрая проверка расстояния Левенштейна <= 1 (1 вставка, удаление или замена).
     */
    private fun isWithinEditDistanceOne(a: String, b: String): Boolean {
        val lenA = a.length
        val lenB = b.length
        if (kotlin.math.abs(lenA - lenB) > 1) return false

        var i = 0
        var j = 0
        var edits = 0
        while (i < lenA && j < lenB) {
            if (a[i] == b[j]) {
                i++
                j++
            } else {
                edits++
                if (edits > 1) return false
                when {
                    lenA > lenB -> i++
                    lenB > lenA -> j++
                    else -> {
                        i++
                        j++
                    }
                }
            }
        }
        if (i < lenA || j < lenB) edits++
        return edits <= 1
    }
}
