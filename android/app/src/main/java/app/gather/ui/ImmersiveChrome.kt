package app.gather.ui

import android.animation.ValueAnimator
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

sealed class ChromePhase {
    data object Hidden : ChromePhase()

    data class Visible(val hold: ChromeHold) : ChromePhase()
}

sealed class ChromeHold {
    data object Transient : ChromeHold()

    data object Explore : ChromeHold()

    data object Sheet : ChromeHold()

    data object Fault : ChromeHold()

    data object Seeking : ChromeHold()

    data object Persistent : ChromeHold()
}

enum class PlaybackRate(val factor: Float, val label: String) {
    X1(1f, "1×"),
    X15(1.5f, "1.5×"),
    X2(2f, "2×"),
    X25(2.5f, "2.5×"),
    X3(3f, "3×"),
}

enum class ChromePolicy {
    AutoHide,
    Persistent,
}

@Composable
fun ImmersiveChrome(
    policy: ChromePolicy,
    screenDescription: String,
    isPlaying: Boolean = false,
    hold: ChromeHold = ChromeHold.Transient,
    modifier: Modifier = Modifier,
    media: @Composable BoxScope.() -> Unit,
    captions: @Composable BoxScope.() -> Unit = {},
    footer: @Composable BoxScope.() -> Unit = {},
    bottomControls: @Composable () -> Unit = {},
    chrome: @Composable BoxScope.() -> Unit,
) {
    val accessibilityExplore = rememberAccessibilityExplore()
    val effectiveHold =
        when {
            policy == ChromePolicy.Persistent -> ChromeHold.Persistent
            accessibilityExplore -> ChromeHold.Explore
            else -> hold
        }
    var phase by
        remember(policy) { mutableStateOf<ChromePhase>(ChromePhase.Visible(effectiveHold)) }

    LaunchedEffect(effectiveHold, isPlaying, phase) {
        when {
            effectiveHold != ChromeHold.Transient -> {
                if (phase != ChromePhase.Visible(effectiveHold)) {
                    phase = ChromePhase.Visible(effectiveHold)
                }
            }
            !isPlaying -> {
                if (phase != ChromePhase.Visible(ChromeHold.Transient)) {
                    phase = ChromePhase.Visible(ChromeHold.Transient)
                }
            }
            phase is ChromePhase.Visible -> {
                if (phase != ChromePhase.Visible(ChromeHold.Transient)) {
                    phase = ChromePhase.Visible(ChromeHold.Transient)
                }
                delay(3_000)
                if (phase == ChromePhase.Visible(ChromeHold.Transient)) {
                    phase = ChromePhase.Hidden
                }
            }
        }
    }

    val reducedMotion = !ValueAnimator.areAnimatorsEnabled()
    Box(
        modifier.fillMaxSize().background(CameraBackdrop).semantics {
            contentDescription = screenDescription
        }
    ) {
        media()

        AnimatedVisibility(
            visible = phase is ChromePhase.Visible,
            modifier = Modifier.fillMaxSize(),
            enter =
                if (reducedMotion) EnterTransition.None
                else fadeIn(animationSpec = tween(durationMillis = 180)),
            exit =
                if (reducedMotion) ExitTransition.None
                else fadeOut(animationSpec = tween(durationMillis = 180)),
        ) {
            Box(
                Modifier.fillMaxSize()
                    .then(
                        if (
                            policy == ChromePolicy.AutoHide && effectiveHold == ChromeHold.Transient
                        )
                            Modifier.clickable(
                                role = Role.Button,
                                onClickLabel = "Hide player controls",
                            ) {
                                phase = ChromePhase.Hidden
                            }
                        else Modifier
                    )
            ) {
                Box(
                    Modifier.align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(160.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.76f), Color.Transparent)
                            )
                        )
                )
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(280.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f))
                            )
                        )
                )
                chrome()
            }
        }

        if (phase is ChromePhase.Hidden) {
            Box(
                Modifier.fillMaxSize()
                    .clickable(role = Role.Button, onClickLabel = "Show player controls") {
                        phase = ChromePhase.Visible(ChromeHold.Transient)
                    }
                    .semantics { contentDescription = "Show player controls" }
            )
        }

        captions()
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            AnimatedVisibility(
                visible = phase is ChromePhase.Visible,
                enter = if (reducedMotion) EnterTransition.None else fadeIn(tween(180)),
                exit = if (reducedMotion) ExitTransition.None else fadeOut(tween(180)),
            ) {
                bottomControls()
            }
            Box(Modifier.fillMaxWidth()) { footer() }
        }
    }
}

@Composable
fun ChromeIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp).semantics { contentDescription = label },
        enabled = enabled,
        content = content,
    )
}

@Composable
private fun rememberAccessibilityExplore(): Boolean {
    val manager = LocalContext.current.getSystemService(AccessibilityManager::class.java)
    fun enabled() = manager.isTouchExplorationEnabled

    var explore by remember(manager) { mutableStateOf(enabled()) }
    DisposableEffect(manager) {
        val accessibilityListener =
            AccessibilityManager.AccessibilityStateChangeListener { explore = enabled() }
        val touchListener =
            AccessibilityManager.TouchExplorationStateChangeListener { explore = enabled() }
        manager.addAccessibilityStateChangeListener(accessibilityListener)
        manager.addTouchExplorationStateChangeListener(touchListener)
        onDispose {
            manager.removeAccessibilityStateChangeListener(accessibilityListener)
            manager.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return explore
}
