package com.example.ui.animations

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.domain.model.ActionCategory
import com.example.domain.model.OrbState
import com.example.domain.model.PipelineStage
import com.example.ui.theme.CyanGlow
import com.example.ui.theme.DeepSpaceBlack
import com.example.ui.theme.DeepViolet
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.IndigoPulse
import com.example.ui.theme.NeuralViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlinx.coroutines.delay

@Composable
fun rememberReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

@Composable
fun CommanderAiOrb(
    orbState: OrbState,
    audioLevel: Float,
    activeCategory: ActionCategory,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val transition = updateTransition(targetState = orbState, label = "orb_state_transition")

    val primaryColor by transition.animateColor(
        label = "orb_primary_color",
        transitionSpec = { tween(320) }
    ) { state ->
        when (state) {
            OrbState.IDLE -> ElectricCyan
            OrbState.WAKE_WORD_WAITING -> IndigoPulse
            OrbState.WAKE_WORD_DETECTED -> CyanGlow
            OrbState.LISTENING -> ElectricCyan
            OrbState.PROCESSING, OrbState.THINKING -> NeuralViolet
            OrbState.EXECUTING -> CyanGlow
            OrbState.SPEAKING -> NeuralViolet
            OrbState.SUCCESS -> EmeraldSuccess
            OrbState.ERROR -> CrimsonAlert
        }
    }

    val targetScale = when (orbState) {
        OrbState.IDLE -> 1.0f
        OrbState.WAKE_WORD_WAITING -> 1.02f + audioLevel * 0.08f
        OrbState.WAKE_WORD_DETECTED -> 1.18f
        OrbState.LISTENING -> 1.05f + audioLevel * 0.16f
        OrbState.PROCESSING, OrbState.THINKING -> 1.08f
        OrbState.EXECUTING -> 1.04f
        OrbState.SPEAKING -> 1.04f + audioLevel * 0.14f
        OrbState.SUCCESS -> 1.10f
        OrbState.ERROR -> 1.0f
    }
    val animatedScale by animateFloatAsState(
        targetValue = if (reducedMotion) 1f else targetScale,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "orb_scale"
    )

    // Subtle shake animation for ERROR state
    var shakeToggle by remember { mutableStateOf(false) }
    LaunchedEffect(orbState) {
        if (orbState == OrbState.ERROR && !reducedMotion) {
            repeat(4) {
                shakeToggle = !shakeToggle
                delay(70L)
            }
            shakeToggle = false
        }
    }
    val shakeOffset by animateFloatAsState(
        targetValue = if (orbState == OrbState.ERROR && shakeToggle) 10f else 0f,
        animationSpec = tween(60),
        label = "orb_error_shake"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "orb_infinite")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reducedMotion) 24000 else 5000, easing = LinearEasing)
        ),
        label = "orbit_angle"
    )
    val counterAngle by infiniteTransition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (reducedMotion) 24000 else 3600, easing = LinearEasing)
        ),
        label = "counter_angle"
    )
    val pulsePhase by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_phase"
    )

    val checkProgress by animateFloatAsState(
        targetValue = if (orbState == OrbState.SUCCESS) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "check_progress"
    )

    val orbDescription = when (orbState) {
        OrbState.IDLE -> "AI Commander Orb idle, tap to speak"
        OrbState.WAKE_WORD_WAITING -> "Waiting for Hello Buddy wake word"
        OrbState.WAKE_WORD_DETECTED -> "Hello Buddy detected"
        OrbState.LISTENING -> "Listening to voice command"
        OrbState.PROCESSING -> "Processing command"
        OrbState.THINKING -> "Local AI thinking"
        OrbState.EXECUTING -> "Executing phone action"
        OrbState.SPEAKING -> "Speaking response"
        OrbState.SUCCESS -> "Action completed successfully"
        OrbState.ERROR -> "Action failed or requires attention"
    }

    Box(
        modifier = modifier
            .offset { IntOffset(shakeOffset.roundToInt(), 0) }
            .size(216.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("ai_orb_button")
            .semantics { contentDescription = orbDescription },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.56f * animatedScale
            val breathe = if (reducedMotion) 1f else pulsePhase

            // Outer ambient halo glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.34f),
                        NeuralViolet.copy(alpha = 0.14f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = (size.minDimension / 2f) * breathe
                ),
                radius = size.minDimension / 2f,
                center = center
            )

            // Radial sound waveform bars for LISTENING / WAKE_WORD_WAITING / SPEAKING
            if (orbState == OrbState.LISTENING || orbState == OrbState.WAKE_WORD_WAITING || orbState == OrbState.SPEAKING) {
                val barCount = 28
                val intensity = when (orbState) {
                    OrbState.WAKE_WORD_WAITING -> 0.18f + audioLevel * 0.35f
                    else -> 0.25f + audioLevel * 0.85f
                }
                for (i in 0 until barCount) {
                    val angleRad = ((i * (360f / barCount)) + orbitAngle * 0.4f) * (PI / 180.0)
                    val waveMod = 0.5f + 0.5f * sin(i * 0.9f + orbitAngle * 0.08f).toFloat()
                    val barLen = 10f + (26f * intensity * waveMod)
                    val startR = baseRadius + 10f
                    val endR = startR + barLen
                    val start = Offset(
                        center.x + (startR * cos(angleRad)).toFloat(),
                        center.y + (startR * sin(angleRad)).toFloat()
                    )
                    val end = Offset(
                        center.x + (endR * cos(angleRad)).toFloat(),
                        center.y + (endR * sin(angleRad)).toFloat()
                    )
                    drawLine(
                        color = primaryColor.copy(alpha = 0.78f),
                        start = start,
                        end = end,
                        strokeWidth = 4.5f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Rotating energy rings & neural particles for PROCESSING / THINKING / EXECUTING
            if (orbState == OrbState.PROCESSING || orbState == OrbState.THINKING || orbState == OrbState.EXECUTING || orbState == OrbState.WAKE_WORD_DETECTED) {
                val ringRadius = baseRadius + 18f
                drawArc(
                    color = ElectricCyan.copy(alpha = 0.85f),
                    startAngle = orbitAngle,
                    sweepAngle = 110f,
                    useCenter = false,
                    topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                    size = Size(ringRadius * 2f, ringRadius * 2f),
                    style = Stroke(width = 5f, cap = StrokeCap.Round)
                )
                val innerRingR = baseRadius + 8f
                drawArc(
                    color = NeuralViolet.copy(alpha = 0.9f),
                    startAngle = counterAngle,
                    sweepAngle = 140f,
                    useCenter = false,
                    topLeft = Offset(center.x - innerRingR, center.y - innerRingR),
                    size = Size(innerRingR * 2f, innerRingR * 2f),
                    style = Stroke(width = 3.5f, cap = StrokeCap.Round)
                )

                // Orbiting neural particles
                for (p in 0 until 6) {
                    val pAngle = (orbitAngle + p * 60f) * (PI / 180.0)
                    val pRadius = baseRadius + 26f + (p % 2) * 8f
                    val pos = Offset(
                        center.x + (pRadius * cos(pAngle)).toFloat(),
                        center.y + (pRadius * sin(pAngle)).toFloat()
                    )
                    drawCircle(
                        color = if (p % 2 == 0) ElectricCyan else NeuralViolet,
                        radius = 4.5f,
                        center = pos
                    )
                }
            }

            // Core glass sphere
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        primaryColor.copy(alpha = 0.88f),
                        DeepViolet.copy(alpha = 0.78f),
                        DeepSpaceBlack.copy(alpha = 0.95f)
                    ),
                    center = Offset(center.x - baseRadius * 0.25f, center.y - baseRadius * 0.25f),
                    radius = baseRadius * 1.15f
                ),
                radius = baseRadius,
                center = center
            )

            // Crisp luminous rim
            drawCircle(
                color = primaryColor.copy(alpha = 0.85f),
                radius = baseRadius,
                center = center,
                style = Stroke(width = 2.5f)
            )

            // Animated checkmark vector draw when SUCCESS
            if (orbState == OrbState.SUCCESS && checkProgress > 0f) {
                val p1 = Offset(center.x - 20f, center.y + 2f)
                val p2 = Offset(center.x - 4f, center.y + 18f)
                val p3 = Offset(center.x + 24f, center.y - 16f)
                val path = Path().apply {
                    moveTo(p1.x, p1.y)
                    val firstLeg = (checkProgress * 2f).coerceIn(0f, 1f)
                    lineTo(
                        p1.x + (p2.x - p1.x) * firstLeg,
                        p1.y + (p2.y - p1.y) * firstLeg
                    )
                    if (checkProgress > 0.5f) {
                        val secondLeg = ((checkProgress - 0.5f) * 2f).coerceIn(0f, 1f)
                        lineTo(
                            p2.x + (p3.x - p2.x) * secondLeg,
                            p2.y + (p3.y - p2.y) * secondLeg
                        )
                    }
                }
                drawPath(
                    path = path,
                    color = Color.White,
                    style = Stroke(width = 7f, cap = StrokeCap.Round)
                )
            }
        }

        // Center Icon Transition (except SUCCESS where vector checkmark draws)
        if (orbState != OrbState.SUCCESS) {
            AnimatedContent(
                targetState = orbState to activeCategory,
                transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.8f)).togetherWith(fadeOut() + scaleOut()) },
                label = "orb_center_icon"
            ) { (state, category) ->
                val icon = when (state) {
                    OrbState.LISTENING, OrbState.WAKE_WORD_WAITING, OrbState.WAKE_WORD_DETECTED -> Icons.Default.Mic
                    OrbState.SPEAKING -> Icons.Default.GraphicEq
                    OrbState.PROCESSING, OrbState.THINKING -> Icons.Default.Psychology
                    OrbState.ERROR -> Icons.Default.Close
                    OrbState.EXECUTING -> categoryIcon(category)
                    OrbState.IDLE, OrbState.SUCCESS -> Icons.Default.Mic
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(38.dp)
                )
            }
        }
    }
}

@Composable
fun categoryIcon(category: ActionCategory) = when (category) {
    ActionCategory.BROWSER -> Icons.Default.Language
    ActionCategory.SEARCH -> Icons.Default.Search
    ActionCategory.SETTINGS -> Icons.Default.Settings
    ActionCategory.CALENDAR -> Icons.Default.Event
    ActionCategory.DOCUMENT -> Icons.Default.Description
    ActionCategory.MESSAGING -> Icons.AutoMirrored.Filled.Message
    ActionCategory.VISION -> Icons.Default.Visibility
    ActionCategory.APP_LAUNCH -> Icons.Default.Apps
    ActionCategory.SYSTEM -> Icons.Default.Psychology
    ActionCategory.MEMORY -> Icons.Default.Bookmark
    ActionCategory.AUTOMATION -> Icons.Default.AutoAwesome
    ActionCategory.NOTIFICATIONS -> Icons.Default.NotificationsActive
    ActionCategory.CAMERA -> Icons.Default.CameraAlt
}

@Composable
fun AnimatedStageIndicator(
    stage: PipelineStage,
    category: ActionCategory,
    activeToolName: String,
    modifier: Modifier = Modifier
) {
    val stages = listOf(
        PipelineStage.UNDERSTANDING,
        PipelineStage.PLANNING,
        PipelineStage.SELECTING_TOOLS,
        PipelineStage.EXECUTING,
        PipelineStage.VERIFYING,
        PipelineStage.COMPLETED
    )
    val currentIndex = stages.indexOf(stage)

    AnimatedVisibility(
        visible = stage != PipelineStage.IDLE,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                AnimatedContent(
                    targetState = category,
                    label = "stage_action_icon"
                ) { cat ->
                    Icon(
                        imageVector = if (stage == PipelineStage.COMPLETED) Icons.Default.Check else categoryIcon(cat),
                        contentDescription = null,
                        tint = when (stage) {
                            PipelineStage.COMPLETED -> EmeraldSuccess
                            PipelineStage.FAILED -> CrimsonAlert
                            else -> ElectricCyan
                        },
                        modifier = Modifier.size(20.dp)
                    )
                }
                Text(
                    text = if (activeToolName.isNotBlank() && stage == PipelineStage.EXECUTING) {
                        "${stage.label}: $activeToolName"
                    } else {
                        stage.label
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = when (stage) {
                        PipelineStage.COMPLETED -> EmeraldSuccess
                        PipelineStage.FAILED -> CrimsonAlert
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
            }

            // Segmented stage progress dots (no fake percentages)
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                stages.forEachIndexed { idx, s ->
                    val isDone = currentIndex >= idx && stage != PipelineStage.FAILED
                    val isCurrent = stage == s
                    val pillColor by animateColorAsState(
                        targetValue = when {
                            stage == PipelineStage.FAILED && idx == currentIndex.coerceAtLeast(0) -> CrimsonAlert
                            isCurrent -> ElectricCyan
                            isDone -> EmeraldSuccess.copy(alpha = 0.85f)
                            else -> MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)
                        },
                        label = "stage_pill_$idx"
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(pillColor)
                    )
                }
            }
        }
    }
}
