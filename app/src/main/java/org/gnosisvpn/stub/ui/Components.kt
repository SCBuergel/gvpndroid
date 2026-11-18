package org.gnosisvpn.stub.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ---------- icons: Material Symbols names → Material icons (outlined; "fill" → rounded/filled) ---------- */
fun icon(name: String, fill: Boolean = false): ImageVector = if (fill) when (name) {
    "check_circle" -> Icons.Rounded.CheckCircle; "warning" -> Icons.Rounded.Warning; "pause_circle" -> Icons.Rounded.PauseCircle
    "info" -> Icons.Rounded.Info; "system_update" -> Icons.Rounded.SystemUpdate; "cloud_off" -> Icons.Rounded.CloudOff
    "sync_problem" -> Icons.Rounded.SyncProblem; "lock" -> Icons.Rounded.Lock; "lock_open" -> Icons.Rounded.LockOpen
    "hourglass_top" -> Icons.Rounded.HourglassTop; "push_pin" -> Icons.Rounded.PushPin; "radar" -> Icons.Rounded.Radar
    "account_balance_wallet" -> Icons.Rounded.AccountBalanceWallet; "shield" -> Icons.Rounded.Shield; "block" -> Icons.Rounded.Block
    "error" -> Icons.Rounded.Error; "data_alert" -> Icons.Rounded.DataUsage; "vpn_key" -> Icons.Rounded.VpnKey
    "upload" -> Icons.Rounded.Upload; "schedule" -> Icons.Rounded.Schedule
    else -> icon(name, false)
} else when (name) {
    "check_circle" -> Icons.Outlined.CheckCircle; "warning" -> Icons.Outlined.WarningAmber; "pause_circle" -> Icons.Outlined.PauseCircle
    "info" -> Icons.Outlined.Info; "system_update" -> Icons.Outlined.SystemUpdate; "cloud_off" -> Icons.Outlined.CloudOff
    "sync_problem" -> Icons.Outlined.SyncProblem; "lock" -> Icons.Outlined.Lock; "lock_open" -> Icons.Outlined.LockOpen
    "hourglass_top" -> Icons.Outlined.HourglassTop; "push_pin" -> Icons.Outlined.PushPin; "radar" -> Icons.Outlined.Radar
    "account_balance_wallet" -> Icons.Outlined.AccountBalanceWallet; "settings" -> Icons.Outlined.Settings; "public" -> Icons.Outlined.Public
    "auto_awesome" -> Icons.Outlined.AutoAwesome; "history" -> Icons.Outlined.History; "check" -> Icons.Rounded.Check
    "chevron_right" -> Icons.Outlined.ChevronRight; "expand_more" -> Icons.Outlined.ExpandMore; "expand_less" -> Icons.Outlined.ExpandLess
    "power_settings_new" -> Icons.Rounded.PowerSettingsNew; "add" -> Icons.Rounded.Add; "autorenew" -> Icons.Rounded.Autorenew
    "travel_explore" -> Icons.Outlined.TravelExplore; "format_list_bulleted" -> Icons.AutoMirrored.Outlined.FormatListBulleted
    "arrow_back" -> Icons.AutoMirrored.Outlined.ArrowBack; "close" -> Icons.Outlined.Close; "search" -> Icons.Outlined.Search
    "open_in_new" -> Icons.AutoMirrored.Outlined.OpenInNew; "savings" -> Icons.Outlined.Savings; "content_copy" -> Icons.Outlined.ContentCopy
    "share" -> Icons.Outlined.Share; "shield" -> Icons.Outlined.Shield; "android" -> Icons.Outlined.Android; "touch_app" -> Icons.Outlined.TouchApp
    "delete" -> Icons.Outlined.Delete; "schedule" -> Icons.Outlined.Schedule; "upload" -> Icons.Outlined.Upload; "download" -> Icons.Outlined.Download
    "visibility_off" -> Icons.Outlined.VisibilityOff; "all_inclusive" -> Icons.Outlined.AllInclusive; "location_on" -> Icons.Outlined.LocationOn
    "monitoring" -> Icons.Outlined.Insights; "smart_toy" -> Icons.Outlined.SmartToy; "toggle_on" -> Icons.Outlined.ToggleOn
    "tune" -> Icons.Outlined.Tune; "qr_code_2" -> Icons.Outlined.QrCode2; "data_alert" -> Icons.Outlined.DataUsage; "error" -> Icons.Outlined.ErrorOutline
    "block" -> Icons.Outlined.Block; "search_off" -> Icons.Outlined.SearchOff; "south" -> Icons.Outlined.South; "north" -> Icons.Outlined.North
    "vpn_key" -> Icons.Outlined.VpnKey; "arrow_forward" -> Icons.AutoMirrored.Outlined.ArrowForward; "refresh" -> Icons.Outlined.Refresh
    "developer_mode" -> Icons.Outlined.DeveloperMode; "badge" -> Icons.Outlined.Badge
    else -> Icons.Outlined.Info
}

@Composable
fun Ms(name: String, fill: Boolean = false, size: Dp = 24.dp, tint: Color = Color.Unspecified, modifier: Modifier = Modifier) {
    Icon(icon(name, fill), contentDescription = null, modifier = modifier.size(size), tint = if (tint == Color.Unspecified) androidx.compose.material3.LocalContentColor.current else tint)
}

/* ---------- buttons (app.css .btn) ---------- */
enum class BK { FILLED, TONAL, OUTLINED, TEXT, GO, STOP, PROTO, MORE, DONE }
enum class BS { SM, MD, LG }

@Composable
fun Btn(
    kind: BK, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: BS = BS.MD,
    icon: String? = null, trail: String? = null, enabled: Boolean = true, suffix: String? = null,
    container: Color? = null, content: Color? = null,
) {
    val p = LocalPal.current
    val (bg, fg) = when (kind) {
        BK.FILLED -> p.primary to p.onPrimary
        BK.TONAL -> p.sc to p.onSc
        BK.OUTLINED, BK.TEXT -> Color.Transparent to p.primaryT
        BK.GO, BK.DONE -> p.pc to p.onPc
        BK.STOP -> p.ec to p.onEc
        BK.PROTO -> Color.Transparent to p.onV
        BK.MORE -> Color.Transparent to p.on
    }
    val h = when (size) { BS.SM -> 32.dp; BS.MD -> 40.dp; BS.LG -> 56.dp }
    val pad = when { kind == BK.TEXT || kind == BK.MORE -> 12.dp; size == BS.SM -> 14.dp; size == BS.LG -> 12.dp; else -> 24.dp }
    val fs = when (size) { BS.SM -> 13.sp; BS.MD -> 14.sp; BS.LG -> 16.sp }
    val isz = when (size) { BS.SM -> 16.dp; BS.MD -> 18.dp; BS.LG -> 22.dp }
    val border = if (kind == BK.OUTLINED) BorderStroke(1.dp, p.outline) else null
    var m = modifier.height(if (kind == BK.MORE) 44.dp else if (kind == BK.PROTO) 36.dp else h)
    // prototype-only controls: a dashed outline, so they never pass for real ones
    if (kind == BK.PROTO) m = m.drawBehind {
        val sw = 1.dp.toPx()
        drawRoundRect(p.outlineV, Offset(sw / 2, sw / 2), Size(this.size.width - sw, this.size.height - sw), CornerRadius(this.size.height / 2),
            style = Stroke(sw, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
    }
    if (size == BS.LG && kind != BK.PROTO) m = m.fillMaxWidth()
    Button(
        onClick = onClick, enabled = enabled, modifier = m,
        shape = RoundedCornerShape(50), border = if (enabled) border else null,
        contentPadding = PaddingValues(horizontal = pad),
        colors = ButtonDefaults.buttonColors(
            containerColor = container ?: bg, contentColor = content ?: fg,
            disabledContainerColor = p.on.copy(alpha = 0.10f), disabledContentColor = p.on.copy(alpha = 0.38f),
        ),
        elevation = null,
    ) {
        if (icon != null) { Ms(icon, size = isz, tint = if (kind == BK.MORE) p.onV else Color.Unspecified); Spacer(Modifier.width(if (size == BS.LG) 6.dp else 8.dp)) }
        val fgNow = if (enabled) (content ?: fg) else p.on.copy(alpha = 0.38f)
        Text(buildAnnotatedString {
            append(label)
            if (suffix != null) withStyle(SpanStyle(fontWeight = FontWeight.Medium, color = fgNow.copy(alpha = fgNow.alpha * 0.75f))) { append(" · $suffix") }
        }, fontSize = fs, fontWeight = if (kind == BK.PROTO || kind == BK.MORE) FontWeight.Medium else FontWeight.SemiBold, fontFamily = Inter,
            maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (trail != null) { Spacer(Modifier.width(8.dp)); Ms(trail, size = isz) }
    }
}

/** Icon button with a long-press tooltip (the prototype's data-tip). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IB(name: String, label: String, onClick: () -> Unit, fill: Boolean = false, tint: Color = Color.Unspecified, badge: Boolean = false, small: Boolean = false) {
    val p = LocalPal.current
    Tip(label) {
        IconButton(onClick = onClick, modifier = Modifier.size(if (small) 40.dp else 48.dp)) {
            Box {
                Ms(name, fill = fill, size = if (small) 20.dp else 24.dp, tint = if (tint == Color.Unspecified) p.onV else tint)
                if (badge) Box(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).size(8.dp).background(p.err, CircleShape).border(2.dp, p.bg, CircleShape))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Tip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text, fontFamily = Inter, fontSize = 12.sp) } },
        state = rememberTooltipState(), content = content,
    )
}

/* ---------- pills, bars, exit icons ---------- */
enum class PK { PLAIN, SEL, ON, BAD, PROTO }

@Composable
fun Pill(text: String, kind: PK = PK.PLAIN, icon: String? = null) {
    val p = LocalPal.current
    val (bg, fg) = when (kind) {
        PK.PLAIN -> p.surfHi to p.onV; PK.SEL -> p.sc to p.onSc; PK.ON -> p.pc to p.onPc; PK.BAD -> p.ec to p.onEc; PK.PROTO -> Color.Transparent to p.onV
    }
    Row(
        Modifier.height(24.dp).clip(RoundedCornerShape(12.dp)).background(bg)
            .then(if (kind == PK.PROTO) Modifier.border(1.dp, p.outlineV, RoundedCornerShape(12.dp)) else Modifier)
            .padding(start = if (icon != null) 8.dp else 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Ms(icon, size = 15.dp, tint = fg)
        Text(text, color = fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Inter, maxLines = 1)
    }
}

/** The signal bars of an exit's grade; [checking] animates them. */
@Composable
fun Bars(e: Exit?, eng: Engine, checking: Boolean = false) {
    val p = LocalPal.current
    val g = eng.grade(e)
    if (!checking && g.k == "hidden") return
    val anim = if (checking) rememberInfiniteTransition(label = "chk").animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "chk").value else 0f
    Canvas(Modifier.size(22.dp, 14.dp)) {
        val w = 4.dp.toPx(); val gap = 2.dp.toPx(); val r = CornerRadius(1.5.dp.toPx())
        listOf(5, 8, 11, 14).forEachIndexed { i, hh ->
            val bh = hh.dp.toPx(); val x = i * (w + gap); val top = size.height - bh
            when {
                checking -> {
                    val ph = ((anim - i * 0.125f) % 1f + 1f) % 1f
                    val k = 1f - kotlin.math.abs(ph - 0.5f) * 2f
                    drawRoundRect(androidx.compose.ui.graphics.lerp(p.track, p.outline, k), Offset(x, top), Size(w, bh), r)
                }
                g.k == "unmeasured" -> drawRoundRect(p.outline, Offset(x + 0.5f, top + 0.5f), Size(w - 1f, bh - 1f), r, style = Stroke(1.dp.toPx()))
                else -> drawRoundRect(if (i < g.n) p.signal(g.tone) else p.track, Offset(x, top), Size(w, bh), r)
            }
        }
    }
}

/** Quality line: the grade label, "Weak route" in red. */
@Composable
fun QLine(e: Exit, eng: Engine) {
    val p = LocalPal.current
    val g = eng.grade(e)
    Text(g.label, style = T.ql, color = if (g.k == "weak") p.err else p.onV)
}

enum class XS(val w: Dp, val h: Dp, val r: Dp) { SM(40.dp, 30.dp, 8.dp), LG(52.dp, 39.dp, 10.dp), XL(128.dp, 96.dp, 24.dp) }

/** The exit icon: a pixel bot generated from the exit's id (as in the demo), its flag, or nothing. */
@Composable
fun XIcon(id: String, eng: Engine, size: XS = XS.SM) {
    if (eng.icons == "none") return
    val p = LocalPal.current
    if (eng.icons == "flag" && id != "auto") {
        val cc = eng.exOrNull(id)?.countryCode ?: id
        Box(Modifier.size(size.w, size.h).clip(RoundedCornerShape(size.r)).background(p.surfHi), contentAlignment = Alignment.Center) {
            Text(flagEmoji(cc), fontSize = (size.h.value * 0.62f).sp)
        }
        return
    }
    val (c1, c2, bg) = if (p.dark) Triple(Color(0xFFA8A8CA), Color(0xFFD2D0E9), Color(168, 168, 202, 36)) else Triple(Color(0xFF646794), Color(0xFFA8A8CA), Color(0xFFECEBF5))
    val cells = if (id == "auto") autoCells() else botCells(id)
    val (vw, vh) = if (id == "auto") 15f to 13f else 12f to 9f
    Canvas(Modifier.size(size.w, size.h).clip(RoundedCornerShape(size.r))) {
        drawRect(bg)
        // xMidYMid slice: cover the box, crop the overflow
        val s = maxOf(this.size.width / vw, this.size.height / vh)
        val ox = (this.size.width - vw * s) / 2; val oy = (this.size.height - vh * s) / 2
        for ((x, y, col) in cells) drawRect(if (col == 2) c2 else c1, Offset(ox + x * s, oy + y * s), Size(1.04f * s, 1.04f * s))
    }
}

fun flagEmoji(cc: String): String {
    val c = cc.uppercase()
    if (c.length != 2 || !c.all { it in 'A'..'Z' }) return "🏳"
    return String(Character.toChars(0x1F1E6 + (c[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (c[1] - 'A')))
}

data class Cell(val x: Float, val y: Float, val c: Int)

private val botCache = HashMap<String, List<Cell>>()
fun botCells(id: String): List<Cell> = botCache.getOrPut(id) {
    var h = hash32("bot:$id").toUInt()
    fun r(n: Int): Int { val v = (h % n.toUInt()).toInt(); h = ((h xor (h shr 13)).toInt() * 2654435761L.toInt()).toUInt(); return v }
    val px = LinkedHashMap<Pair<Int, Int>, Int>()
    fun set(x: Int, y: Int, c: Int) { px[x to y] = c; px[(6 - x) to y] = c }
    val wide = r(2); val top = 1 + r(2); val bot = top + 2 + r(2)
    for (y in top..bot) for (x in (if (wide == 1) 0 else 1)..3) set(x, y, 1)
    when (r(4)) { 0 -> set(3, top - 1, 2); 1 -> set(1, top - 1, 2); 2 -> set(2, top - 1, 1) }
    val ey = top + if (bot - top > 2) 1 else 0; val ex0 = 1 + r(2)
    px.remove(minOf(ex0 + 1, 2) to ey); px.remove((6 - minOf(ex0 + 1, 2)) to ey)
    if (r(2) == 1) set(3, bot - (if (r(2) == 1) 0 else 1), 2)
    if (wide == 0 && r(2) == 1) set(0, top + 1, 2)
    when (r(4)) {
        0 -> set(1, bot + 1, 1); 1 -> set(2, bot + 1, 1)
        2 -> { set(1, bot + 1, 2); set(3, bot + 1, 2) }
        3 -> { set(0, bot + 1, 1); set(2, bot + 1, 1) }
    }
    if (r(3) == 0) set(if (wide == 1) 1 else 2, top, 2)
    px.entries.filter { it.key.second in 0..6 }.map { Cell(it.key.first + 2.5f, it.key.second + 1f, it.value) }
}

private var autoCache: List<Cell>? = null
fun autoCells(): List<Cell> = autoCache ?: run {
    val g = Array(11) { CharArray(13) { '.' } }
    val ys = IntArray(13) { x -> Math.round(9 * (Math.exp(3.4 * x / 12) - 1) / (Math.exp(3.4) - 1)).toInt() }
    var prev: Int? = null
    for (x in 0 until 13) {
        val y = minOf(10, 10 - ys[x])
        prev?.let { pv -> for (yy in minOf(pv, y)..maxOf(pv, y)) g[yy][x] = '#' }
        g[y][x] = '#'; prev = y
    }
    val ey = 10 - ys[12]
    for (dy in 0 until 2) for (dx in -1 until 1) if (ey + dy < 11) g[ey + dy][12 + dx] = 'o'
    val out = mutableListOf<Cell>()
    g.forEachIndexed { y, row -> row.forEachIndexed { x, ch -> if (ch != '.') out.add(Cell(x + 1f, y + 1f, if (ch == 'o') 2 else 1)) } }
    out.also { autoCache = it }
}

/* ---------- messages (msgHTML) ---------- */
data class Msg(val tone: Tone, val icon: String, val lead: String, val text: String, val btns: List<Pair<String, String>>? = null, val own: Boolean = false)

@Composable
fun MsgCard(m: Msg, act: (String, Map<String, String>) -> Unit, live: (String) -> String = { it }) {
    val p = LocalPal.current
    val fg = p.onContainer(m.tone)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.container(m.tone)).padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Ms(m.icon, fill = true, tint = p.accent(m.tone))
        Column(Modifier.weight(1f).padding(top = 2.dp, end = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(m.lead) }
                if (m.text.isNotEmpty()) append(" " + live(m.text))
            }, style = T.bm, color = fg)
            if (!m.btns.isNullOrEmpty()) Row(Modifier.offset(x = (-2).dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                m.btns.forEachIndexed { i, (l, a) ->
                    if (i == 0) Btn(BK.TONAL, l, { act(a, emptyMap()) }, size = BS.SM, container = fg.copy(alpha = 0.12f), content = fg)
                    else Btn(BK.TEXT, l, { act(a, emptyMap()) }, size = BS.SM, content = fg)
                }
            }
        }
        if (m.own) IB("close", "Dismiss", { act("dismissMsg", mapOf("k" to m.lead)) }, small = true, tint = p.onV)
    }
}

/* ---------- containers ---------- */
@Composable
fun Card(modifier: Modifier = Modifier, color: Color = LocalPal.current.surfLo, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color), content = content)
}

@Composable
fun PadCard(content: @Composable ColumnScope.() -> Unit) {
    Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

/** An M3 list item row (app.css .li). */
@Composable
fun Li(
    title: String, sub: String? = null, icon: String? = null, onClick: (() -> Unit)? = null, enabled: Boolean = true,
    divider: Boolean = false, minH: Dp = 56.dp, below: @Composable (ColumnScope.() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,   // last, so a trailing lambda is the trailing control
) {
    val p = LocalPal.current
    Column {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV))
        Row(
            Modifier.fillMaxWidth().heightIn(min = minH)
                .then(if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .then(if (!enabled) Modifier.alphaOf(0.45f) else Modifier),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (icon != null) Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Ms(icon, tint = p.onV) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = T.tm, color = p.on)
                if (sub != null) Text(sub, style = T.bm, color = p.onV)
                below?.invoke(this)
            }
            trailing?.invoke(this)
        }
    }
}

fun Modifier.alphaOf(a: Float) = this.alpha(a)

/** A list group (app.css .list): rounded surface, rows separated by hairlines. */
@Composable
fun ListGroup(color: Color = LocalPal.current.surfLo, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(color), content = content)
}

@Composable
fun Sw(on: Boolean, onChange: () -> Unit, enabled: Boolean = true) {
    val p = LocalPal.current
    Switch(
        checked = on, onCheckedChange = { onChange() }, enabled = enabled,
        thumbContent = if (on) ({ Ms("check", size = 16.dp, tint = p.onPc) }) else null,
        colors = SwitchDefaults.colors(
            checkedTrackColor = p.primary, checkedThumbColor = p.onPrimary, uncheckedTrackColor = p.surfTop,
            uncheckedThumbColor = p.outline, uncheckedBorderColor = p.outline,
        ),
    )
}

@Composable
fun SectionHead(text: String) {
    Text(text, style = T.sh, color = LocalPal.current.primaryT, modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp))
}

@Composable
fun NoteText(text: AnnotatedString, center: Boolean = false) {
    Text(text, style = T.note, color = LocalPal.current.onV, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp),
        textAlign = if (center) androidx.compose.ui.text.style.TextAlign.Center else null)
}
@Composable fun NoteText(text: String, center: Boolean = false) = NoteText(AnnotatedString(text), center)

/** Text with **bold** parts, as the prototype's <b> markup. */
fun rich(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, part -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(part) } else append(part) }
}

/** Key/value line (app.css .dl). */
@Composable
fun Dl(k: String, v: String, mono: Boolean = false, divider: Boolean = true, vColor: Color = Color.Unspecified) {
    val p = LocalPal.current
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(k, style = T.bm, color = p.onV)
            Text(v, style = if (mono) T.mono.copy(fontSize = 14.sp) else T.bm, fontWeight = FontWeight.SemiBold, color = if (vColor == Color.Unspecified) p.on else vColor)
        }
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV))
    }
}

/** Top app bar of a pushed page (app.css .tab). */
@Composable
fun AppBar(title: String, onBack: () -> Unit, bg: Color = LocalPal.current.bg, titleStyle: TextStyle = TextStyle(fontFamily = Inter, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium), actions: @Composable RowScope.() -> Unit = {}) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().height(64.dp).background(bg).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IB("arrow_back", "Navigate up", onBack, tint = p.on)
        Text(title, style = titleStyle, color = p.on, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}

/** Linear progress (app.css .lin). */
@Composable
fun Lin(f: Float, modifier: Modifier = Modifier, color: Color = LocalPal.current.sync, h: Dp = 4.dp) {
    val p = LocalPal.current
    Box(modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(h / 2)).background(p.track)) {
        Box(Modifier.fillMaxWidth(f.coerceIn(0f, 1f)).height(h).clip(RoundedCornerShape(h / 2)).background(color))
    }
}
