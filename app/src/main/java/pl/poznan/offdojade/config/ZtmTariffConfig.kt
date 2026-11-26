package pl.poznan.offdojade.config

/**
 * Конфигурация тарифов ZTM Poznań (временные билеты и PEKA tPortmonetka).
 * Вынесена отдельно для простого обновления при изменении цен ZTM.
 */
object ZtmTariffConfig {

    data class TimeTicketOption(
        val id: String,
        val label: String,
        val maxMinutes: Int,
        val pricePln: Double
    )

    /**
     * Временные билеты ZTM Poznań (Bilety czasowe — действуют во всех зонах A+B+C+D).
     * Отсортированы по возрастанию длительности.
     */
    val TIME_TICKETS: List<TimeTicketOption> = listOf(
        TimeTicketOption(id = "t15", label = "15 мин", maxMinutes = 15, pricePln = 4.00),
        TimeTicketOption(id = "t45", label = "45 мин", maxMinutes = 45, pricePln = 6.00),
        TimeTicketOption(id = "t90", label = "90 мин", maxMinutes = 90, pricePln = 8.00),
        TimeTicketOption(id = "t24h", label = "24 часа", maxMinutes = 24 * 60, pricePln = 15.00)
    )

    /** Рекомендуемый буфер времени (в минутах) на случай пробок и задержек */
    const val SAFETY_BUFFER_MINUTES = 5

    /**
     * Дегрессивная шкала PEKA tPortmonetka (нормальный тариф ZTM Poznań, zł за остановку):
     * - 1-я остановка: 0.72 zł
     * - 2–3-я остановки: 0.60 zł / ост.
     * - 4–6-я остановки: 0.42 zł / ост.
     * - 7–10-я остановки: 0.30 zł / ост.
     * - 11–20-я остановки: 0.24 zł / ост.
     * - 21+ остановка: 0.12 zł / ост.
     *
     * Суточный/поездочный потолок стоимости одного непрерывного проезда (по правилам ZTM)
     * ограничен максимальной ставкой (опционально можно скорректировать).
     */
    data class PekaStopTier(
        val upToStopInclusive: Int,
        val pricePerStopPln: Double
    )

    val PEKA_STOP_TIERS: List<PekaStopTier> = listOf(
        PekaStopTier(upToStopInclusive = 1, pricePerStopPln = 0.72),
        PekaStopTier(upToStopInclusive = 3, pricePerStopPln = 0.60),
        PekaStopTier(upToStopInclusive = 6, pricePerStopPln = 0.42),
        PekaStopTier(upToStopInclusive = 10, pricePerStopPln = 0.30),
        PekaStopTier(upToStopInclusive = 20, pricePerStopPln = 0.24),
        PekaStopTier(upToStopInclusive = Int.MAX_VALUE, pricePerStopPln = 0.12)
    )

    /**
     * Вычисляет стоимость поездки по PEKA tPortmonetka для заданного количества остановок.
     */
    fun calculatePekaCostPln(totalStops: Int): Double {
        if (totalStops <= 0) return 0.0
        var cost = 0.0
        for (stopIndex in 1..totalStops) {
            val tier = PEKA_STOP_TIERS.first { stopIndex <= it.upToStopInclusive }
            cost += tier.pricePerStopPln
        }
        return kotlin.math.round(cost * 100.0) / 100.0
    }
}
