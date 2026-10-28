package app.keeper.car.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

object K {
    val Bg = Color(0xFF0B1220)
    val Surface = Color(0xFF131C2E)
    val Surface2 = Color(0xFF1B2640)
    val Line = Color(0xFF26324D)
    val Text = Color(0xFFE8EEF9)
    val Sub = Color(0xFF8E9BB5)
    val Blue = Color(0xFF4F8BFF)
    val Green = Color(0xFF34D399)
    val Amber = Color(0xFFFBBF24)
    val Red = Color(0xFFF05252)
    val Purple = Color(0xFFA78BFA)
}

@Composable
fun KeeperTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = K.Blue, background = K.Bg, surface = K.Surface, onSurface = K.Text, onBackground = K.Text,
            error = K.Red,
        ),
        content = content,
    )
}

/** 방패 + 자물쇠 아이콘 (외부 아이콘 라이브러리 없이 직접 그림). */
@Composable
fun ShieldIcon(color: Color, size: Dp, locked: Boolean = true, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val shield = Path().apply {
            moveTo(w * .5f, h * .04f); lineTo(w * .92f, h * .18f); lineTo(w * .92f, h * .48f)
            cubicTo(w * .92f, h * .74f, w * .74f, h * .9f, w * .5f, h * .98f)
            cubicTo(w * .26f, h * .9f, w * .08f, h * .74f, w * .08f, h * .48f)
            lineTo(w * .08f, h * .18f); close()
        }
        drawPath(shield, color.copy(alpha = .18f))
        drawPath(shield, color, style = Stroke(width = w * .05f))
        val bodyTop = h * .48f
        drawRoundRect(color, topLeft = Offset(w * .32f, bodyTop), size = Size(w * .36f, h * .28f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(w * .04f))
        val shackleShift = if (locked) 0f else w * .12f
        drawArc(
            color, startAngle = 180f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * .38f + shackleShift, h * .30f), size = Size(w * .24f, h * .32f),
            style = Stroke(width = w * .05f, cap = StrokeCap.Round),
        )
        if (!locked) drawLine(color, Offset(w * .62f + shackleShift - w * .0f, h * .46f), Offset(w * .62f + shackleShift, h * .40f), strokeWidth = w * .05f)
    }
}

/** 지문 아이콘 (동심 호). */
@Composable
fun FingerprintIcon(color: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height * .55f)
        val stroke = this.size.width * .045f
        listOf(.12f, .22f, .32f, .42f).forEachIndexed { i, r ->
            val rr = this.size.width * r
            drawArc(
                color, startAngle = 200f - i * 6, sweepAngle = 140f + i * 12, useCenter = false,
                topLeft = Offset(c.x - rr, c.y - rr), size = Size(rr * 2, rr * 2),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        drawLine(color, Offset(c.x, c.y - this.size.width * .05f), Offset(c.x, c.y + this.size.width * .2f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}
