package app.gather.ui

import android.animation.ValueAnimator
import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.gather.data.Reaction
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

data class SentEmoji(val id: String, val emoji: String, val origin: Offset)

@Composable
fun ReactionCards(
    messageId: String,
    reactions: List<Reaction>,
    selfId: String?,
    positionMs: Long,
    seeking: Boolean,
) {
    val timeline = remember(messageId) { ReactionTimeline() }
    val latest by rememberUpdatedState(reactions)
    val position by rememberUpdatedState(positionMs)
    val isSeeking by rememberUpdatedState(seeking)
    var cards by remember(messageId) { mutableStateOf(emptyList<ReactionCard>()) }
    LaunchedEffect(timeline, selfId) {
        while (true) {
            cards =
                timeline.update(latest, selfId, position, SystemClock.elapsedRealtime(), isSeeking)
            delay(80)
        }
    }
    Column(
        Modifier.statusBarsPadding()
            .padding(top = 76.dp, start = 20.dp, end = 20.dp)
            .widthIn(max = 360.dp)
            .animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        cards.forEach { card ->
            key(card.reaction.id) {
                Surface(
                    color = Color(0xFFF5F3EE),
                    contentColor = Color(0xFF252925),
                    shape = RoundedCornerShape(14.dp),
                    modifier =
                        Modifier.testTag("reaction-card").semantics(mergeDescendants = true) {
                            liveRegion = LiveRegionMode.Polite
                        },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            card.reaction.userName,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(card.reaction.emoji, fontSize = 26.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun FloatingSentEmoji(event: SentEmoji, onFinished: () -> Unit) {
    val progress = remember(event.id) { Animatable(0f) }
    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    val travel = with(LocalDensity.current) { 200.dp.toPx() }
    val size = with(LocalDensity.current) { 48.dp.toPx() }
    LaunchedEffect(event.id) {
        if (reducedMotion) delay(900)
        else progress.animateTo(1f, tween(1_600, easing = LinearOutSlowInEasing))
        onFinished()
    }
    Box(
        Modifier.offset {
                IntOffset(
                    (event.origin.x - size / 2).roundToInt(),
                    (event.origin.y - size / 2).roundToInt(),
                )
            }
            .size(48.dp)
            .testTag("sent-emoji")
            .graphicsLayer {
                translationY = if (reducedMotion) -size else -travel * progress.value
                translationX =
                    if (reducedMotion) 0f
                    else -sin(progress.value * Math.PI).toFloat() * size * 0.25f
                alpha = if (reducedMotion) 1f else (1f - progress.value).coerceIn(0f, 1f)
            }
            .semantics { contentDescription = "Reaction sent ${event.emoji}" },
        contentAlignment = Alignment.Center,
    ) {
        Text(event.emoji, fontSize = 32.sp)
    }
}
