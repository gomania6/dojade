package pl.poznan.offdojade.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import pl.poznan.offdojade.data.realtime.RealtimeStatus
import pl.poznan.offdojade.domain.model.CatchabilityInfo
import pl.poznan.offdojade.domain.model.CatchabilityStatus
import pl.poznan.offdojade.domain.model.CheapestTicketChoice
import pl.poznan.offdojade.domain.model.TicketRecommendation
import pl.poznan.offdojade.domain.model.TransportMode
import pl.poznan.offdojade.domain.tariff.ZtmTicketCalculator
import pl.poznan.offdojade.ui.localization.AppStrings
import pl.poznan.offdojade.ui.localization.LocalStrings
import pl.poznan.offdojade.ui.theme.DelayAmber
import pl.poznan.offdojade.ui.theme.DelayRed
import pl.poznan.offdojade.ui.theme.LiveGreen
import pl.poznan.offdojade.ui.theme.NightBusOnWhite
import pl.poznan.offdojade.ui.theme.NightBusWhite
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

    fun formatDuration(totalMinutes: Int, strings: AppStrings): String {
        val mins = totalMinutes.coerceAtLeast(1)
        return if (mins >= 60) {
            val h = mins / 60
            val m = mins % 60
            if (m == 0) "$h ${strings.hourShort}" else "$h ${strings.hourShort} $m ${strings.minShort}"
        } else {
            "$mins ${strings.minShort}"
        }
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

/**
 * Анимированный индикатор живого сигнала транспорта:
 * плавно появляется при получении GTFS-RT и мягко пульсирует радиоволной.
 */
@Composable
fun AnimatedLiveSignal(
    isDelayed: Boolean,
    modifier: Modifier = Modifier
) {
    val tint = if (isDelayed) DelayRed else LiveGreen
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }

    val enterAlpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "liveSignalEnterAlpha"
    )
    val enterScale by animateFloatAsState(
        targetValue = if (appeared) 1f else 0.55f,
        animationSpec = tween(durationMillis = 380, easing = FastOutSlowInEasing),
        label = "liveSignalEnterScale"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "liveSignalPulse")
    val iconPulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "liveIconPulse"
    )
    val waveProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "liveWaveProgress"
    )

    Box(
        modifier = modifier
            .size(16.dp)
            .alpha(enterAlpha)
            .scale(enterScale),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val radius = (size.minDimension / 2f) * (0.45f + 0.55f * waveProgress)
            drawCircle(
                color = tint.copy(alpha = (1f - waveProgress) * 0.42f),
                radius = radius,
                style = Stroke(width = 1.4.dp.toPx())
            )
        }
        Icon(
            imageVector = Icons.Default.Sensors,
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(13.dp)
                .scale(iconPulse)
        )
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

    val dotAlpha = if (isLive) {
        val infinite = rememberInfiniteTransition(label = "statusDotPulse")
        val a by infinite.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1100, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dotAlpha"
        )
        a
    } else {
        1f
    }

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
                    .alpha(dotAlpha)
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
    isRail: Boolean = false,
    modifier: Modifier = Modifier
) {
    val s = LocalStrings.current
    val isNightBus = TransportMode.isNightLine(shortName)
    val parsedColor = if (isNightBus) {
        NightBusWhite
    } else {
        colorHex?.takeIf { it.isNotBlank() }?.let { hex ->
            runCatching {
                val clean = hex.trim().removePrefix("#")
                Color(android.graphics.Color.parseColor("#$clean"))
            }.getOrNull()
        }
    }
    val bgColor = parsedColor ?: when {
        isRail -> Color(0xFFD32F2F)
        isTram -> ZtmTramColor
        else -> ZtmBusColor
    }
    val bgLuminance = bgColor.luminance()
    val isLightBg = bgLuminance > 0.55f

    val parsedTextColor = if (isNightBus) {
        NightBusOnWhite
    } else {
        textColorHex?.takeIf { it.isNotBlank() }?.let { hex ->
            runCatching {
                val clean = hex.trim().removePrefix("#")
                Color(android.graphics.Color.parseColor("#$clean"))
            }.getOrNull()
        }
    }
    val fgColor = if (parsedTextColor != null && kotlin.math.abs(parsedTextColor.luminance() - bgLuminance) > 0.25f) {
        parsedTextColor
    } else if (isLightBg) {
        NightBusOnWhite
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
                imageVector = when {
                    isRail -> Icons.Default.Train
                    isTram -> Icons.Default.Tram
                    else -> Icons.Default.DirectionsBus
                },
                contentDescription = when {
                    isRail -> s.modeRail
                    isTram -> s.modeTram
                    else -> s.modeBus
                },
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val okColor = if (isDark) LiveGreen else Color(0xFF1B7A4B)
    val warnColor = if (isDark) DelayAmber else Color(0xFF9A5B00)
    val errColor = if (isDark) DelayRed else Color(0xFFB3261E)
    val (color, icon, label) = when (info.status) {
        CatchabilityStatus.COMFORTABLE -> Triple(
            okColor,
            Icons.Default.DirectionsWalk,
            s.catchComfortable(info.walkTimeSeconds / 60, info.marginSeconds / 60)
        )
        CatchabilityStatus.HURRY -> Triple(
            warnColor,
            Icons.Default.DirectionsRun,
            s.catchHurry((info.walkTimeSeconds + 30) / 60, info.secondsUntilDeparture / 60)
        )
        CatchabilityStatus.TOO_TIGHT -> Triple(
            errColor,
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
                isUlgowy = showUlgowy,
                pekaStopGroups = ticket.pekaStopGroups,
                hasNonZtmPksLeg = ticket.hasNonZtmPksLeg,
                hasRailLeg = ticket.hasRailLeg
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
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ConfirmationNumber,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(17.dp)
                )
                Text(
                    text = s.ticketRecommendationTitle,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = { showUlgowy = !showUlgowy },
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
                        maxLines = 1,
                        color = if (showUlgowy) {
                            MaterialTheme.colorScheme.onTertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                        maxLines = 1,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (activeTicket.hasRailLeg) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Train,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = s.railTicketNotice,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Max),
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
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )

                    val localizedTicketDuration = s.ticketDurationLabel(
                        activeTicket.recommendedTimeTicket.id,
                        activeTicket.recommendedTimeTicket.maxMinutes
                    )
                    TicketOptionTile(
                        title = s.timeTicketLabel(localizedTicketDuration),
                        subtitle = s.estimateMinutes(activeTicket.totalEstimatedMinutes),
                        price = ZtmTicketCalculator.formatPln(activeTicket.recommendedTimeTicketPricePln),
                        isBest = isTimeWinner,
                        bestBadge = s.cheaperBadge,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }

            if (!activeTicket.hasRailLeg && activeTicket.tightFitWarning != null) {
                val pureTicket = pl.poznan.offdojade.config.ZtmTariffConfig.TIME_TICKETS
                    .firstOrNull { activeTicket.pureDurationMinutes <= it.maxMinutes }
                    ?: pl.poznan.offdojade.config.ZtmTariffConfig.TIME_TICKETS.last()
                val localizedWarning = s.tightFitWarningText(
                    activeTicket.pureDurationMinutes,
                    activeTicket.delayMinutes,
                    activeTicket.bufferMinutes,
                    activeTicket.totalEstimatedMinutes,
                    s.ticketDurationLabel(pureTicket.id, pureTicket.maxMinutes),
                    s.ticketDurationLabel(
                        activeTicket.recommendedTimeTicket.id,
                        activeTicket.recommendedTimeTicket.maxMinutes
                    ),
                    ZtmTicketCalculator.formatPln(activeTicket.recommendedTimeTicketPricePln)
                )
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
                            text = localizedWarning,
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
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val bestAccent = if (isDark) LiveGreen else MaterialTheme.colorScheme.primary
    val bg = if (isBest) {
        bestAccent.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(10.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                if (isBest) {
                    Text(
                        text = bestBadge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = bestAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = price,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                color = if (isBest) bestAccent else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
