package org.gnosisvpn.stub.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Milliseconds since this composable entered, updated every frame (for animations keyed to a clock). */
@Composable
fun frameClock(): Long {
    var t by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { val t0 = withFrameMillis { it }; while (true) withFrameMillis { t = it - t0 } }
    return t
}

/** Wall-clock milliseconds (uptime), updated every frame. */
@Composable
fun uptimeFrames(): Long {
    var t by remember { mutableLongStateOf(android.os.SystemClock.uptimeMillis()) }
    LaunchedEffect(Unit) { while (true) withFrameMillis { t = android.os.SystemClock.uptimeMillis() } }
    return t
}

/* ---------- logo ---------- */
@Composable
fun Logo(height: Dp = 26.dp) {
    val p = LocalPal.current
    val ink = if (p.dark) Color.White else Color(0xFF1A1751)
    val v = remember(ink) {
        ImageVector.Builder(defaultWidth = 141.dp, defaultHeight = 27.dp, viewportWidth = 141f, viewportHeight = 27f).apply {
            addPath(PathParser().parsePathString(LogoPaths.P0).toNodes(), fill = SolidColor(ink))
            addPath(PathParser().parsePathString(LogoPaths.P1).toNodes(), fill = SolidColor(ink))
            addGroup(clipPathData = PathParser().parsePathString("M46 0.826416h10a8 8 0 0 1 8 8v10a8 8 0 0 1 -8 8h-10a8 8 0 0 1 -8 -8v-10a8 8 0 0 1 8 -8z").toNodes())
            addPath(PathParser().parsePathString(LogoPaths.P2).toNodes(), fill = SolidColor(ink))
            clearGroup()
            addPath(PathParser().parsePathString(LogoPaths.P3).toNodes(), fill = SolidColor(Color(0xFFFF4288)))
        }.build()
    }
    Image(rememberVectorPainter(v), contentDescription = "Gnosis VPN", modifier = Modifier.height(height).aspectRatio(141f / 27f))
}

/* ---------- helpers ---------- */
/** A font size in view units: the text is drawn under the view's scale, so size it in px before scaling. */
private fun DrawScope.u(v: Float) = (v / density / fontScale).sp
private fun easeIO(p: Double) = if (p < .5) 4 * p * p * p else 1 - (-2 * p + 2).pow(3) / 2
private fun DrawScope.view(vx: Float, vy: Float, vw: Float, vh: Float, block: DrawScope.() -> Unit) {
    val s = min(size.width / vw, size.height / vh)
    val ox = (size.width - vw * s) / 2 - vx * s; val oy = (size.height - vh * s) / 2 - vy * s
    withTransform({ translate(ox, oy); scale(s, s, Offset.Zero) }, block)
}
private fun quad(a: Offset, b: Offset, lift: Float) = Path().apply {
    moveTo(a.x, a.y); quadraticTo((a.x + b.x) / 2, min(a.y, b.y) - lift, b.x, b.y)
}
private fun partial(src: Path, f: Float): Path {
    val m = PathMeasure(); m.setPath(src, false); val out = Path()
    if (f > 0) m.getSegment(0f, m.length * f.coerceIn(0f, 1f), out, true)
    return out
}
private fun along(src: Path, f: Float): Offset { val m = PathMeasure(); m.setPath(src, false); return m.getPosition(m.length * f.coerceIn(0f, 1f)) }
private fun DrawScope.label(tm: TextMeasurer, text: String, x: Float, y: Float, style: TextStyle, anchor: String = "start", alpha: Float = 1f) {
    val r = tm.measure(text, style)
    val dx = when (anchor) { "end" -> -r.size.width.toFloat(); "middle" -> -r.size.width / 2f; else -> 0f }
    drawText(r, topLeft = Offset(x + dx, y - r.firstBaseline), alpha = alpha)
}

/* ---------- fund-first visual (assets/gvis.svg) ---------- */
@Composable
fun GVis(modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val ms = frameClock()
    Canvas(modifier.fillMaxWidth().aspectRatio(260f / 180f)) {
        view(0f, 0f, 260f, 180f) {
            val t = ms / 1000f
            val c = Offset(130f, 90f)
            drawCircle(Brush.radialGradient(listOf(p.sync.copy(alpha = .16f), p.sync.copy(alpha = .02f)), Offset(130f, 81f), 77f), 70f, c)
            drawCircle(p.syncLine, 70f, c, style = Stroke(1.2f))
            drawOval(p.syncLine, Offset(60f, 68f), Size(140f, 44f), style = Stroke(1f))
            drawOval(p.syncLine, Offset(100f, 20f), Size(60f, 140f), style = Stroke(1f))
            drawOval(p.syncLine.copy(alpha = p.syncLine.alpha * .7f), Offset(72f, 44f), Size(116f, 28f), style = Stroke(1f))
            drawOval(p.syncLine.copy(alpha = p.syncLine.alpha * .7f), Offset(72f, 108f), Size(116f, 28f), style = Stroke(1f))
            val blink1 = .3f + .7f * (0.5f + 0.5f * cos(2 * PI.toFloat() * t / 3.4f))
            val blink2 = .3f + .7f * (0.5f - 0.5f * cos(2 * PI.toFloat() * t / 4.1f))
            listOf(Offset(96f, 62f) to 1f, Offset(120f, 44f) to 1f, Offset(152f, 56f) to blink1, Offset(172f, 84f) to 1f, Offset(150f, 112f) to blink2, Offset(112f, 118f) to 1f, Offset(86f, 96f) to 1f)
                .forEach { (o, a) -> drawCircle(p.syncNode.copy(alpha = p.syncNode.alpha * a), 2.4f, o) }
            drawCircle(p.on, 3.2f, Offset(130f, 80f))
            val pa = Path().apply { moveTo(80f, 128f); quadraticTo(100f, 90f, 130f, 80f); quadraticTo(160f, 70f, 184f, 52f) }
            val pb = Path().apply { moveTo(80f, 128f); quadraticTo(130f, 134f, 150f, 112f); quadraticTo(176f, 98f, 172f, 84f); quadraticTo(178f, 68f, 184f, 52f) }
            drawPath(pa, p.sync, style = Stroke(1.6f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 5f), -(t % 1.6f) / 1.6f * 12f)))
            drawPath(pb, p.sync.copy(alpha = .7f), style = Stroke(1.4f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1f, 5f), -(t % 2.1f) / 2.1f * 12f)))
            // the packets: var(--mag) in gvis.svg is undefined, so the prototype draws them in black; ink here
            drawCircle(p.on, 3.2f, along(pa, (t % 5f) / 5f))
            drawCircle(p.on.copy(alpha = .8f), 2.6f, along(pb, ((t + 1.6f) % 6.5f) / 6.5f))
            drawCircle(p.on, 8f, Offset(80f, 128f), style = Stroke(1.8f)); drawCircle(p.on, 3.4f, Offset(80f, 128f))
            val ph = (t % 3f) / 3f; val k = 0.5f - 0.5f * cos(2 * PI.toFloat() * ph)
            drawCircle(p.sync.copy(alpha = 1f - .65f * k), 8f + 3f * k, Offset(184f, 52f), style = Stroke(1.8f)); drawCircle(p.sync, 3.4f, Offset(184f, 52f))
        }
    }
}

/* ---------- connecting animation (connectSVG; one timeline keyed to the connect start) ---------- */
@Composable
fun ConnectStage(eng: Engine, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val tm = rememberTextMeasurer()
    val now = uptimeFrames()
    val e = eng.ex(eng.loc)
    Canvas(modifier.fillMaxWidth().height(168.dp)) {
        view(40f, 0f, 220f, 92f) {
            val T = now / 1000.0; val D = eng.cDur / 1000.0; val el = (now - eng.cStart) / 1000.0
            fun at(f: Double, d: Double): Float { val t0 = f * D; if (el < t0) return 0f; return if (d > 0) easeIO(min(1.0, (el - t0) / d)).toFloat() else 1f }
            val CX = 150f; val CY = 46f; val R = 40f; val K = R / 54f
            fun sc(x: Float, y: Float) = Offset(CX + (x - 150) * K, CY + (y - 62) * K)
            val gn = listOf(118 to 34, 142 to 22, 168 to 30, 130 to 58, 158 to 50, 184 to 64, 124 to 86, 150 to 80, 176 to 92, 196 to 40).map { sc(it.first.toFloat(), it.second.toFloat()) }
            val h = (eng.cStart % 4).toInt()
            val hops = listOf(listOf(3, 1, 4, 5), listOf(0, 4, 2, 7), listOf(6, 1, 5, 4), listOf(3, 7, 2, 8))[h]
            val ci = hops[1]
            val peers = hops + (0..9).filter { it !in hops }.sortedBy { (it * 7 + h) % 11 }.take(2)
            val you = sc(108f, 98f); val ext = sc(200f, 46f)
            fun legAt(k: Int) = .27 + k * .115
            fun hopLit(k: Int) = legAt(k) + .07
            val exitAt = .72
            // globe
            drawCircle(Brush.radialGradient(listOf(p.sync.copy(alpha = .16f), p.sync.copy(alpha = .02f)), Offset(CX, CY - 4), R * 1.1f), R, Offset(CX, CY))
            drawCircle(p.syncLine, R, Offset(CX, CY), style = Stroke(1.2f))
            drawOval(p.syncLine, Offset(CX - R, CY - R * .32f), Size(2 * R, 2 * R * .32f), style = Stroke(1f))
            val lineA = p.syncLine.copy(alpha = p.syncLine.alpha * .7f)
            drawOval(lineA, Offset(CX - R * .82f, CY - R * .45f - R * .22f), Size(2 * R * .82f, 2 * R * .22f), style = Stroke(1f))
            drawOval(lineA, Offset(CX - R * .82f, CY + R * .45f - R * .22f), Size(2 * R * .82f, 2 * R * .22f), style = Stroke(1f))
            for (k in 0..2) {
                val ph = ((T + k * 4) % 12) / 12; val u = easeIO(if (ph < .5) ph * 2 else (1 - ph) * 2)
                val rx = (R + (2 - R) * u).toFloat()
                drawOval(p.syncLine, Offset(CX - rx, CY - R), Size(2 * rx, 2 * R), style = Stroke(1f))
            }
            // stars
            for (k in 0 until 34) {
                val a = (k * 137.5) % 360 * PI / 180; val rr = R * sqrt(((k * 53) % 100) / 100.0)
                val x = (CX + rr * cos(a)).toFloat(); val y = (CY + rr * sin(a) * .92).toFloat()
                var op = if (k % 5 == 0) .5f else .28f
                if (k % 7 == 0) { val d = 2 + k % 3; val ph = ((T + k) % d) / d; op = (.15 + .55 * (1 - abs(ph * 2 - 1))).toFloat() }
                drawCircle(p.sync.copy(alpha = op), if (k % 5 == 0) 1.2f else .7f, Offset(x, y))
            }
            // channels to peers
            peers.forEachIndexed { k, i ->
                val d = quad(you, gn[i], 8f + k * 2); val f = .02 + k * .035
                val op = .75f + (.28f - .75f) * at(.27, 1.2)
                drawPath(partial(d, at(f, .9)), p.sync.copy(alpha = op), style = Stroke(1f, cap = StrokeCap.Round))
                val dotOp = (.85f * at(f + .08, .4)).let { it + (.25f - .85f) * at(.27, 1.2) * (if (it > 0) 1f else 0f) }
                if (dotOp > 0) {
                    val dur = 1.5 + k * .17; val ph = ((T + k * .4) % dur) / dur; val u = easeIO(if (ph < .5) ph * 2 else (1 - ph) * 2)
                    drawCircle(p.syncHi.copy(alpha = dotOp.coerceIn(0f, 1f)), 1.3f, along(d, u.toFloat()))
                }
            }
            gn.forEachIndexed { i, o ->
                val k = peers.indexOf(i); val hi = hops.indexOf(i)
                var op = .55f; var r = 2.3f; var col = p.syncNode
                if (k >= 0) op = .55f + (1f - .55f) * at(.02 + k * .035 + .07, .5)
                if (hi >= 0) { val u = at(hopLit(hi), .5); r = 2.3f + 1.1f * u; if (el >= hopLit(hi) * D) col = p.syncHi }
                drawCircle(col.copy(alpha = col.alpha * op), r, o)
            }
            // routes through one relay each
            val routes = hops.mapIndexed { k, i ->
                Path().apply {
                    moveTo(you.x, you.y); quadraticTo((you.x + gn[i].x) / 2, min(you.y, gn[i].y) - (10 + k * 2), gn[i].x, gn[i].y)
                    quadraticTo((gn[i].x + ext.x) / 2, min(gn[i].y, ext.y) - (8 + k), ext.x, ext.y)
                }
            }
            routes.forEachIndexed { k, d ->
                val f = at(legAt(k), D * .1); if (f <= 0) return@forEachIndexed
                val seg = partial(d, f)
                drawPath(seg, p.sync.copy(alpha = if (k > 0) .08f else .14f), style = Stroke(if (k > 0) 4f else 7f, cap = StrokeCap.Round))
                drawPath(seg, p.sync.copy(alpha = if (k > 0) .7f else 1f), style = Stroke(if (k > 0) 1.2f else 2f, cap = StrokeCap.Round))
            }
            routes.forEachIndexed { k, d ->
                val op = at(max(exitAt + .03, legAt(k) + .1), .4); if (op <= 0) return@forEachIndexed
                val ph = ((T + k * .6) % 2.4) / 2.4
                drawCircle(p.syncHi.copy(alpha = op), 2.4f, along(d, easeIO(ph).toFloat()))
            }
            fun ring(pt: Offset, f: Double) {
                drawCircle(p.syncNode, 6.5f, pt, style = Stroke(1.3f)); drawCircle(p.syncNode, 3.2f, pt)
                val a = at(f, .6); if (a > 0) { drawCircle(p.sync.copy(alpha = a), 6.5f, pt, style = Stroke(1.3f)); drawCircle(p.syncHi.copy(alpha = a), 3.2f, pt) }
            }
            ring(you, 0.0); ring(ext, exitAt)
            val st = TextStyle(fontFamily = Inter, fontSize = 7.sp, fontWeight = FontWeight.Medium, color = p.onV)
            val stb = st.copy(fontWeight = FontWeight.SemiBold, color = p.on)
            val sst = st.copy(fontSize = u(7f)); val sstb = stb.copy(fontSize = u(7f))
            label(tm, "You", you.x - 12, you.y + 4, sstb, "end")
            label(tm, "1 hop", gn[ci].x, gn[ci].y + 14, sst, "middle", at(.3, .6))
            label(tm, e.country, ext.x + 13, ext.y + 4, sstb)
            label(tm, "Exit", ext.x + 13, ext.y + 15, sst)
        }
    }
}

/* ---------- 600 MB graphic (quotaGraphic) ---------- */
@Composable
fun QuotaGraphic(eng: Engine, id: String) {
    val p = LocalPal.current
    val tm = rememberTextMeasurer()
    val ms = frameClock()
    val per = eng.chunk
    val others = eng.alloc.filter { (k, v) -> v > 0 && k != id }.map { it.key to it.value }
    val total = eng.balance + (if (eng.free) per else 0.0) + others.sumOf { it.second }
    val after = if (eng.free) eng.balance else eng.balance - per
    Canvas(Modifier.fillMaxWidth().aspectRatio(330f / 74f)) {
        view(0f, 0f, 330f, 74f) {
            val W = 244f; val x0 = 6f; var cx = x0; var freeMid = x0
            drawRoundRect(p.outline, Offset(2f, 22f), Size(W + 8, 32f), CornerRadius(8f), style = Stroke(1.5f))
            drawRoundRect(p.outline, Offset(W + 12, 31f), Size(4f, 14f), CornerRadius(1.5f))
            val segs = others.map { it.second to it.first as String? } + listOf(after to null)
            val flags = mutableListOf<Pair<String, Float>>()
            segs.forEach { (v, sid) ->
                val w = (v / total * W).toFloat(); if (w <= 0 || total <= 0) return@forEach
                if (sid == null) freeMid = cx + w / 2
                drawRoundRect(p.track, Offset(cx, 26f), Size(max(0f, w - 2), 24f), CornerRadius(4f))
                if (sid != null) flags.add(sid to cx + w / 2)
                cx += w
            }
            val thisW = max(36f, (per / max(total, 1.0) * W).toFloat()); cx = min(cx, W + 6 - thisW)
            val dx = 290 - cx
            drawRoundRect(p.sync.copy(alpha = .18f), Offset(cx, 26f), Size(thisW, 24f), CornerRadius(4f))
            val u = easeIO(((ms - 300) / 1400.0).coerceIn(0.0, 1.0)).toFloat()
            // the chunk slides into the exit; clipped there, so it never pokes out behind the icon
            clipRect(right = 326f) { drawRoundRect(p.sync, Offset(cx + dx * u, 26f), Size(max(thisW, 8f), 24f), CornerRadius(4f)) }
            label(tm, eng.chunkTxt(), cx + thisW / 2, 16f, TextStyle(fontFamily = Mono, fontSize = u(11f), fontWeight = FontWeight.SemiBold, color = p.on), "middle")
            drawPath(Path().apply { moveTo(W + 22, 38f); lineTo(W + 34, 38f); moveTo(W + 29, 33f); lineTo(W + 34, 38f); lineTo(W + 29, 43f) }, p.outline, style = Stroke(1.6f, cap = StrokeCap.Round))
            // the exit it goes to
            val dest = Rect(288f, 24f, 326f, 52f)
            clipPath(Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(dest, CornerRadius(5f))) }) {
                if (eng.icons == "none") drawRect(p.track, dest.topLeft, dest.size)
                else drawBot(eng, id, dest, p)
            }
            flags.forEach { (fid, mid) -> if (eng.icons != "none") drawBot(eng, fid, Rect(mid - 7, 55f, mid + 7, 65f), p) }
            label(tm, "${mb(after)} left", freeMid, 70f, TextStyle(fontFamily = Inter, fontSize = u(10f), fontWeight = FontWeight.Medium, color = p.onV), "middle")
        }
    }
}

/** Draws an exit's pixel bot into [r] (slice-fit), for graphics drawn on a canvas. */
fun DrawScope.drawBot(eng: Engine, id: String, r: Rect, p: Pal) {
    val (c1, c2, bg) = if (p.dark) Triple(Color(0xFFA8A8CA), Color(0xFFD2D0E9), Color(168, 168, 202, 36)) else Triple(Color(0xFF646794), Color(0xFFA8A8CA), Color(0xFFECEBF5))
    drawRect(bg, r.topLeft, r.size)
    val s = max(r.width / 12f, r.height / 9f); val ox = r.left + (r.width - 12 * s) / 2; val oy = r.top + (r.height - 9 * s) / 2
    for ((x, y, c) in botCells(id)) drawRect(if (c == 2) c2 else c1, Offset(ox + x * s, oy + y * s), Size(1.04f * s, 1.04f * s))
}

/* ---------- auto-renew check-in graphic (checkGraphic) ---------- */
@Composable
fun CheckGraphic() {
    val p = LocalPal.current
    val tm = rememberTextMeasurer()
    val ms = frameClock()
    Canvas(Modifier.fillMaxWidth().aspectRatio(280f / 74f)) {
        view(0f, 0f, 280f, 74f) {
            drawRoundRect(p.outline, Offset(2f, 22f), Size(266f, 32f), CornerRadius(8f), style = Stroke(1.5f))
            drawRoundRect(p.outline, Offset(272f, 31f), Size(4f, 14f), CornerRadius(1.5f))
            for (i in 0 until 4) {
                drawRoundRect(p.sync, Offset(6f + i * 28, 26f), Size(24f, 24f), CornerRadius(4f))
                val x = 150f + i * 28
                drawRoundRect(p.outline, Offset(x, 26f), Size(24f, 24f), CornerRadius(4f), style = Stroke(1.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 3f))))
                val ph = (((ms / 1000.0) - i * .5) % 2.4 + 2.4) % 2.4 / 2.4
                val op = when { ph < .3 -> ph / .3; ph < .7 -> 1.0; else -> (1 - ph) / .3 }
                drawRoundRect(p.sync.copy(alpha = op.toFloat().coerceIn(0f, 1f)), Offset(x, 26f), Size(24f, 24f), CornerRadius(4f))
            }
            label(tm, "4 renewed", 48f, 16f, TextStyle(fontFamily = Inter, fontSize = u(10f), fontWeight = FontWeight.Medium, color = p.onV), "middle")
            label(tm, "4 more?", 204f, 16f, TextStyle(fontFamily = Mono, fontSize = u(11f), fontWeight = FontWeight.SemiBold, color = p.on), "middle")
            drawPath(Path().apply { moveTo(122f, 38f); lineTo(136f, 38f); moveTo(131f, 33f); lineTo(136f, 38f); lineTo(131f, 43f) }, p.outline, style = Stroke(1.6f, cap = StrokeCap.Round))
        }
    }
}

/* ---------- live traffic (the demo's visualizer) ---------- */
class Viz {
    class Ep(val kind: String, val g: DoubleArray, var born: Double, var die: Double? = null, val ph: Double)
    var eps = mutableListOf<Ep>(); var c: DoubleArray? = null; var last = 0.0; var nextChurn = 0.0
    var relT0: Double? = null; var dpk = 0.0; var upk = 0.0; var bwAt = 0.0
    var down = 0.0; var up = 0.0; var nRel = 0; var nVpn = 0; var nOth = 0
    private var s = 7L
    fun rand(): Double { s = (s * 16807) % 2147483647; return s / 2147483647.0 }
    val relays: List<DoubleArray> = List(17) { doubleArrayOf(rand() * 100 - 30, rand() * 140 - 40) }

    /** Shows only measured [rates] (Mbit/s down, up); none (off, connecting, paused) reads 0 and keeps the peaks. */
    fun sample(rates: Pair<Double, Double>?, t: Double) {
        val (d, u) = rates ?: (0.0 to 0.0)
        dpk = max(dpk, d); upk = max(upk, u)
        if (t - bwAt >= .2) { bwAt = t; down = d; up = u }
    }
}

val GEO = mapOf("nl" to doubleArrayOf(52.4, 4.9), "at" to doubleArrayOf(48.2, 16.4), "de" to doubleArrayOf(52.5, 13.4), "ch" to doubleArrayOf(46.2, 6.1),
    "pt" to doubleArrayOf(38.7, -9.1), "se" to doubleArrayOf(59.3, 18.1), "br" to doubleArrayOf(-23.5, -46.6), "gb" to doubleArrayOf(51.5, -.1),
    "pl" to doubleArrayOf(52.2, 21.0), "kr" to doubleArrayOf(37.6, 127.0))
/** Rough country centres (lat, lon) for exits named by country code. */
private val CC_GEO = mapOf(
    "DE" to doubleArrayOf(51.2, 10.4), "FR" to doubleArrayOf(46.6, 2.4), "GB" to doubleArrayOf(54.0, -2.0), "NL" to doubleArrayOf(52.2, 5.3),
    "RS" to doubleArrayOf(44.0, 20.9), "PT" to doubleArrayOf(39.6, -8.0), "LT" to doubleArrayOf(55.3, 23.9), "KE" to doubleArrayOf(0.2, 37.9),
    "ES" to doubleArrayOf(40.2, -3.6), "IT" to doubleArrayOf(42.8, 12.6), "CH" to doubleArrayOf(46.8, 8.2), "AT" to doubleArrayOf(47.6, 14.1),
    "PL" to doubleArrayOf(52.1, 19.4), "SE" to doubleArrayOf(62.0, 15.0), "FI" to doubleArrayOf(64.0, 26.0), "NO" to doubleArrayOf(61.0, 9.0),
    "US" to doubleArrayOf(39.8, -98.6), "CA" to doubleArrayOf(56.1, -106.3), "BR" to doubleArrayOf(-14.2, -51.9), "JP" to doubleArrayOf(36.2, 138.3),
    "SG" to doubleArrayOf(1.35, 103.8), "IN" to doubleArrayOf(20.6, 79.0), "AU" to doubleArrayOf(-25.3, 133.8), "ZA" to doubleArrayOf(-30.6, 22.9),
    "IE" to doubleArrayOf(53.4, -8.2), "BE" to doubleArrayOf(50.5, 4.5), "CZ" to doubleArrayOf(49.8, 15.5), "RO" to doubleArrayOf(45.9, 24.9),
    "GR" to doubleArrayOf(39.1, 21.8), "TR" to doubleArrayOf(39.0, 35.2), "UA" to doubleArrayOf(48.4, 31.2), "DK" to doubleArrayOf(56.3, 9.5),
)
private val YOU_GEO = doubleArrayOf(47.4, 8.5)
private fun vec(g: DoubleArray): DoubleArray { val a = g[0] * PI / 180; val b = g[1] * PI / 180; return doubleArrayOf(cos(a) * cos(b), cos(a) * sin(b), sin(a)) }
private fun geoOf(v: DoubleArray) = doubleArrayOf(asin(v[2].coerceIn(-1.0, 1.0)) * 180 / PI, atan2(v[1], v[0]) * 180 / PI)

@Composable
fun TrafficViz(eng: Engine, viz: Viz, modifier: Modifier = Modifier) {
    val p = LocalPal.current
    val tm = rememberTextMeasurer()
    val ms = frameClock()
    Canvas(modifier.fillMaxWidth().aspectRatio(420f / 400f)) {
        view(0f, 0f, 420f, 400f) {
            val t = ms / 1000.0
            val dt = min(.1, t - (if (viz.last == 0.0) t else viz.last)); viz.last = t
            val on = eng.isOn(); val conn = eng.conn == Conn.CONNECTING; val e = eng.ex(eng.loc)
            fun exitGeo(): DoubleArray {
                val g = GEO[eng.loc] ?: CC_GEO[e.countryCode] ?: doubleArrayOf(50.0, 10.0); val a = vec(YOU_GEO); val b = vec(g)
                val ang = acos(min(1.0, a[0] * b[0] + a[1] * b[1] + a[2] * b[2])) * 180 / PI
                return if (ang >= 34) g else doubleArrayOf(YOU_GEO[0] - 14, YOU_GEO[1] + 34)
            }
            val exg = exitGeo()
            val tgt = run {
                val a = vec(YOU_GEO); val b = vec(if (on) exg else YOU_GEO)
                val m = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2]); val n = hypot(hypot(m[0], m[1]), m[2]).takeIf { it > 0 } ?: 1.0
                val g = geoOf(doubleArrayOf(m[0] / n, m[1] / n, m[2] / n)); doubleArrayOf((g[0] + 12).coerceIn(-10.0, 52.0), g[1] + 12)
            }
            val c = viz.c ?: tgt.copyOf().also { viz.c = it }
            c[0] += (tgt[0] - c[0]) * min(1.0, dt * 1.2); val dl = ((tgt[1] - c[1] + 540) % 360) - 180; c[1] += dl * min(1.0, dt * 1.2)
            val la0 = c[0] * PI / 180; val lo0 = (c[1] + 7 * sin(t / 14)) * PI / 180; val CX = 210.0; val CY = 200.0; val R = 168.0
            fun P(v: DoubleArray): DoubleArray {
                val x1 = v[0] * cos(-lo0) - v[1] * sin(-lo0); val y1 = v[0] * sin(-lo0) + v[1] * cos(-lo0)
                val x = y1; val y = v[2] * cos(la0) - x1 * sin(la0); val z = v[2] * sin(la0) + x1 * cos(la0)
                return doubleArrayOf(CX + R * x, CY - R * y, z)
            }
            fun arc(ga: DoubleArray, gb: DoubleArray, grow0: Double = 1.0, lift: Double = .18): List<DoubleArray> {
                val a = vec(ga); val b = vec(gb); val om = acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1.0, 1.0)).takeIf { it > 0 } ?: 1e-6
                val n = 30; val pts = mutableListOf<DoubleArray>()
                fun pt(f: Double): DoubleArray { val s1 = sin((1 - f) * om) / sin(om); val s2 = sin(f * om) / sin(om); val hh = 1 + lift * om * sin(PI * f); return P(doubleArrayOf((a[0] * s1 + b[0] * s2) * hh, (a[1] * s1 + b[1] * s2) * hh, (a[2] * s1 + b[2] * s2) * hh)) }
                val grow = grow0.coerceIn(0.0, 1.0); val k = floor(n * grow).toInt(); for (i in 0..k) pts.add(pt(i.toDouble() / n)); if (n * grow - k > 1e-3) pts.add(pt(grow)); return pts
            }
            fun alongP(pts: List<DoubleArray>, f: Double): Offset { val x = f * (pts.size - 1); val i = min(pts.size - 2, floor(x).toInt()); val u = x - i; val A = pts[i]; val B = pts[i + 1]; return Offset((A[0] + (B[0] - A[0]) * u).toFloat(), (A[1] + (B[1] - A[1]) * u).toFloat()) }
            fun easeO(u: Double) = 1 - (1 - u).pow(3)
            fun path(pts: List<DoubleArray>) = Path().apply { pts.forEachIndexed { i, q -> if (i == 0) moveTo(q[0].toFloat(), q[1].toFloat()) else lineTo(q[0].toFloat(), q[1].toFloat()) } }
            if (t > viz.nextChurn) {
                val alive = viz.eps.filter { it.die == null }; val filling = alive.size < 20
                viz.nextChurn = t + if (filling) .35 + viz.rand() * .9 else 1.2 + viz.rand() * 1.4
                if (!filling) alive[floor(viz.rand() * alive.size).toInt()].die = t
                val kind = if (viz.rand() < .82) "vpn" else "other"; val center = if (viz.rand() < .82) exg else YOU_GEO
                viz.eps.add(Viz.Ep(kind, doubleArrayOf((center[0] + (viz.rand() - .5) * 120).coerceIn(-60.0, 70.0), center[1] + (viz.rand() - .5) * 150), t, null, viz.rand()))
            }
            viz.eps.removeAll { it.die != null && t - it.die!! >= 1 }
            // globe and grid
            drawCircle(Brush.radialGradient(listOf(p.sync.copy(alpha = .10f), p.sync.copy(alpha = .03f)), Offset(189f, 160f), 200f), 168f, Offset(210f, 200f))
            drawCircle(p.syncLine, 168f, Offset(210f, 200f), style = Stroke(1.5f))
            val grid = Path()
            fun line(pts: List<DoubleArray>) { var pen = false; pts.forEach { q -> if (q[2] > 0) { if (pen) grid.lineTo(q[0].toFloat(), q[1].toFloat()) else grid.moveTo(q[0].toFloat(), q[1].toFloat()); pen = true } else pen = false } }
            for (lo in -180 until 180 step 30) line(List(37) { i -> P(vec(doubleArrayOf(-90.0 + i * 5, lo.toDouble()))) })
            for (la in -60..60 step 30) line(List(73) { i -> P(vec(doubleArrayOf(la.toDouble(), -180.0 + i * 5))) })
            drawPath(grid, p.syncLine.copy(alpha = p.syncLine.alpha * .7f), style = Stroke(.8f))
            val you = P(vec(YOU_GEO)); val exitP = P(vec(exg))
            fun vis(q: DoubleArray) = q[2] > -.05
            var nRel = 0; var nVpn = 0; var nOth = 0
            val dots = mutableListOf<() -> Unit>()
            if (on || conn) { if (viz.relT0 == null) viz.relT0 = t } else viz.relT0 = null
            if (on && vis(exitP)) drawPath(path(arc(YOU_GEO, exg, 1.0, .5)), p.sync, style = Stroke(2.4f))
            if (on || conn) viz.relays.forEachIndexed { i, gg ->
                val since = t - viz.relT0!! - (i * .55 + (i * 37 % 10) * .04); if (since <= 0) return@forEachIndexed
                val pts = arc(YOU_GEO, gg, easeO(min(1.0, since / 1)), .12); if (pts.size < 2) return@forEachIndexed
                val end = pts.last(); if (!vis(end)) return@forEachIndexed; nRel++
                drawPath(path(pts), p.sync.copy(alpha = .45f), style = Stroke(1f))
                dots.add { drawCircle(p.bg, 5f, Offset(end[0].toFloat(), end[1].toFloat())); drawCircle(p.sync, 4f, Offset(end[0].toFloat(), end[1].toFloat())) }
                if (i % 3 == 0 && on) { val q = alongP(pts, (t * .35 + i * .13) % 1); dots.add { drawCircle(p.syncHi, 2f, q) } }
            }
            val blocked = !on && eng.lockdown && !eng.ldOffPay
            viz.eps.forEach { ep ->
                val age = t - ep.born; val grow = easeO(min(1.0, age / 1.1))
                var fade = (if (ep.die != null) max(0.0, 1 - (t - ep.die!!) / 1) else 1.0) * min(1.0, age / .5)
                val viaVpn = ep.kind == "vpn" && on; val from = if (viaVpn) exg else YOU_GEO
                if (blocked) return@forEach
                if (ep.kind == "vpn" && !on && conn) return@forEach
                val kind = if (viaVpn) "vpn" else "other"; val pts = arc(from, ep.g, grow, .2); val end = pts.last(); if (pts.size < 2 || !vis(end)) return@forEach
                fade *= ((end[2] + .05) / .3).coerceIn(0.0, 1.0); if (kind == "vpn") nVpn++ else nOth++
                val col = if (kind == "vpn") p.onV else p.warn
                drawPath(path(pts), col.copy(alpha = (fade * if (kind == "vpn") .7 else 1.0).toFloat()), style = Stroke(1.1f, pathEffect = if (kind == "other") PathEffect.dashPathEffect(floatArrayOf(3f, 3f)) else null))
                val o = Offset(end[0].toFloat(), end[1].toFloat())
                if (grow > .8) { val r = (3.6 * (grow - .8) / .2).toFloat(); dots.add { drawCircle(p.bg.copy(alpha = fade.toFloat()), r + 1f, o); drawCircle(col.copy(alpha = fade.toFloat()), r, o) } }
                if (grow >= .999 && ep.ph < .45) { val q = alongP(pts, (t * .45 + ep.ph * 3) % 1); dots.add { drawCircle((if (kind == "vpn") p.on else p.warn).copy(alpha = fade.toFloat()), 1.8f, q) } }
            }
            dots.forEach { it() }
            val lbl = TextStyle(fontFamily = Inter, fontSize = u(11f), fontWeight = FontWeight.Medium, color = p.on)
            if (on && vis(exitP)) {
                val o = Offset(exitP[0].toFloat(), exitP[1].toFloat())
                drawCircle(p.sync, 9f, o, style = Stroke(1.5f)); drawCircle(p.bg, 6.5f, o); drawCircle(p.syncHi, 5.5f, o)
                drawRoundRect(p.surfHi, Offset(o.x - 48, o.y + 12), Size(96f, 18f), CornerRadius(9f))
                label(tm, e.country, o.x, o.y + 25, lbl, "middle")
            }
            val yo = Offset(you[0].toFloat(), you[1].toFloat())
            drawCircle(p.onV, 11f, yo, style = Stroke(1.5f)); drawCircle(p.bg, 8f, yo); drawCircle(p.on, 7f, yo)
            label(tm, "You", yo.x, yo.y + 30, lbl, "middle")
            viz.sample(eng.rates(), t)
            viz.nRel = nRel; viz.nVpn = nVpn; viz.nOth = nOth
        }
    }
}

/* ---------- QR code of an address ---------- */
fun qrBitmap(text: String, px: Int = 480): ImageBitmap {
    val m = com.google.zxing.MultiFormatWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, px, px, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
    val bmp = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until m.height) for (x in 0 until m.width) bmp.setPixel(x, y, if (m[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
    return bmp.asImageBitmap()
}
