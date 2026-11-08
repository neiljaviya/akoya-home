package com.akoya.home.ui.screensaver

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.akoya.home.data.Festival
import com.akoya.home.data.FestivalArt
import com.akoya.home.ui.theme.AkoyaPalette
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/** Seconds since first frame; drives every procedural animation. */
@Composable
fun rememberSeconds(): State<Float> = produceState(0f) {
    val start = withFrameNanos { it }
    while (true) {
        withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
}

private class Particle(val x: Float, val y: Float, val size: Float, val speed: Float, val phase: Float, val hue: Int)

private fun particles(n: Int, seed: Int) = Random(seed).let { r ->
    List(n) { Particle(r.nextFloat(), r.nextFloat(), r.nextFloat(), 0.5f + r.nextFloat(), r.nextFloat() * 6.28f, r.nextInt(1000)) }
}

private fun frac(v: Float) = v - floor(v)

/** Full-screen animated art for a festival or season. */
@Composable
fun FestivalScene(festival: Festival, palette: AkoyaPalette, modifier: Modifier = Modifier) {
    val t by rememberSeconds()
    val ps = remember(festival.id) { particles(90, festival.id.hashCode()) }
    Canvas(modifier.fillMaxSize()) {
        when (festival.art) {
            FestivalArt.DIWALI -> diwali(t, ps)
            FestivalArt.HOLI -> holi(t, ps)
            FestivalArt.KITES -> kites(t, ps)
            FestivalArt.GARBA -> garba(t)
            FestivalArt.RAKHI -> rakhi(t, ps)
            FestivalArt.TORAN -> toran(t, ps)
            FestivalArt.PEACOCK -> peacock(t)
            FestivalArt.CHRISTMAS -> christmas(t, ps)
            FestivalArt.FIREWORKS -> fireworks(t)
            FestivalArt.SNOW -> season(palette, t, ps, Color(0xFFFFFFFF), SeasonFx.SNOW)
            FestivalArt.PETALS -> season(palette, t, ps, Color(0xFFF2B8C9), SeasonFx.PETALS)
            FestivalArt.RAIN -> season(palette, t, ps, Color(0xFFBFD4DE), SeasonFx.RAIN)
            FestivalArt.LEAVES -> season(palette, t, ps, Color(0xFFD9822B), SeasonFx.LEAVES)
        }
    }
}

// ---------------------------------------------------------------- Diwali: diyas, rangoli, sparks

private fun DrawScope.diwali(t: Float, ps: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF231309), Color(0xFF120A05))))
    val warm = Offset(size.width * 0.5f, size.height * 0.85f)
    drawCircle(Brush.radialGradient(listOf(Color(0x40E8A33D), Color.Transparent), warm, size.width * 0.6f), size.width * 0.6f, warm)
    rangoli(Offset(size.width * 0.5f, size.height * 0.40f), size.height * 0.22f, t)
    // rising golden sparks
    ps.forEach { p ->
        val y = size.height * (1f - frac(p.y + t * 0.03f * p.speed))
        val x = size.width * p.x + sin(t * p.speed + p.phase) * 14f
        val tw = 0.4f + 0.6f * abs(sin(t * 3f * p.speed + p.phase))
        drawCircle(Color(0xFFFFD27A).copy(alpha = 0.55f * tw), radius = 1.5f + p.size * 2.5f, center = Offset(x, y))
    }
    val n = 7
    for (i in 0 until n) {
        val cx = size.width * (0.14f + i * 0.72f / (n - 1))
        diya(Offset(cx, size.height * 0.84f), size.height * 0.05f, t, i)
    }
}

private fun DrawScope.diya(c: Offset, r: Float, t: Float, i: Int) {
    val flicker = 1f + 0.10f * sin(t * 9f + i * 1.7f) + 0.05f * sin(t * 23f + i)
    // glow
    drawCircle(Brush.radialGradient(listOf(Color(0x66FFB347), Color.Transparent), Offset(c.x, c.y - r * 1.2f), r * 4f * flicker), r * 4f * flicker, Offset(c.x, c.y - r * 1.2f))
    // bowl
    val bowl = Path().apply {
        moveTo(c.x - r * 1.6f, c.y - r * 0.2f)
        cubicTo(c.x - r * 1.3f, c.y + r * 1.1f, c.x + r * 1.3f, c.y + r * 1.1f, c.x + r * 1.6f, c.y - r * 0.2f)
        cubicTo(c.x + r * 0.8f, c.y + r * 0.15f, c.x - r * 0.8f, c.y + r * 0.15f, c.x - r * 1.6f, c.y - r * 0.2f)
        close()
    }
    drawPath(bowl, Brush.verticalGradient(listOf(Color(0xFFC8693A), Color(0xFF7A3416)), c.y - r, c.y + r))
    drawPath(bowl, Color(0xFFF2B36B), style = Stroke(width = r * 0.08f))
    // flame
    val h = r * 1.5f * flicker
    val sway = sin(t * 5f + i) * r * 0.12f
    val flame = Path().apply {
        moveTo(c.x, c.y - r * 0.1f)
        cubicTo(c.x - r * 0.55f, c.y - r * 0.4f, c.x - r * 0.25f + sway, c.y - h * 0.8f, c.x + sway, c.y - h - r * 0.1f)
        cubicTo(c.x + r * 0.25f + sway, c.y - h * 0.8f, c.x + r * 0.55f, c.y - r * 0.4f, c.x, c.y - r * 0.1f)
        close()
    }
    drawPath(flame, Brush.verticalGradient(listOf(Color(0xFFFFF4C2), Color(0xFFFFB02E), Color(0xFFE5501B)), c.y - h, c.y))
}

private fun DrawScope.rangoli(c: Offset, R: Float, t: Float) {
    val colors = listOf(Color(0xFFE0457B), Color(0xFFF28C28), Color(0xFFF6C945), Color(0xFF2BB3A3), Color(0xFF7B4FB3))
    rotate(t * 3f, c) {
        for (ring in 0 until 4) {
            val count = 8 * (ring + 1)
            val rr = R * (0.25f + ring * 0.22f)
            val petal = Size(R * 0.10f, R * (0.22f - ring * 0.02f))
            for (k in 0 until count) {
                val a = 360f / count * k + ring * 11f
                rotate(a, c) {
                    drawOval(
                        colors[(ring + k) % colors.size].copy(alpha = 0.85f),
                        topLeft = Offset(c.x - petal.width / 2, c.y - rr - petal.height / 2),
                        size = petal,
                    )
                }
            }
        }
        drawCircle(Color(0xFFF6C945), R * 0.12f, c)
        drawCircle(Color(0xFFE0457B), R * 0.06f, c)
        drawCircle(Color(0xFFF2B36B).copy(alpha = 0.5f), R * 1.02f, c, style = Stroke(2f))
        for (k in 0 until 48) {
            val a = k * 2 * PI.toFloat() / 48
            drawCircle(Color(0xFFFFE6A8).copy(alpha = 0.8f), 3f, Offset(c.x + cos(a) * R * 1.1f, c.y + sin(a) * R * 1.1f))
        }
    }
}

// ---------------------------------------------------------------- Holi: drifting colour clouds

private fun DrawScope.holi(t: Float, ps: List<Particle>) {
    drawRect(Color(0xFFF7F2EA))
    val colors = listOf(Color(0xFFF06BA8), Color(0xFFFFC93C), Color(0xFF3CC4A0), Color(0xFF5B8DEF), Color(0xFFB66BF0), Color(0xFFFF7A45))
    for (i in 0 until 9) {
        val cx = size.width * (0.5f + 0.42f * sin(t * 0.05f * (1 + i % 3) + i * 1.3f))
        val cy = size.height * (0.5f + 0.38f * cos(t * 0.04f * (1 + i % 2) + i * 2.1f))
        val r = size.minDimension * (0.28f + 0.06f * sin(t * 0.3f + i))
        drawCircle(Brush.radialGradient(listOf(colors[i % colors.size].copy(alpha = 0.55f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }
    ps.forEach { p ->
        val x = size.width * frac(p.x + t * 0.01f * p.speed)
        val y = size.height * frac(p.y + sin(t * 0.2f + p.phase) * 0.02f)
        drawCircle(colors[p.hue % colors.size].copy(alpha = 0.6f), 2f + p.size * 4f, Offset(x, y))
    }
}

// ---------------------------------------------------------------- Uttarayan: kites

private fun DrawScope.kites(t: Float, ps: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF8FC9EE), Color(0xFFCFE6F2), Color(0xFFF6D7B8))))
    // soft clouds
    for (i in 0 until 5) {
        val cx = size.width * frac(0.1f + i * 0.23f + t * 0.004f)
        val cy = size.height * (0.15f + (i % 3) * 0.12f)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f), Color.Transparent), Offset(cx, cy), size.width * 0.12f), size.width * 0.12f, Offset(cx, cy))
    }
    val colors = listOf(Color(0xFFE63946), Color(0xFFF4A261), Color(0xFF2A9D8F), Color(0xFF6A4C93), Color(0xFFFFD166), Color(0xFF118AB2), Color(0xFFEF476F))
    for (i in 0 until 7) {
        val bx = size.width * (0.12f + i * 0.13f)
        val by = size.height * (0.22f + (i % 3) * 0.14f)
        val x = bx + sin(t * 0.6f + i) * 30f
        val y = by + cos(t * 0.8f + i * 1.3f) * 22f
        val s = size.height * (0.07f + (i % 2) * 0.02f)
        val tilt = sin(t * 0.9f + i) * 14f
        // string
        val string = Path().apply {
            moveTo(x, y + s)
            cubicTo(x + 40f, y + s * 3f, x - 60f, size.height * 0.8f, size.width * 0.05f + i * 20f, size.height * 1.02f)
        }
        drawPath(string, Color.White.copy(alpha = 0.6f), style = Stroke(1.2f))
        rotate(45f + tilt, Offset(x, y)) {
            drawRect(colors[i], topLeft = Offset(x - s * 0.7f, y - s * 0.7f), size = Size(s * 1.4f, s * 1.4f))
            drawRect(colors[(i + 3) % colors.size], topLeft = Offset(x - s * 0.7f, y - s * 0.7f), size = Size(s * 0.7f, s * 0.7f))
        }
        drawLine(Color.Black.copy(alpha = 0.25f), Offset(x, y - s), Offset(x, y + s), 2f)
        drawLine(Color.Black.copy(alpha = 0.25f), Offset(x - s, y), Offset(x + s, y), 2f)
        // tail
        drawLine(colors[(i + 1) % colors.size], Offset(x, y + s), Offset(x + sin(t * 3f + i) * 10f, y + s * 1.6f), 4f, cap = StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- Navratri: garba rings

private fun DrawScope.garba(t: Float) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF3A1238), Color(0xFF14060F)), Offset(size.width / 2, size.height / 2), size.maxDimension * 0.7f))
    val nine = listOf(0xFFF2C14E, 0xFF2E7D32, 0xFF9E9E9E, 0xFFFF7043, 0xFFFFFFFF, 0xFFE53935, 0xFF1565C0, 0xFFEC407A, 0xFF7E57C2).map { Color(it) }
    val c = Offset(size.width / 2, size.height * 0.52f)
    for (ring in 0 until 5) {
        val r = size.minDimension * (0.10f + ring * 0.085f)
        val n = 9 + ring * 6
        val dir = if (ring % 2 == 0) 1f else -1f
        for (k in 0 until n) {
            val a = (k * 2 * PI / n).toFloat() + dir * t * (0.25f - ring * 0.03f)
            val p = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
            val col = nine[(k + ring) % 9]
            drawCircle(col.copy(alpha = 0.9f), 4f + ring * 1.2f, p)
            if (ring == 4 && k % 2 == 0) {
                // dandiya pair
                val l = 26f
                drawLine(col, Offset(p.x - l, p.y - l), Offset(p.x + l, p.y + l), 4f, cap = StrokeCap.Round)
                drawLine(col, Offset(p.x + l, p.y - l), Offset(p.x - l, p.y + l), 4f, cap = StrokeCap.Round)
            }
        }
    }
    val glow = 1f + 0.08f * sin(t * 2f)
    drawCircle(Brush.radialGradient(listOf(Color(0xCCFFC861), Color.Transparent), c, size.minDimension * 0.09f * glow), size.minDimension * 0.09f * glow, c)
    drawCircle(Color(0xFFB4562C), size.minDimension * 0.045f, c)
    for (k in 0 until 8) {
        val a = (k * 2 * PI / 8).toFloat()
        drawCircle(Color(0xFFFFE08A), 3f, Offset(c.x + cos(a) * size.minDimension * 0.028f, c.y + sin(a) * size.minDimension * 0.028f))
    }
}

// ---------------------------------------------------------------- Raksha Bandhan: thread + rakhi

private fun DrawScope.rakhi(t: Float, ps: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFBE3E1), Color(0xFFF6D2C8))))
    val y0 = size.height * 0.5f
    val thread = Path().apply {
        moveTo(0f, y0)
        var x = 0f
        while (x <= size.width) {
            lineTo(x, y0 + sin(x / 90f + t * 0.8f) * 10f)
            x += 12f
        }
    }
    drawPath(thread, Color(0xFFD7263D), style = Stroke(6f))
    drawPath(thread, Color(0xFFF6C945), style = Stroke(2f))
    val c = Offset(size.width / 2, y0 + sin(size.width / 2 / 90f + t * 0.8f) * 10f)
    val R = size.minDimension * 0.13f
    rotate(t * 6f, c) {
        for (k in 0 until 12) rotate(k * 30f, c) {
            drawOval(Color(0xFFE0457B), Offset(c.x - R * 0.16f, c.y - R), Size(R * 0.32f, R * 0.6f))
        }
        for (k in 0 until 8) rotate(k * 45f + 22f, c) {
            drawOval(Color(0xFFF6C945), Offset(c.x - R * 0.12f, c.y - R * 0.62f), Size(R * 0.24f, R * 0.4f))
        }
    }
    drawCircle(Color(0xFF7B4FB3), R * 0.24f, c)
    drawCircle(Color.White.copy(alpha = 0.8f), R * 0.08f, Offset(c.x - R * 0.06f, c.y - R * 0.06f))
    ps.take(40).forEach { p ->
        val y = size.height * frac(p.y + t * 0.02f * p.speed)
        drawCircle(Color(0xFFF6C945).copy(alpha = 0.5f), 2f + p.size * 3f, Offset(size.width * p.x, y))
    }
}

// ---------------------------------------------------------------- Ganesh Chaturthi: marigold toran

private fun DrawScope.toran(t: Float, ps: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFF6EBDD), Color(0xFFEBD8BE))))
    val swags = 4
    val sw = size.width / swags
    for (s in 0 until swags) {
        val x0 = s * sw
        val sag = size.height * 0.16f + sin(t * 0.7f + s) * 6f
        for (k in 0..14) {
            val u = k / 14f
            val x = x0 + u * sw
            val y = size.height * 0.04f + sag * 4f * u * (1 - u)
            val col = if (k % 2 == 0) Color(0xFFF59E0B) else Color(0xFFFBBF24)
            drawCircle(col, size.height * 0.022f, Offset(x, y))
            drawCircle(Color(0xFFC2410C).copy(alpha = 0.5f), size.height * 0.008f, Offset(x, y))
        }
        // mango leaves hanging
        for (k in 1..3) {
            val u = k / 4f
            val x = x0 + u * sw
            val y = size.height * 0.04f + sag * 4f * u * (1 - u)
            rotate(sin(t + k + s) * 6f, Offset(x, y)) {
                drawOval(Color(0xFF3F7D20), Offset(x - 10f, y + 8f), Size(20f, size.height * 0.08f))
            }
        }
    }
    ps.forEach { p ->
        val y = size.height * frac(p.y + t * 0.025f * p.speed)
        val x = size.width * p.x + sin(t + p.phase) * 20f
        rotate(t * 40f * p.speed + p.phase * 50f, Offset(x, y)) {
            drawOval((if (p.hue % 2 == 0) Color(0xFFF59E0B) else Color(0xFFDC2626)).copy(alpha = 0.7f), Offset(x - 5f, y - 3f), Size(10f, 6f))
        }
    }
}

// ---------------------------------------------------------------- Janmashtami: peacock feathers

private fun DrawScope.peacock(t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0B2A33), Color(0xFF061419))))
    val feathers = listOf(0.18f to 0.55f, 0.36f to 0.45f, 0.5f to 0.6f, 0.66f to 0.42f, 0.84f to 0.55f)
    feathers.forEachIndexed { i, (fx, fy) ->
        val base = Offset(size.width * fx, size.height * 1.05f)
        val tip = Offset(size.width * fx + sin(t * 0.5f + i) * 20f, size.height * fy)
        val angle = Math.toDegrees(kotlin.math.atan2((tip.x - base.x).toDouble(), (base.y - tip.y).toDouble())).toFloat()
        drawLine(Color(0xFF9AB87A), base, tip, 3f)
        rotate(angle, tip) {
            // barbs
            for (k in 0 until 40) {
                val yy = tip.y + k * 9f
                drawLine(Color(0xFF6FA35A).copy(alpha = 0.5f), Offset(tip.x, yy), Offset(tip.x - 50f + k * 0.6f, yy - 26f), 1.2f)
                drawLine(Color(0xFF6FA35A).copy(alpha = 0.5f), Offset(tip.x, yy), Offset(tip.x + 50f - k * 0.6f, yy - 26f), 1.2f)
            }
            val e = size.height * 0.07f
            drawOval(Color(0xFF3E8E41), Offset(tip.x - e * 0.9f, tip.y - e * 1.2f), Size(e * 1.8f, e * 2.4f))
            drawOval(Color(0xFFC9A227), Offset(tip.x - e * 0.7f, tip.y - e * 0.95f), Size(e * 1.4f, e * 1.9f))
            drawOval(Color(0xFF1FA0A8), Offset(tip.x - e * 0.5f, tip.y - e * 0.7f), Size(e * 1.0f, e * 1.4f))
            drawOval(Color(0xFF0D2C6B), Offset(tip.x - e * 0.28f, tip.y - e * 0.45f), Size(e * 0.56f, e * 0.8f))
        }
    }
}

// ---------------------------------------------------------------- Christmas: snow, star, lights

private fun DrawScope.christmas(t: Float, ps: List<Particle>) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0E1A2B), Color(0xFF14243A))))
    val bulbs = 24
    for (k in 0 until bulbs) {
        val x = size.width * k / (bulbs - 1)
        val y = size.height * 0.06f + sin(k * 0.8f) * 10f + 18f * (1 - abs((k - bulbs / 2f) / (bulbs / 2f)))
        val col = listOf(Color(0xFFE53935), Color(0xFFFFD54F), Color(0xFF43A047), Color(0xFF42A5F5))[k % 4]
        val on = 0.5f + 0.5f * sin(t * 2f + k)
        drawCircle(col.copy(alpha = 0.25f * on), 22f, Offset(x, y))
        drawCircle(col.copy(alpha = 0.6f + 0.4f * on), 7f, Offset(x, y))
    }
    star(Offset(size.width / 2, size.height * 0.38f), size.height * 0.09f, t)
    ps.forEach { p ->
        val y = size.height * frac(p.y + t * 0.03f * p.speed)
        val x = size.width * frac(p.x + sin(t * 0.5f + p.phase) * 0.01f)
        drawCircle(Color.White.copy(alpha = 0.8f), 1.5f + p.size * 3f, Offset(x, y))
    }
}

private fun DrawScope.star(c: Offset, R: Float, t: Float) {
    val glow = 1f + 0.1f * sin(t * 1.5f)
    drawCircle(Brush.radialGradient(listOf(Color(0x88FFE08A), Color.Transparent), c, R * 3f * glow), R * 3f * glow, c)
    val p = Path()
    for (i in 0 until 10) {
        val a = (-PI / 2 + i * PI / 5).toFloat()
        val r = if (i % 2 == 0) R else R * 0.42f
        val pt = Offset(c.x + cos(a) * r, c.y + sin(a) * r)
        if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    p.close()
    drawPath(p, Color(0xFFFFE08A))
}

// ---------------------------------------------------------------- Fireworks

private fun DrawScope.fireworks(t: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF070B1A), Color(0xFF151A33))))
    val colors = listOf(Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF06D6A0), Color(0xFF118AB2), Color(0xFFF78C6B), Color(0xFFE8D7A6))
    val period = 2.6f
    for (b in 0 until 5) {
        val cycle = floor((t + b * 0.7f) / period).toInt()
        val local = (t + b * 0.7f) - cycle * period
        val r = Random(cycle * 31 + b * 7)
        val c = Offset(size.width * (0.15f + r.nextFloat() * 0.7f), size.height * (0.15f + r.nextFloat() * 0.4f))
        val col = colors[r.nextInt(colors.size)]
        val n = 36
        val speed = size.minDimension * 0.16f
        val fade = (1f - local / period).coerceIn(0f, 1f)
        for (k in 0 until n) {
            val a = (k * 2 * PI / n).toFloat()
            val d = speed * (1f - (1f - local / period) * (1f - local / period))
            val p = Offset(c.x + cos(a) * d, c.y + sin(a) * d + local * local * 12f)
            drawCircle(col.copy(alpha = fade), 3f, p)
            drawLine(col.copy(alpha = fade * 0.4f), Offset(c.x + cos(a) * d * 0.7f, c.y + sin(a) * d * 0.7f + local * local * 9f), p, 2f)
        }
    }
}

// ---------------------------------------------------------------- Seasons (subtle, on the theme background)

private enum class SeasonFx { SNOW, PETALS, RAIN, LEAVES }

private fun DrawScope.season(p: AkoyaPalette, t: Float, ps: List<Particle>, col: Color, fx: SeasonFx) {
    drawRect(Brush.verticalGradient(listOf(p.backgroundAlt, p.background)))
    val c = if (p.isLight && fx == SeasonFx.SNOW) Color(0xFF9FB6C8) else col
    ps.forEach { q ->
        when (fx) {
            SeasonFx.SNOW -> {
                val y = size.height * frac(q.y + t * 0.025f * q.speed)
                val x = size.width * frac(q.x + sin(t * 0.4f + q.phase) * 0.015f)
                drawCircle(c.copy(alpha = 0.75f), 1.5f + q.size * 3f, Offset(x, y))
            }
            SeasonFx.PETALS -> {
                val y = size.height * frac(q.y + t * 0.02f * q.speed)
                val x = size.width * frac(q.x + t * 0.008f + sin(t * 0.6f + q.phase) * 0.02f)
                rotate(t * 30f * q.speed + q.phase * 57f, Offset(x, y)) {
                    drawOval(c.copy(alpha = 0.8f), Offset(x - 7f, y - 4f), Size(14f, 8f))
                }
            }
            SeasonFx.RAIN -> {
                val y = size.height * frac(q.y + t * 0.6f * q.speed)
                val x = size.width * q.x
                drawLine(c.copy(alpha = 0.45f), Offset(x, y), Offset(x - 6f, y + 26f), 1.5f)
            }
            SeasonFx.LEAVES -> {
                val y = size.height * frac(q.y + t * 0.018f * q.speed)
                val x = size.width * frac(q.x + sin(t * 0.5f + q.phase) * 0.03f)
                rotate(t * 25f * q.speed + q.phase * 57f, Offset(x, y)) {
                    val leaf = Path().apply {
                        moveTo(x, y - 10f)
                        cubicTo(x + 9f, y - 4f, x + 7f, y + 6f, x, y + 10f)
                        cubicTo(x - 7f, y + 6f, x - 9f, y - 4f, x, y - 10f)
                        close()
                    }
                    drawPath(leaf, (if (q.hue % 3 == 0) Color(0xFFB5541B) else c).copy(alpha = 0.8f))
                }
            }
        }
    }
}
