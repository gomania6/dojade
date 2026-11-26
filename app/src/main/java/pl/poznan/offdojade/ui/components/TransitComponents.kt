package pl.poznan.offdojade.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.CatchabilityInfo
import pl.poznan.offdojade.domain.model.CatchabilityStatus
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.TicketRecommendation
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
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

    fun formatDelayBadge(delaySec: Int): String? {
        val mins = delaySec / 60
        return when {
            mins >= 1 -> "+$mins мин опоздание"
            mins <= -1 -> "$mins мин раньше"
            else -> null
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
    modifier: Modifier = Modifier
) {
    val parsedColor = colorHex?.let { hex ->
        runCatching {
            val clean = hex.removePrefix("#")
            Color(android.graphics.Color.parseColor("#$clean"))
        }.getOrNull()
    }
    val bgColor = parsedColor ?: if (isTram) ZtmTramColor else ZtmBusColor

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (isTram) Icons.Default.Tram else Icons.Default.DirectionsBus,
                contentDescription = if (isTram) "Трамвай" else "Автобус",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = shortName,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

@Composable
fun CatchabilityBadge(
    info: CatchabilityInfo,
    modifier: Modifier = Modifier
) {
    val (color, icon, label) = when (info.status) {
        CatchabilityStatus.COMFORTABLE -> Triple(
            LiveGreen,
            Icons.Default.DirectionsWalk,
            "Успеете спокойно (пешком ${info.walkTimeSeconds / 60} мин, запас +${info.marginSeconds / 60} мин)"
        )
        CatchabilityStatus.HURRY -> Triple(
            DelayAmber,
            Icons.Default.DirectionsRun,
            "Нужно ускориться (пешком ${(info.walkTimeSeconds + 30) / 60} мин, до рейса ${info.secondsUntilDeparture / 60} мин)"
        )
        CatchabilityStatus.TOO_TIGHT -> Triple(
            DelayRed,
            Icons.Default.Warning,
            "Впритык / не успеть добежать (${info.walkDistanceMeters} м)"
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.ConfirmationNumber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Рекомендация по билету",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = ticket.zonesBadgeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val isPekaWinner = ticket.cheapestChoice == CheapestTicketChoice.PEKA_TPORTMONETKA
                val isTimeWinner = ticket.cheapestChoice == CheapestTicketChoice.TIME_TICKET

                TicketOptionTile(
                    title = "PEKA tPortmonetka",
                    subtitle = "${ticket.totalStopsPassed} ост.",
                    price = ZtmTicketCalculator.formatPln(ticket.pekaCostPln),
                    isBest = isPekaWinner,
                    modifier = Modifier.weight(1f)
                )

                TicketOptionTile(
                    title = "Временной ${ticket.recommendedTimeTicket.label}",
                    subtitle = "Оценка ~${ticket.totalEstimatedMinutes} мин",
                    price = ZtmTicketCalculator.formatPln(ticket.recommendedTimeTicket.pricePln),
                    isBest = isTimeWinner,
                    modifier = Modifier.weight(1f)
                )
            }

            if (ticket.tightFitWarning != null) {
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
                            text = ticket.tightFitWarning,
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
                    text = "ВЫГОДНЕЕ",
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
