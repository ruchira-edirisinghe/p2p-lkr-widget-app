package dev.dfanso.lkrp2p.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The launch animation: the launcher icon's mark comes to life (rings open,
 * the sweep turns, the rate line draws itself and the blip lands), then the
 * name fades in and the whole overlay fades away. About 1.5 seconds; the app
 * underneath is already loading the rate.
 */
@Composable
fun SplashOverlay(onFinished: () -> Unit) {
    val draw = remember { Animatable(0f) }
    val blip = remember { Animatable(0f) }
    val title = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        launch { blip.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) }
        title.animateTo(1f, tween(350))
        delay(250)
        fade.animateTo(0f, tween(300))
        onFinished()
    }
    val sweepAngle by rememberInfiniteTransition(label = "sweep").animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sweep",
    )
    val accent = Ui.Up

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade.value }
            .background(Ui.Bg)
            // Swallow touches so nothing underneath is tapped by accident.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            .semantics { contentDescription = "RupeeRadar is starting" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Canvas(Modifier.size(168.dp)) {
                // The icon's 108-unit grid, centred and scaled so the outer ring fills the canvas.
                val u = size.minDimension / 66f
                val c = Offset(size.width / 2, size.height / 2)
                fun p(x: Float, y: Float) = Offset(c.x + (x - 54f) * u, c.y + (y - 54f) * u)

                val open = draw.value
                for ((radius, alpha) in listOf(30f to 0.30f, 20f to 0.38f, 10f to 0.46f)) {
                    drawCircle(
                        color = accent.copy(alpha = alpha * open),
                        radius = radius * u * (0.6f + 0.4f * open),
                        center = c,
                        style = Stroke(width = 2.2f * u),
                    )
                }
                rotate(sweepAngle, pivot = c) {
                    val r = 30f * u
                    drawArc(
                        brush = Brush.sweepGradient(
                            0f to Color.Transparent, 0.83f to Color.Transparent, 1f to accent.copy(alpha = 0.45f * open),
                            center = c,
                        ),
                        startAngle = -60f, sweepAngle = 60f, useCenter = true,
                        topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2),
                    )
                }

                val line = Path().apply {
                    moveTo(p(28f, 70f).x, p(28f, 70f).y)
                    lineTo(p(42f, 60f).x, p(42f, 60f).y)
                    lineTo(p(53f, 65f).x, p(53f, 65f).y)
                    lineTo(p(76f, 40f).x, p(76f, 40f).y)
                }
                val measure = PathMeasure().apply { setPath(line, false) }
                val drawn = Path()
                measure.getSegment(0f, measure.length * open, drawn, true)
                drawPath(drawn, Color.White, style = Stroke(width = 5.5f * u, cap = StrokeCap.Round, join = StrokeJoin.Round))

                val tip = p(76f, 40f)
                drawCircle(accent.copy(alpha = 0.35f * blip.value), radius = 8f * u * blip.value, center = tip)
                drawCircle(Color.White, radius = 4.5f * u * blip.value, center = tip)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = title.value
                    translationY = (1f - title.value) * 12.dp.toPx()
                },
            ) {
                Text("RupeeRadar", color = Ui.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("USDT → LKR · Sri Lanka bank transfer sell rate", color = Ui.Dim, fontSize = 13.sp)
            }
        }
    }
}
