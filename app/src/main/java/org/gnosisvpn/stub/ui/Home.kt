package org.gnosisvpn.stub.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import org.gnosisvpn.stub.R
import kotlin.math.roundToInt

typealias Act = (String, Map<String, String>) -> Unit

fun Act.go(a: String, vararg kv: Pair<String, String>) = this(a, kv.toMap())

/** Pages that fill the screen and have their buttons at the bottom, in thumb reach. */
@Composable
fun Screen(bg: Color = LocalPal.current.bg, top: @Composable ColumnScope.() -> Unit = {}, bottom: @Composable ColumnScope.() -> Unit = {}, body: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().background(bg)) {
        top()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), content = body)
        Column(Modifier.fillMaxWidth().background(bg).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = bottom)
    }
}

/* ---------- I-1, I-1w: fund first ---------- */
@Composable
fun GateScreen(eng: Engine, act: Act, debug: Boolean) {
    val p = LocalPal.current
    Screen(bottom = { FundButtons(act, "gateFund") }) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 28.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Logo(); Spacer(Modifier.height(4.dp))
            Text("Fund your VPN", style = T.hl, color = p.on, textAlign = TextAlign.Center)
            Text("Your funds pay the independent nodes that carry your traffic. Once they arrive, we find your best route.", style = T.lead, color = p.onV, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
            GVis(Modifier.widthIn(max = 280.dp))
            if (eng.pay != null) WaitMsg()
        }
    }
}

/** The funding site, and next to it the VPN address (QR code, text, GnosisScan) in a sheet. */
@Composable
fun FundButtons(act: Act, fund: String) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn(BK.FILLED, "Open funding site", { act.go(fund) }, Modifier.weight(1f), size = BS.LG, trail = "open_in_new")
        Tip("Your VPN address") {
            Box(Modifier.size(56.dp).clip(CircleShape).background(p.sc).clickable { act.go("ui:address") }.semantics { contentDescription = "Your VPN address" }.testTag("address"),
                contentAlignment = Alignment.Center) { Ms("qr_code_2", size = 26.dp, tint = p.onSc) }
        }
    }
}

@Composable
fun WaitMsg() = MsgCard(Msg(Tone.INFO, "hourglass_top", "Waiting for your payment.", "Usually a few minutes."), { _, _ -> })

/* ---------- I0: discovering the network ---------- */
private val SYNC_STEPS = listOf(0 to "Looking for other node runners", 13 to "Determining available routes", 25 to "Opening channels", 40 to "Finding return paths",
    62 to "Checking exit health and capacity", 88 to "Establishing first loop.", 100 to "Same exit, a different route each time")

@Composable
fun SyncScreen(eng: Engine, act: Act, debug: Boolean) {
    val p = LocalPal.current
    val t = eng.syncT ?: 0.0
    val f = eng.syncProgress()
    val comp by rememberLottieComposition(LottieCompositionSpec.RawRes(if (p.dark) R.raw.sync_dark else R.raw.sync_light))
    Column(Modifier.fillMaxSize().background(p.bg).semantics { contentDescription = "Discovering the network" }) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Discovering the network", style = T.hl, color = p.on)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                Text("Progress", style = T.lbl, color = p.on)
                Text(String.format(java.util.Locale.ROOT, "%.1f%%", f * 100), style = T.mono.copy(fontSize = 28.sp, fontWeight = FontWeight.SemiBold), color = p.on)
            }
            Lin(f)
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
            if (comp != null) LottieAnimation(comp, progress = { f }, modifier = Modifier.fillMaxSize())
        }
        val step = SYNC_STEPS.last { t >= it.first || it.first == 0 }.second
        Text(eng.syncStep() ?: step, style = T.tm.copy(fontSize = 15.sp), color = p.on, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).heightIn(min = 22.dp))
        Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        }
    }
}

/* ---------- E1: failure ---------- */
@Composable
fun FailScreen(eng: Engine, act: Act) {
    val p = LocalPal.current
    val fl = eng.fail ?: return
    Screen(bottom = { Btn(BK.FILLED, "Try again", { act.go("failRetry") }, size = BS.LG) }) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.padding(bottom = 8.dp).size(56.dp).clip(RoundedCornerShape(16.dp)).background(p.ec), contentAlignment = Alignment.Center) { Ms("error", fill = true, size = 30.dp, tint = p.err) }
            Text("Something went wrong", style = T.lbl, color = p.onV)
            Text("That didn’t work.", style = T.hl, color = p.on)
            Card(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
                Li(fl.title, icon = "cloud_off")
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Failure code", style = T.lbl, color = p.onV)
                        Text(fl.code, style = T.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = p.on)
                    }
                    IB("content_copy", "Copy failure code", { act.go("copyCode") })
                }
            }
            SectionHead("Troubleshooting")
            Text("Check that your phone is online, then try again. If it keeps failing, export the logs and send them with the failure code.", style = T.bm, color = p.on)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(BK.OUTLINED, "Export logs", { act.go("logsExport") }, icon = "download")
            }
        }
    }
}

/* ---------- the main screen ---------- */
@Composable
fun StatusDot(conn: Conn) {
    val p = LocalPal.current
    val blink = conn == Conn.CONNECTING || conn == Conn.RECONNECTING
    val a = if (blink) rememberInfiniteTransition(label = "dot").animateFloat(1f, .3f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "dot").value else 1f
    val col = when (conn) { Conn.CONNECTED, Conn.CONNECTING -> p.sync; Conn.RECONNECTING -> p.warn; Conn.PAUSED -> p.err; Conn.IDLE -> p.outline }
    Box(contentAlignment = Alignment.Center) {
        if (conn == Conn.CONNECTED) Box(Modifier.size(18.dp).background(p.syncLine, CircleShape))
        Box(Modifier.size(10.dp).alpha(a).background(col, CircleShape))
    }
}

@Composable
fun BalanceChip(eng: Engine, act: Act) {
    val p = LocalPal.current
    val b = eng.balance; val n = (b / eng.chunk).toInt(); val f = (b / (eng.chunk * 8)).coerceIn(0.0, 1.0).toFloat()
    val col = if (n <= 0) p.err else if (n <= 1) p.warn else p.sync
    val txt = eng.balanceText()
    Tip("Balance $txt") {
        Column(
            Modifier.padding(horizontal = 4.dp).height(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.Transparent)
                .clickable { act.go("balance") }.semantics { contentDescription = "Balance $txt, enough for $n exits" }
                .then(Modifier.background(Color.Transparent)).padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
        ) {
            Text(txt, style = T.mono.copy(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold), color = p.on, maxLines = 1)
            Lin(f, Modifier.width(44.dp), col, 3.dp)
        }
    }
}

@Composable
fun HomeScreen(eng: Engine, act: Act, moreMsgs: Boolean, toggleMore: () -> Unit) {
    val p = LocalPal.current
    val e = eng.ex(eng.loc); val c = eng.conn; val a = eng.left(); val simple = eng.simple
    val lockOn = eng.lockdown && !eng.ldOffPay
    Column(Modifier.fillMaxSize().background(p.bg)) {
        // top app bar: the status, then the actions
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).testTag("status"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(c)
                Text(eng.statusTxt(), style = T.tl.copy(fontSize = 18.sp), color = p.on, maxLines = 1)
            }
            if (!simple) {
                IB(if (lockOn) "lock" else "lock_open", if (lockOn) "Lockdown on · opens VPN settings" else if (eng.ldOffPay) "Lockdown off since you paid" else "Lockdown off",
                    { act.go("lockTap") }, fill = lockOn, tint = if (lockOn) p.primaryT else if (eng.ldOffPay) p.warn else p.onV)
                if (eng.metered) { IB("public", "Live traffic", { act.go("traffic") }); BalanceChip(eng, act) }
            }
            IB("settings", if (eng.update != null) "Settings, update available" else "Settings", { act.go("settings") }, badge = eng.update != null)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val chk = eng.checking && c == Conn.IDLE
            val label: @Composable () -> Unit = {
                when {
                    c == Conn.IDLE || c == Conn.CONNECTING || c == Conn.RECONNECTING ->
                        if (eng.mode == "auto") Pill("Current Best Location", PK.PLAIN, "auto_awesome") else Pill("Selected Location", PK.SEL, "push_pin")
                    c == Conn.PAUSED -> Pill("Last location", PK.BAD, "history")
                    !simple -> Pill("Current Location", PK.ON, "check")
                }
            }
            if (simple) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(p.surfLo).clickable { act.go("pick") }
                        .semantics { contentDescription = "Choose location, now ${e.country}" }.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 22.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // a new best exit slides in (the prototype's swap animation)
                    androidx.compose.animation.AnimatedContent(e.id, transitionSpec = {
                        (androidx.compose.animation.fadeIn(tween(700)) + androidx.compose.animation.slideInVertically(tween(700)) { it / 10 }) togetherWith androidx.compose.animation.fadeOut(tween(150))
                    }, label = "hero") { id ->
                        val x = eng.ex(id)
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            XIcon(x.id, eng, XS.XL)
                            Text(x.country, fontFamily = Inter, fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.8).sp, color = p.on, modifier = Modifier.padding(top = 6.dp), textAlign = TextAlign.Center)
                        }
                    }
                    if (c == Conn.CONNECTED && eng.metered && a > 0) DataLeft(eng, act)
                    else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Bars(e, eng, chk)
                        if (chk) Text("Checking…", style = T.bm, color = p.onV) else QLine(e, eng)
                    }
                    if (eng.mode != "auto" || c == Conn.PAUSED) Box(Modifier.heightIn(min = 24.dp)) { label() }
                }
            } else {
                DetailCard(eng, act, label, chk)
            }
            // the slot: the connecting animation, otherwise messages
            Column(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (c == Conn.CONNECTING) ConnectingSlot(eng)
                else {
                    val all = eng.moments(); val lim = if (moreMsgs) 9 else if (eng.ldOffPay || simple) 1 else 2
                    val live: (String) -> String = { it.replace("{retry}", eng.retryText()) }
                    all.take(lim).map { if (simple) eng.simpleLine(it) else it }.forEach { MsgCard(it, act, live) }
                    if (all.size > lim) Row(
                        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(Color.Transparent)
                            .then(Modifier.clickable { toggleMore() }).padding(start = 8.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) { Ms("expand_more", size = 18.dp, tint = p.onV); Text("${all.size - lim} more", fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = p.onV) }
                    if (eng.ldOffPay) MsgCard(
                        if (simple) Msg(Tone.WARN, "lock_open", "Lockdown is off.", "", listOf("Turn it back on" to "lockOn"))
                        else Msg(Tone.WARN, "lock_open", "Lockdown is off", "since you paid.", listOf("Turn Lockdown back on" to "lockOn", "Not now" to "ldNot")), act)
                }
            }
            if (!simple && eng.metered && eng.details && c != Conn.PAUSED && c != Conn.CONNECTING) DetailsCard(eng, act)
        }
        // bottom actions, in thumb reach
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Btn(BK.MORE, "More locations", { act.go("pick") }, icon = "format_list_bulleted", modifier = Modifier.fillMaxWidth())
            val blocked = !(a > 0) && !eng.free && eng.short()
            when (c) {
                Conn.IDLE -> when {
                    eng.topup || (blocked && eng.ldOffPay && eng.pay != null) -> Btn(BK.GO, eng.connLbl(), {}, size = BS.LG, icon = "power_settings_new", enabled = false)
                    blocked -> Btn(BK.FILLED, "Add data", { act.go("addData") }, size = BS.LG, icon = "add")
                    else -> ConnectBtn(eng, act)
                }
                Conn.CONNECTING -> Btn(BK.STOP, "Disconnect", { act.go("cancel") }, size = BS.LG, icon = "power_settings_new")
                Conn.CONNECTED, Conn.RECONNECTING -> Btn(BK.STOP, "Disconnect", { act.go("disconnect") }, size = BS.LG, icon = "power_settings_new")
                Conn.PAUSED -> {
                    when (eng.pause) {
                        Pause.NODATA -> Btn(BK.GO, "Renew and reconnect", { act.go("renew") }, size = BS.LG, icon = "autorenew", suffix = "${eng.chunkTxt()}")
                        Pause.OFFLINE -> Btn(BK.GO, "Connect to best available", { act.go("bestAvail") }, size = BS.LG, icon = "travel_explore", suffix = "${eng.chunkTxt()}")
                        Pause.BALANCE -> if (eng.lockdown) Btn(BK.FILLED, "Turn off Lockdown and pay", { act.go("payOut") }, size = BS.LG, icon = "lock_open")
                            else Btn(BK.FILLED, "Add data", { act.go("addData") }, size = BS.LG, icon = "add")
                        else -> Btn(BK.GO, "Retry now", { act.go("renew") }, size = BS.LG, icon = "refresh")
                    }
                    Btn(BK.OUTLINED, "Turn off VPN", { act.go("offAsk") }, size = BS.LG)
                }
            }
        }
    }
}

@Composable
private fun ConnectBtn(eng: Engine, act: Act) {
    val l = eng.connLbl()
    if (l.contains(" · ")) Btn(BK.GO, l.substringBefore(" · "), { act.go("connect") }, size = BS.LG, icon = "power_settings_new", suffix = l.substringAfter(" · "))
    else Btn(BK.GO, l, { act.go("connect") }, size = BS.LG, icon = "power_settings_new")
}

@Composable
private fun ConnectingSlot(eng: Engine) {
    val p = LocalPal.current
    val now = uptimeFrames()
    val prog = eng.connectFrac() ?: ((now - eng.cStart).toFloat() / eng.cDur.coerceAtLeast(1)).coerceIn(0f, 1f)
    val st = minOf(3, (prog * 4).toInt())
    val e = eng.ex(eng.loc)
    val steps = listOf("Finding relays", "One hop, a new relay per packet", "Securing the tunnel", "Reaching the exit in ${e.city}")
    val blink = rememberInfiniteTransition(label = "xd").animateFloat(1f, .3f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "xd").value
    Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectStage(eng)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 0..3) Box(Modifier.size(6.dp).alpha(if (i == st) blink else 1f).background(if (i <= st) p.sync else p.track, CircleShape))
            }
            Text(eng.connectStep() ?: steps[st], style = T.ql, color = p.on, modifier = Modifier.weight(1f))
        }
        Lin(prog)
    }
}

@Composable
private fun DetailCard(eng: Engine, act: Act, label: @Composable () -> Unit, chk: Boolean) {
    val p = LocalPal.current
    val e = eng.ex(eng.loc); val c = eng.conn; val a = eng.left(); val pz = eng.pause
    Card(Modifier.padding(top = 0.dp)) {
        Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp).heightIn(min = 24.dp)) { label() }
            Row(
                Modifier.fillMaxWidth().clickable { act.go("pick") }.semantics { contentDescription = "Choose location, now ${e.country}" }
                    .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                XIcon(e.id, eng, XS.LG)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(e.country, style = T.tl, color = p.on)
                        Text(" #${eng.exitTag(e)}", style = T.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium), color = p.onV)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Bars(e, eng, chk); QLine(e, eng) }
                }
                Ms("chevron_right", tint = p.onV)
            }
            val canDet = c != Conn.PAUSED && eng.metered
            Box(Modifier.padding(horizontal = 4.dp).fillMaxWidth().height(1.dp).background(p.outlineV))
            Row(
                Modifier.padding(horizontal = 4.dp).fillMaxWidth().clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .then(if (canDet) Modifier.clickable { act.go("details") } else Modifier).padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!eng.metered) {
                    Kv("Exit", "#${eng.exitTag(e)}", mono = true)
                    Kv(if (c == Conn.CONNECTED) "Connected for" else "Route", if (c == Conn.CONNECTED) dur((eng.connSec / 60).toInt()) else "1 hop", mono = c == Conn.CONNECTED)
                } else if (c == Conn.PAUSED) when (pz) {
                    Pause.OFFLINE -> { Kv("Exit", "Offline", p.err); Kv("Data lost", "${eng.lost.roundToInt()} MB", mono = true) }
                    Pause.RENEWFAIL -> { Kv("Renewal", "Failed", p.err); if (eng.retryCountdown) Kv("Next try in", eng.retryText(), mono = true) else Kv("Retrying", "Automatically") }
                    else -> { Kv("This session", "${a.roundToInt()} MB", p.err, mono = true); if (pz == Pause.BALANCE) Kv("Balance", eng.balanceText(), p.err, true) else Kv("Traffic", "Blocked", p.err) }
                } else {
                    Kv("Ping", if (c == Conn.RECONNECTING || e.ping <= 0) "—" else "${e.ping.grouped()} ms", if (c == Conn.RECONNECTING) p.onV else if (e.ping < 500) p.okT else if (e.ping < 900) p.warn else p.err, true)
                    if (a > 0 || c == Conn.CONNECTED) {
                        Kv("Data left", mb(a), if (a <= LOW) p.warn else Color.Unspecified, true)
                        if (c == Conn.CONNECTED) Kv("Lasts", eng.timeLeft() ?: "—", mono = true)
                    }
                    else if (c == Conn.IDLE) Kv("Sets aside", if (eng.free) "${eng.chunkTxt()} free" else "${eng.chunkTxt()}", mono = true)
                }
                if (canDet) Ms(if (eng.details) "expand_less" else "expand_more", tint = p.onV)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Kv(k: String, v: String, color: Color = Color.Unspecified, mono: Boolean = false) {
    val p = LocalPal.current
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(k, fontFamily = Inter, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = p.onV)
        Text(v, style = if (mono) T.mono.copy(fontSize = 16.sp) else T.bm.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold, color = if (color == Color.Unspecified) p.on else color)
    }
}

@Composable
private fun DetailsCard(eng: Engine, act: Act) {
    val p = LocalPal.current
    val e = eng.ex(eng.loc); val c = eng.conn
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) { Text("Latency", style = T.lbl, color = p.onV); Text(if (c == Conn.RECONNECTING || e.ping <= 0) "—" else "${e.ping.grouped()} ms", style = T.mono.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = p.on) }
                Column(Modifier.weight(1f)) { Text("Server load", style = T.lbl, color = p.onV); Text(eng.serverLoad(e)?.let { "$it%" } ?: "—", style = T.mono.copy(fontSize = 18.sp, fontWeight = FontWeight.SemiBold), color = p.on) }
            }
            Text("Diagnostics", style = T.lbl, color = p.onV, modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
            Dl("Last checked", ago(e.checkedAt), mono = true)
            Dl("Path", if (e.pv > 0) "${(e.pv * 100).roundToInt()}%" else "—", mono = true)
            Dl("First relays", if (e.fr > 0) "${e.fr}" else "—")
            Dl("Route", "1 hop")
            Dl("Open spots", if (e.slots > 0) "${e.spots} of ${e.slots}" else "—", divider = false)
            if (eng.left() > 0 && eng.isOn()) Usage(eng, "Usage", act)
        }
    }
}

/** The main screen while connected: the data left at this exit, how long it lasts, a meter. Tap: the session's numbers. */
@Composable
private fun DataLeft(eng: Engine, act: Act) {
    val p = LocalPal.current
    val a = eng.left(); val tl = eng.timeLeft()
    val low = a <= LOW
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable { act.go("ui:session") }
            .semantics { contentDescription = "${mb(a)} left at this exit" + (tl?.let { ", lasts $it" } ?: "") + ". Session details" }
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("data-left"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(leftText(mb(a) + " left", tl?.let { " · $it" }), style = T.tm.copy(fontSize = 17.sp), color = if (low) p.warn else p.on, textAlign = TextAlign.Center)
        Lin((a / eng.chunk).toFloat().coerceIn(0f, 1f), Modifier.width(200.dp), if (low) p.warn else p.sync, 6.dp)
        if (eng.autoRenewFixed && !eng.short()) Text("Renews from your balance", style = T.bs, color = p.onV)
    }
}

private fun leftText(main: String, rest: String?) = buildAnnotatedString {
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(main) }
    if (rest != null) withStyle(SpanStyle(fontWeight = FontWeight.Normal)) { append(rest) }
}

/** Data left at this exit: meter and time remaining (usageHTML). */
@Composable
fun Usage(eng: Engine, cap: String, act: Act) {
    val p = LocalPal.current
    val a = eng.left()
    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(cap, style = T.lbl, color = p.onV)
            Text(mb(a), style = T.mono.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold), color = p.on)
        }
        Lin((a / eng.chunk).toFloat(), color = if (a <= LOW) p.warn else p.sync, h = 8.dp)
        val tl = if (a > 0) "Time remaining " + (if (eng.isOn() && eng.sessionRows() == null) dur(minOf(eng.minsLeft(), maxOf(0, ((10800 - eng.connSec) / 60).roundToInt()))) else eng.timeLeft() ?: "—") else "—"
        Row(
            Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).clickable { act.go("ui:session") }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) { Text(tl, style = T.ql, color = p.onV); Ms("info", size = 18.dp, tint = p.onV) }
    }
}
