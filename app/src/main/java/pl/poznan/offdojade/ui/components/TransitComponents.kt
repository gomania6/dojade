package pl.poznan.offdojade.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.CatchabilityInfo
import pl.poznan.offdojade.domain.model.CatchabilityStatus
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.TicketRecommendation
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import pl.poznan.offdojade.ui.localization.AppStrings
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.DelayAmber
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.LiveGreen
import pl.poznan.offdojade.ui.theme.OfflineGray
import pl.poznan.offdojade.ui.theme.ZtmBusColor
import pl.poznan.offdojade.ui.theme.ZtmTramColor
import java.util.Locale

object TimeFormatUtils {
    fun formatClock(secondsOfDay: Int): String {
        val normalized = ((secondsOfDay % 86400) + 86400) % 86400
        val hours = normalized / 3600
        val minutes = (normalized % 3600) / 60
        return String.format(Locale.US, "%02d:%02d", hours, minutes)
    }

    fun formatDelayBadge(delaySec: Int, strings: AppStrings? = null): String? {
        val mins = delaySec / 60
        return when {
            mins >= 1 -> strings?.delayLate(mins) ?: "+$mins мин опоздание"
            mins <= -1 -> strings?.delayEarly(mins) ?: "$mins мин раньше"
            else -> null
        }
    }

    /**
     * Форматирует время отправления относительно [referenceNowSec]:
     * - Если уже отъехал:
     *   - <= 60 мин назад -> «X мин назад»
     *   - > 60 мин назад -> точное время отъезда «HH:mm»
     * - Если предстоит:
     *   - 0 мин -> «сейчас»
     *   - 1..60 мин -> «через X мин»
     *   - > 60 мин -> точное время отъезда «HH:mm» (а не «через X мин»)
     */
    fun formatRelativeDeparture(
        departureTimeSec: Int,
        referenceNowSec: Int,
        strings: AppStrings
    ): String {
        val deltaSec = departureTimeSec - referenceNowSec
        return if (deltaSec < 0) {
            val minsAgo = ((-deltaSec) + 30) / 60
            when {
                minsAgo > 60 -> formatClock(departureTimeSec)
                else -> strings.departedMinutesAgo(minsAgo.coerceAtLeast(1))
            }
        } else {
            val minsUntil = deltaSec / 60
            when {
                minsUntil > 60 -> formatClock(departureTimeSec)
                minsUntil == 0 -> strings.departingNow
                else -> strings.inMinutes(minsUntil)
            }
        }
    }
}

@Composable
fun RealtimeStatusBadge(
    status: RealtimeStatus,
    modifier: Modifier = Modifier
) {
    val isLive = status is RealtimeStatus.Live
    val color = if (isLive) LiveGreen else OfflineGray
    val text = if (isLive) "Live • ZTM" else "Offline"

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = color
            )
        }
    }
}

@Composable
fun LineBadge(
    shortName: String,
    isTram: Boolean,
    colorHex: String? = null,
    textColorHex: String? = null,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val parsedColor = colorHex?.takeIf { it.isNotBlank() }?.let { hex ->
        runCatching {
            val clean = hex.trim().removePrefix("#")
            Color(android.graphics.Color.parseColor("#$clean"))
        }.getOrNull()
    }
    val bgColor = parsedColor ?: if (isTram) ZtmTramColor else ZtmBusColor
    val bgLuminance = bgColor.luminance()
    val isLightBg = bgLuminance > 0.55f

    val parsedTextColor = textColorHex?.takeIf { it.isNotBlank() }?.let { hex ->
        runCatching {
            val clean = hex.trim().removePrefix("#")
            Color(android.graphics.Color.parseColor("#$clean"))
        }.getOrNull()
    }
    val fgColor = if (parsedTextColor != null && kotlin.math.abs(parsedTextColor.luminance() - bgLuminance) > 0.25f) {
        parsedTextColor
    } else if (isLightBg) {
        Color(0xFF111816)
    } else {
        Color.White
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bgColor,
        border = if (isLightBg) BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                contentDescription = if (isTram) s.modeTram else s.modeBus,
                tint = fgColor,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = shortName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = fgColor
            )
        }
    }
}

@Composable
fun CatchabilityBadge(
    info: CatchabilityInfo,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val (color, icon, label) = when (info.status) {
        CatchabilityStatus.COMFORTABLE -> Triple(
            LiveGreen,
            Icons.Default.DirectionsWalk,
            s.catchComfortable(info.walkTimeSeconds / 60, info.marginSeconds / 60)
        )
        CatchabilityStatus.HURRY -> Triple(
            DelayAmber,
            Icons.Default.DirectionsRun,
            s.catchHurry((info.walkTimeSeconds + 30) / 60, info.secondsUntilDeparture / 60)
        )
        CatchabilityStatus.TOO_TIGHT -> Triple(
            DelayRed,
            Icons.Default.Warning,
            s.catchTooTight(info.walkDistanceMeters)
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = color
            )
        }
    }
}

@Composable
fun TicketRecommendationCard(
    ticket: TicketRecommendation,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    var showUlgowy by remember(ticket.isUlgowy) { mutableStateOf(ticket.isUlgowy) }
    val activeTicket = remember(ticket, showUlgowy) {
        if (showUlgowy == ticket.isUlgowy) {
            ticket
        } else {
            ZtmTicketCalculator.calculate(
                pureTransitMinutes = ticket.pureDurationMinutes,
                delayMinutes = ticket.delayMinutes,
                totalStopsPassed = ticket.totalStopsPassed,
                zones = ticket.zones.toSet(),
                isUlgowy = showUlgowy
            )
        }
    }

    val zonesBadge = if (activeTicket.zones.isEmpty()) {
        "${s.zonePrefix}: A"
    } else if (activeTicket.zones.size == 1) {
        "${s.zonePrefix}: ${activeTicket.zones.first()}"
    } else {
        "${s.zonesPrefix}: ${activeTicket.zones.joinToString(" + ")}"
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.ConfirmationNumber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = s.ticketRecommendationTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        modifier = Modifier.clickable { showUlgowy = !showUlgowy },
                        shape = RoundedCornerShape(6.dp),
                        color = if (showUlgowy) {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    ) {
                        Text(
                            text = if (showUlgowy) s.tariffUlgowy else s.tariffNormal,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (showUlgowy) {
                                MaterialTheme.colorScheme.onTertiaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Text(
                            text = zonesBadge,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isPekaWinner = activeTicket.cheapestChoice == CheapestTicketChoice.PEKA_TPORTMONETKA
                val isTimeWinner = activeTicket.cheapestChoice == CheapestTicketChoice.TIME_TICKET

                TicketOptionTile(
                    title = "PEKA tPortmonetka",
                    subtitle = "${activeTicket.totalStopsPassed} ${s.stopsShort}",
                    price = ZtmTicketCalculator.formatPln(activeTicket.pekaCostPln),
                    isBest = isPekaWinner,
                    bestBadge = s.cheaperBadge,
                    modifier = Modifier.weight(1f)
                )

                TicketOptionTile(
                    title = s.timeTicketLabel(activeTicket.recommendedTimeTicket.label),
                    subtitle = s.estimateMinutes(activeTicket.totalEstimatedMinutes),
                    price = ZtmTicketCalculator.formatPln(activeTicket.recommendedTimeTicketPricePln),
                    isBest = isTimeWinner,
                    bestBadge = s.cheaperBadge,
                    modifier = Modifier.weight(1f)
                )
            }

            if (activeTicket.tightFitWarning != null) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = DelayAmber.copy(alpha = 0.14f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = DelayAmber,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = activeTicket.tightFitWarning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TicketOptionTile(
    title: String,
    subtitle: String,
    price: String,
    isBest: Boolean,
    bestBadge: String,
    modifier: Modifier = Modifier
) {
    val bg = if (isBest) {
        LiveGreen.copy(alpha = 0.15f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (isBest) {
                Text(
                    text = bestBadge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = LiveGreen
                )
            }
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = price,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (isBest) LiveGreen else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
