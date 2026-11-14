package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.domain.model.ExecutionStatus
import com.example.ui.animations.rememberReducedMotion
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonAlert
import com.example.ui.theme.DeepSpaceBlack
import com.example.ui.theme.ElectricCyan
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.GlassBorder
import com.example.ui.theme.GlassSurface
import com.example.ui.theme.MidnightNavy
import com.example.ui.theme.NeuralViolet

@Composable
fun SpaceBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        DeepSpaceBlack,
                        MidnightNavy,
                        DeepSpaceBlack
                    )
                )
            )
    ) {
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    accentBorder: Color = GlassBorder.copy(alpha = 0.65f),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    animateSize: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val reducedMotion = rememberReducedMotion()
    val animatedBorder by animateColorAsState(
        targetValue = accentBorder,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 220),
        label = "glass_card_border"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = GlassSurface),
        border = BorderStroke(1.dp, animatedBorder)
    ) {
        val colModifier = if (animateSize && !reducedMotion) {
            Modifier
                .padding(contentPadding)
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
        } else {
            Modifier.padding(contentPadding)
        }
        Column(modifier = colModifier, content = content)
    }
}

@Composable
fun StatusPill(
    label: String,
    color: Color,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier
) {
    val reducedMotion = rememberReducedMotion()
    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = if (reducedMotion) 0 else 200),
        label = "status_pill_color"
    )
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = animatedColor.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, animatedColor.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = animatedColor,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(Modifier.width(5.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = animatedColor
            )
        }
    }
}

fun Modifier.pressScaleFeedback(enabled: Boolean = true): Modifier = composed {
    val reducedMotion = rememberReducedMotion()
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (!enabled || reducedMotion || !pressed) 1f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label = "press_scale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(enabled) {
            if (enabled) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    }
                )
            }
        }
}

fun statusColor(status: ExecutionStatus): Color = when (status) {
    ExecutionStatus.SUCCESS -> EmeraldSuccess
    ExecutionStatus.FAILED -> CrimsonAlert
    ExecutionStatus.PERMISSION_REQUIRED -> AmberWarning
    ExecutionStatus.CONFIRMATION_REQUIRED -> NeuralViolet
    ExecutionStatus.UNSUPPORTED -> AmberWarning
    ExecutionStatus.CANCELLED -> ElectricCyan
}
