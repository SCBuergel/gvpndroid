package org.gnosisvpn.stub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun Pad(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
}

/* ---------- S03s, S11: choose location ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickPage(eng: Engine, act: Act) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        AppBar("Choose location", { act.go("back") })
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(28.dp)).background(p.surfHi).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Ms("search", tint = p.onV)
                Box(Modifier.weight(1f)) {
                    if (eng.q.isEmpty()) Text("Search countries", style = T.lead, color = p.onV)
                    BasicTextField(eng.q, { act.go("q", "v" to it) }, singleLine = true, textStyle = T.lead.copy(color = p.on), cursorBrush = SolidColor(p.primary), modifier = Modifier.fillMaxWidth())
                }
                if (eng.q.isNotEmpty()) IB("close", "Clear search", { act.go("q", "v" to "") })
            }
            val opts = if (eng.simple) listOf("best" to "Best", "rel" to "Reliable", "recent" to "Recent") else listOf("best" to "Best", "rel" to "Reliable", "alpha" to "A–Z", "recent" to "Recent")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                opts.forEachIndexed { i, (v, l) ->
                    Tip(TIPS[if (v == "best") "fast" else v] ?: "") {
                        SegmentedButton(
                            selected = eng.ptab == v, onClick = { act.go("ptab", "v" to v) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size),
                            colors = SegmentedButtonDefaults.colors(activeContainerColor = p.sc, activeContentColor = p.onSc, inactiveContainerColor = Color.Transparent, inactiveContentColor = p.on, activeBorderColor = p.outline, inactiveBorderColor = p.outline),
                            // four segments fit 360 dp only without the check mark
                            icon = { if (opts.size < 4) SegmentedButtonDefaults.Icon(eng.ptab == v) },
                            label = { Text(l, fontFamily = Inter, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) },
                        )
                    }
                }
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            PickRows(eng, act)
            val nFull = eng.exits.count { it.full && eng.fullMsg?.id != it.id }
            Text(if (!eng.metered) (if (eng.exits.isEmpty()) "Looking for exits…" else "${eng.exits.size} exits") else if (eng.ptab == "recent") "Your last 6 exits · data left there is used first" else "Each new exit sets aside 600 MB · $nFull full exits hidden",
                style = T.bs, color = p.onV, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 24.dp))
        }
    }
}

@Composable
private fun PickRows(eng: Engine, act: Act) {
    val p = LocalPal.current
    val on = eng.isOn() || eng.conn == Conn.PAUSED; val q = eng.q.trim().lowercase(); val b = eng.best()
    val all = eng.exits.filter { x -> (eng.usable(x) || x.offline || eng.fullMsg?.id == x.id) && (q.isNotEmpty() || x.id != eng.loc || eng.ptab == "recent") }
    var list = when {
        q.isNotEmpty() -> all.filter { it.city.lowercase().contains(q) || it.country.lowercase().contains(q) || it.place.lowercase().contains(q) }
        eng.ptab == "recent" -> eng.recent.mapNotNull { r -> eng.exOrNull(r.id) }.filter { it in all }
        eng.ptab == "rel" -> all.sortedWith(compareBy({ -it.rel }, { it.ping }))
        eng.ptab == "alpha" -> all.sortedBy { it.country }
        else -> all.sortedByDescending { eng.grade(it).sc ?: -2.0 }
    }
    if (eng.simple) list = list.filter { x -> (!x.offline || x.id == eng.loc) && !(q.isEmpty() && eng.ptab == "best" && b != null && x.id == b.id) }
    val more = q.isEmpty() && !eng.pall && list.size > 5
    if (q.isEmpty() && !eng.pall) list = list.take(5)
    Column(Modifier.padding(horizontal = 8.dp)) {
        if (q.isEmpty() && eng.ptab == "best") {
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(16.dp)).background(p.surfLo), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).heightIn(min = 72.dp).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    XIcon("auto", eng)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Pill("Current Best Location", icon = "auto_awesome")
                        Text(if (eng.simple) (b?.country ?: "Best available") else "Auto · best available", style = T.tm, color = p.on)
                        if (eng.simple) { if (b != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { Bars(b, eng); Text(eng.grade(b).label, style = T.ql, color = p.onV) } }
                        else Text("Picks fast, reliable exits" + (b?.let { " · ${it.country} now" } ?: ""), style = T.ql, color = p.onV)
                    }
                }
                Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (eng.mode == "auto" && (eng.isOn() || eng.conn == Conn.IDLE) && b != null && b.id == eng.loc) Pill(if (eng.isOn()) "On" else "Selected", PK.ON, "check")
                    else { Btn(BK.TONAL, if (eng.isOn()) "Switch" else "Connect", { act.go("auto") }, size = BS.SM); if (eng.metered) Price("600 MB") }
                }
            }
        }
        list.forEachIndexed { i, x -> ExitRow(eng, act, x, on, b, divider = i > 0 && eng.openRow != list[i - 1].id && eng.openRow != x.id) }
        if (list.isEmpty()) {
            val (ic, txt) = if (q.isNotEmpty()) "search_off" to "No exit matches “${eng.q}”." else if (eng.ptab == "recent") "history" to "No exits used yet" else null to null
            if (txt != null) Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Ms(ic!!, size = 36.dp, tint = p.onV); Text(txt, style = T.bm, color = p.onV, textAlign = TextAlign.Center)
            }
        }
        if (more) Row(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalArrangement = Arrangement.Center) { Btn(BK.TEXT, "Show all exits", { act.go("pall") }, icon = "expand_more") }
    }
}

@Composable
private fun Price(s: String) = Text(s, style = T.mono.copy(fontSize = 11.sp), color = LocalPal.current.onV)

@Composable
private fun ExitRow(eng: Engine, act: Act, x: Exit, on: Boolean, b: Exit?, divider: Boolean) {
    val p = LocalPal.current
    val cur = x.id == eng.loc; val a = eng.left(x.id); val open = !eng.simple && eng.openRow == x.id
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV))
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(if (open) p.surfLo else Color.Transparent)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val click: (() -> Unit)? = if (eng.simple) (if (eng.usable(x) && !(cur && on)) ({ act.go("pick2", "id" to x.id) }) else null) else ({ act.go("row", "id" to x.id) })
            Row(
                Modifier.weight(1f).heightIn(min = 72.dp).clip(RoundedCornerShape(16.dp)).then(if (click != null) Modifier.clickable(onClick = click) else Modifier)
                    .padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                XIcon(x.id, eng)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(x.country, style = T.tm, color = p.on)
                        if (!eng.simple) Text(" #${eng.exitTag(x)}", style = T.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium), color = p.onV)
                    }
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                        if (eng.warmNow() && x.id != b?.id) { Bars(x, eng, true); Text("Measuring…", style = T.ql, color = p.onV) }
                        else {
                            Bars(x, eng)
                            if (x.full) Text("Full", style = T.ql, color = p.err) else QLine(x, eng)
                            HoldPill(eng, x.id)
                            val r = eng.recent.firstOrNull { it.id == x.id }
                            if (eng.ptab == "recent" && r != null) Text("· used ${fmtT(r.at)}", style = T.ql, color = p.onV)
                        }
                    }
                }
                if (!eng.simple) Ms(if (open) "expand_less" else "expand_more", tint = p.onV)
            }
            Column(Modifier.padding(end = 8.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                when {
                    x.offline -> Pill("Offline", PK.BAD)
                    x.full -> Pill("Full", PK.BAD)
                    cur && on -> Pill(if (eng.conn == Conn.PAUSED) "Paused" else "Connected", PK.ON)
                    else -> { Btn(BK.TONAL, if (on) "Switch" else "Connect", { act.go("pick2", "id" to x.id) }, size = BS.SM); if (eng.metered) Price(if (a > 0) "${a.roundToInt()} MB left" else if (eng.free && !on) "free" else "600 MB") }
                }
            }
        }
        if (open) RowStats(eng, x)
        val fm = eng.fullMsg
        if (fm != null && fm.id == x.id) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            MsgCard(Msg(Tone.WARN, "block", "${x.city} just filled up.", fm.alt?.let { "Next most reliable: ${eng.ex(it).city}." } ?: "",
                fm.alt?.let { listOf("${if (on) "Switch" else "Connect"} to ${eng.ex(it).country} · 600 MB" to "pick2:$it") }), { a2, d -> if (a2.startsWith("pick2:")) act.go("pick2", "id" to a2.substringAfter(":")) else act(a2, d) })
        }
    }
}

@Composable
private fun HoldPill(eng: Engine, id: String) {
    val p = LocalPal.current
    val a = eng.left(id); if (!(a > 0) || id == eng.loc) return
    val m = (eng.heldLeft(id) / 60).roundToInt()
    Tip("Data you left here. It stays for ${dur(m)}, then it is gone.") {
        Row(Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(p.sc).padding(start = 5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val f = (a / COST).coerceIn(0.0, 1.0).toFloat()
            androidx.compose.foundation.Canvas(Modifier.size(12.dp)) {
                val sw = size.width * 3f / 14f; val r = size.width * 5f / 14f
                drawCircle(p.track, r, style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
                drawArc(p.onSc, -90f, 360f * f, false, topLeft = androidx.compose.ui.geometry.Offset(center.x - r, center.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r), style = androidx.compose.ui.graphics.drawscope.Stroke(sw))
            }
            Text("${a.roundToInt()} MB · ${dur(m)}", style = T.mono.copy(fontSize = 11.sp), color = p.onSc)
        }
    }
}

private val STAT_TIPS = mapOf("Latency" to "Round trip through the relay to the exit and back.", "Checked" to "When this exit was last measured.",
    "Path" to "Quality of the best path through the mixnet. Below 90 % counts as a weak route.", "Relays" to "Distinct first relays the route can use.",
    "Load" to "How busy the exit is: used slots out of all slots.", "Cost" to "Data set aside from your balance when you connect here.")

@Composable
private fun RowStats(eng: Engine, e: Exit) {
    val p = LocalPal.current
    val a = eng.left(e.id); val m = e.measured; val load = if (e.slots > 0) ((1 - e.spots.toDouble() / e.slots) * 100).roundToInt() else null
    val items = listOf("Latency" to (if (m) "${e.ping.grouped()} ms" else "—"), "Checked" to "${(eng.t % 30).toInt()} s ago", "Path" to "${(e.pv * 100).roundToInt()}%",
        "Relays" to "${minOf(e.fr, 3)} of 3", "Load" to (if (m && load != null) "$load%" else "—"), "Cost" to (if (a > 0) "0 MB · ${a.roundToInt()} left" else "600 MB"))
    Column(Modifier.padding(start = 66.dp, end = 12.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v) ->
                    Column(Modifier.weight(1f)) {
                        Tip(STAT_TIPS[k] ?: "") {
                            Row(Modifier.padding(top = 4.dp, bottom = 2.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(k, style = T.bs, color = p.onV); Ms("info", size = 14.dp, tint = p.onV)
                            }
                        }
                        Text(v, style = T.mono.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = p.on)
                    }
                }
            }
        }
    }
}

/* ---------- S19n, S06n: settings ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(eng: Engine, act: Act, debug: Boolean, extra: SettingsExtra) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        AppBar("Settings", { act.go("back") })
        val tabs = listOf("general" to "General", "usage" to "Usage")
        PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == eng.stab }.coerceAtLeast(0), containerColor = p.bg, contentColor = p.primaryT, divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV)) }) {
            tabs.forEach { (v, l) -> Tab(selected = eng.stab == v, onClick = { act.go("tab", "v" to v) }, text = { Text(l, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }, selectedContentColor = p.primaryT, unselectedContentColor = p.onV) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Pad { if (eng.stab == "general") SettingsGeneral(eng, act, debug, extra) else SettingsUsage(eng, act) }
        }
    }
}

/** Rows the activity adds to Settings (real app: identity export, developer screen). */
class SettingsExtra(val rows: List<Triple<String, String, String>> = emptyList(), val version: String = "", val onRow: (String) -> Unit = {})

@Composable
private fun SettingsGeneral(eng: Engine, act: Act, debug: Boolean, extra: SettingsExtra) {
    val p = LocalPal.current
    val lockSub = if (eng.ldOffPay) "Off since you paid · Android VPN settings" else if (eng.lockdown) "On · Block connections without VPN" else "Off · traffic goes direct when the VPN is off"
    Text("Protection", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    ListGroup {
        Li("Connect when the app starts", icon = "power_settings_new", onClick = { act.go("tog", "k" to "startConnect") }) { Sw(eng.startConnect, { act.go("tog", "k" to "startConnect") }) }
        Li("Lockdown", lockSub, icon = if (eng.lockdown && !eng.ldOffPay) "lock" else "lock_open", onClick = { act.go("lockTap") }, divider = true) { Ms("open_in_new", tint = p.onV) }
    }
    NoteText(rich("Together, nothing leaves your phone before Gnosis VPN is connected. Lockdown is Android’s **Always-on VPN** with **Block connections without VPN**."))
    if (eng.metered) Text("Renewing", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    if (eng.metered) ListGroup {
        Li("Hide data use before connecting", "Connect goes straight ahead instead of first showing the 600 MB an exit takes.", "visibility_off", { act.go("togHide") }) { Sw(!eng.popover, { act.go("togHide") }) }
        Li("Auto-renew sessions", "Adds the next 600 MB by itself when an exit runs low, without asking.", "autorenew", { act.go("togAR") }, divider = true) { Sw(eng.autoRenew, { act.go("togAR") }) }
        Li("Auto-renew past 4 cycles", "After four renewals in a row, keeps going without checking in with you.", "all_inclusive", { act.go("togP4") }, enabled = eng.autoRenew, divider = true) { Sw(eng.arPast4, { act.go("togP4") }, enabled = eng.autoRenew) }
    }
    Text("App", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    if (eng.update != null) Card(color = p.lavc) {
        Li("Update to $UPD_V", "Ready to install. Restarting takes a few seconds.", "system_update") { Btn(BK.FILLED, "Restart", { act.go("updNow") }, size = BS.SM) }
    }
    ListGroup {
        Li("Preferred exit", if (eng.mode == "auto") "Auto · best" else eng.ex(eng.loc).city, "location_on", { act.go("pick") }) { Ms("chevron_right", tint = p.onV) }
        Li("Detailed metrics", "Ping, the route, open spots and the full exit list on the main screen.", "monitoring", { act.go("view") }, divider = true) { Sw(!eng.simple, { act.go("view") }) }
        Li("Exit icons", icon = "smart_toy", divider = true, below = {
            Spacer(Modifier.height(8.dp))
            val opts = listOf("node" to "Node", "flag" to "Flags", "none" to "None")
            SingleChoiceSegmentedButtonRow {
                opts.forEachIndexed { i, (v, l) ->
                    SegmentedButton(selected = eng.icons == v, onClick = { act.go("icons", "v" to v) }, shape = SegmentedButtonDefaults.itemShape(i, opts.size), modifier = Modifier.height(32.dp),
                        colors = SegmentedButtonDefaults.colors(activeContainerColor = p.sc, activeContentColor = p.onSc, inactiveContainerColor = Color.Transparent, inactiveContentColor = p.on),
                        label = { Text(l, fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) })
                }
            }
        })
        Li("Quick Settings tile", if (eng.qsTile) "Added. Pull down the status bar to connect from there." else "Connect and disconnect from the Quick Settings panel.", "toggle_on", divider = true) {
            if (eng.qsTile) Pill("Added", PK.ON, "check") else Btn(BK.TONAL, "Add", { act.go("qsAsk") }, size = BS.SM)
        }
    }
    Text("Logs, for support", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    ListGroup {
        Li("Export logs", "Saved to a folder you pick.", "download") { Btn(BK.TEXT, "Export", { act.go("logsExport") }, size = BS.SM) }
        Li("Upload logs", "Sent to support. Check what’s included first.", "upload", divider = true) { Btn(BK.TEXT, "Upload", { act.go("ui:upload") }, size = BS.SM) }
    }
    if (extra.rows.isNotEmpty()) {
        Text("Node", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        ListGroup { extra.rows.forEachIndexed { i, (k, title, sub) -> Li(title, sub, if (k == "dev") "developer_mode" else "badge", { extra.onRow(k) }, divider = i > 0) { Ms("chevron_right", tint = p.onV) } } }
    }
    if (debug) {
        Text("Prototype", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        ListGroup { Li("Demo controls", "Scenarios, network events, time speed, notification rules.", "tune", { act.go("demo") }) { Ms("chevron_right", tint = p.onV) } }
    }
    NoteText(extra.version, center = true)
}

@Composable
private fun SettingsUsage(eng: Engine, act: Act) {
    val p = LocalPal.current
    if (!eng.metered) NoteText("Your balance and data use will show here once the Gnosis VPN core reports them. You can fund your node any time:")
    if (eng.metered) Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Available balance", style = T.lbl, color = p.onV)
        Text(mb(eng.balance), style = T.big, color = p.on)
        Text(eng.usePhrase(), style = T.bm, color = p.onV)
    } }
    if (eng.metered && eng.balance <= COST) MsgCard(Msg(if (eng.balance < COST) Tone.BAD else Tone.WARN, "warning", if (eng.balance < COST) "Balance empty:" else "Balance running low:", if (eng.balance < COST) "no renewals left." else "1 renewal left."), act)
    ListGroup {
        Li("Send funds directly", "Your address and what to send", "qr_code_2", { act.go("gateDirect") }) { Ms("chevron_right", tint = p.onV) }
        Li("Open funding site", "Pay with crypto from a wallet", "account_balance_wallet", { act.go("funding") }, divider = true) { Ms("open_in_new", tint = p.onV) }
    }
    if (eng.metered && (eng.isOn() || eng.left() > 0)) {
        SectionHead("This exit")
        PadCard { Usage(eng, eng.ex(eng.loc).city, act) }
    }
}

/* ---------- S18: add data ---------- */
@Composable
fun AddPage(eng: Engine, act: Act) {
    Screen(top = { AppBar("Fund your VPN", { act.go("back") }) }, bottom = {
        Btn(BK.FILLED, "Open funding site", { act.go("funding") }, size = BS.LG, trail = "open_in_new")
        Btn(BK.OUTLINED, "Send funds directly", { act.go("gateDirect") }, size = BS.LG)
    }) {
        val p = LocalPal.current
        Pad {
            PadCard { Text("Pay with crypto", style = T.tm, color = p.on); Text("Top up your balance with crypto you have left in a wallet. The funding site walks you through it.", style = T.bm, color = p.onV) }
            PadCard { Text("Your VPN address", style = T.lbl, color = p.onV); Address(eng, act, share = true); Text("Paste it on the funding site.", style = T.bs, color = p.onV) }
            if (eng.pay != null) WaitMsg() else NoteText("Your balance updates about 2 minutes after paying. More payment methods are planned.")
        }
    }
}

@Composable
fun Address(eng: Engine, act: Act, share: Boolean) {
    val p = LocalPal.current
    val a = eng.address()
    val copied = eng.copiedAt > 0 && eng.now() - eng.copiedAt < 2200
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(buildAnnotatedString {
            val head = minOf(6, a.length); val tail = maxOf(head, a.length - 4)
            withStyle(SpanStyle(color = p.on, fontWeight = FontWeight.SemiBold)) { append(a.substring(0, head)) }
            append(a.substring(head, tail))
            withStyle(SpanStyle(color = p.on, fontWeight = FontWeight.SemiBold)) { append(a.substring(tail)) }
        }, style = T.mono.copy(fontSize = 15.sp, lineHeight = 22.sp), color = p.onV)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (copied) BK.DONE else BK.TONAL, if (copied) "Copied" else "Copy", { act.go("copy") }, size = BS.SM, icon = if (copied) "check" else "content_copy")
            if (share) Btn(BK.TEXT, "Share", { act.go("share") }, size = BS.SM, icon = "share")
        }
    }
}

/* ---------- S18a: pay outside the VPN ---------- */
@Composable
fun PayOutPage(eng: Engine, act: Act) {
    val p = LocalPal.current
    Screen(top = { AppBar("Add data", { act.go("back") }) }, bottom = { Btn(BK.FILLED, "Continue to VPN settings", { act.go("payGo") }, size = BS.LG, trail = "open_in_new") }) {
        Pad {
            PadCard {
                Ms("warning", fill = true, size = 32.dp, tint = p.warn)
                Text("Pay outside the VPN", style = T.tl, color = p.on)
                Text("With no data left, the funding site can’t be reached through Gnosis VPN. To pay, Lockdown turns off and the VPN disconnects.", style = T.bm, color = p.onV)
            }
            MsgCard(Msg(Tone.WARN, "shield", "Until you reconnect, your traffic will not be private.", ""), act)
            NoteText(rich("Android keeps Lockdown in its own VPN settings. Continue opens them: turn off **Block connections without VPN**, then come back. Once your payment arrives, we’ll ask you to turn it back on."))
        }
    }
}

/* ---------- I-1d: send funds directly ---------- */
@Composable
fun DirectPage(eng: Engine, act: Act) {
    val p = LocalPal.current
    val addr = eng.address()
    val qr = remember(addr) { qrBitmap(addr) }
    Screen(top = { AppBar("Send funds directly", { act.go("back") }) }) {
        Pad {
            Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp).size(200.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                    Image(qr, "QR code of your VPN address", Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                }
                Text("Your VPN address", style = T.lbl, color = p.onV)
                Address(eng, act, share = true)
            } }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).border(1.dp, p.outlineV, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ms("info", size = 20.dp, tint = p.primaryT)
                Text(rich("Send on **Gnosis Chain** only. Other networks won’t arrive."), style = T.bm, color = p.on)
            }
            SectionHead("What to send")
            val ok = eng.balance >= COST
            ListGroup {
                eng.fundingNeeds().forEachIndexed { i, (tok, what, amt) ->
                    Li(tok, what, divider = i > 0) {
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(amt, style = T.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.on)
                            if (!eng.metered) {} else if (ok) Pill("Sufficient", PK.ON) else Pill("Needed")
                        }
                    }
                }
            }
            if (eng.pay != null) WaitMsg() else NoteText("Your balance updates by itself, usually within 2 minutes of sending.")
            Row { Btn(BK.TONAL, "Funding site", { act.go("funding") }, trail = "open_in_new") }
        }
    }
}

/* ---------- live traffic ---------- */
@Composable
fun TrafficPage(eng: Engine, act: Act, viz: Viz, mbps: Boolean, toggleUnits: () -> Unit) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        AppBar("Live traffic", { act.go("back") }) { Text("10 s avg", style = T.lbl, color = p.onV, modifier = Modifier.padding(end = 16.dp)) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TrafficViz(eng, viz, Modifier.fillMaxWidth(0.95f))
            val idle = !(eng.isOn() || eng.conn == Conn.CONNECTING)
            val k = if (mbps) 1.0 / 8 else 1.0
            fun fmt(v: Double) = String.format(java.util.Locale.ROOT, if (mbps) "%.2f" else "%.1f", v * k)
            val unit = if (mbps) "MB/s" else "Mbit/s"
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { toggleUnits() }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Triple("south", "Downstream", viz.down to viz.dpk), Triple("north", "Upstream", viz.up to viz.upk)).forEach { (ic, k2, v) ->
                    Card(Modifier.weight(1f)) { Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Ms(ic, size = 18.dp, tint = p.onV); Text(k2.uppercase(), fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, color = p.onV) }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(fmt(v.first), fontFamily = Dots, fontWeight = FontWeight.Black, fontSize = 38.sp, lineHeight = 38.sp, letterSpacing = (-1).sp, color = if (idle) p.onV else p.on)
                            Text(unit, style = T.bs, color = p.onV)
                        }
                        Text("peak ${fmt(v.second)}", style = T.mono.copy(fontSize = 11.sp), color = p.onV)
                    } }
                }
            }
            Text("Tap the numbers to switch between Mbit/s and MB/s.", style = T.bs, color = p.onV)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Legend(p.sync, "${viz.nRel} HOPR relays"); Legend(p.onV, "${viz.nVpn} via VPN"); Legend(p.warn, "${viz.nOth} not via VPN")
                Text("${viz.nRel + viz.nVpn + viz.nOth} endpoints", style = T.bs, color = p.onV)
                if (!eng.isOn() && eng.lockdown && !eng.ldOffPay) Text("Lockdown: nothing leaves while disconnected", style = T.bs, color = p.lav)
            }
        }
    }
}

@Composable
private fun Legend(c: Color, s: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(8.dp).background(c, CircleShape)); Text(s, style = T.bs, color = LocalPal.current.onV)
}

/* ---------- simulated Android VPN settings (demo engine only) ---------- */
@Composable
fun SysVpnPage(eng: Engine, act: Act, openReal: () -> Unit) {
    val p = LocalPal.current
    Screen(bg = p.sysBg, top = {
        Row(Modifier.fillMaxWidth().height(26.dp).background(p.surf), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Ms("android", size = 15.dp, tint = p.sync); Spacer(Modifier.width(6.dp))
            Text("ANDROID SYSTEM SETTINGS · SIMULATED", fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp, color = p.onV)
        }
        AppBar("Gnosis VPN", { act.go("back") }, bg = p.sysBg, titleStyle = T.hs.copy(fontSize = 24.sp, fontWeight = FontWeight.Normal))
    }, bottom = { Btn(BK.TONAL, "Back to Gnosis VPN", { act.go("back") }, size = BS.LG, icon = "arrow_back") }) {
        val hint = when (eng.sysPurpose) {
            "pay" -> if (eng.lockdown) "To pay, turn off **Block connections without VPN**, then go back to Gnosis VPN." else "Done. Go back to Gnosis VPN to pay."
            "lockOn" -> if (eng.lockdown) "Lockdown is on again. Go back to Gnosis VPN." else "Turn on **Always-on VPN** and **Block connections without VPN**, then go back."
            else -> null
        }
        if (hint != null) Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.pc).padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Ms("touch_app", size = 20.dp, tint = p.onPc); Text(rich(hint), style = T.bm, color = p.onPc)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.sysCard)) {
            Li("Always-on VPN", "Stay connected to VPN at all times", onClick = { act.go("sysAO") }, minH = 72.dp) { Sw(eng.alwaysOn, { act.go("sysAO") }) }
            Li("Block connections without VPN", "Only allow connections through this VPN", onClick = { act.go("sysBlock") }, enabled = eng.alwaysOn, divider = true, minH = 72.dp) { Sw(eng.lockdown, { act.go("sysBlock") }, enabled = eng.alwaysOn) }
            Li("Forget VPN", icon = "delete", enabled = false, divider = true, minH = 72.dp)
        }
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Text(rich("The demo simulates this screen. With the Gnosis backend, the app opens Android’s own VPN settings (Settings.ACTION_VPN_SETTINGS) and reads Always-on and Lockdown when you come back."), style = T.note, color = p.onV)
            Spacer(Modifier.height(8.dp))
            Btn(BK.TEXT, "Open Android’s VPN settings", openReal, trail = "open_in_new")
        }
    }
}

/* ---------- demo controls (debug builds) ---------- */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DemoPage(eng: Engine, act: Act, ui: UiState) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        AppBar("Demo controls", { act.go("back") }) { Box(Modifier.padding(end = 12.dp)) { Pill("Prototype", PK.PROTO) } }
        val tabs = listOf("scenario" to "Scenario", "ntf" to "Notifications")
        PrimaryTabRow(selectedTabIndex = if (ui.ctlTab == "ntf") 1 else 0, containerColor = p.bg, contentColor = p.primaryT, divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV)) }) {
            tabs.forEach { (v, l) -> Tab(selected = ui.ctlTab == v, onClick = { ui.ctlTab = v }, text = { Text(l, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }, selectedContentColor = p.primaryT, unselectedContentColor = p.onV) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { Pad {
            H3("Backend")
            val app = org.gnosisvpn.stub.App.instance
            Chips {
                Chip("Demo engine", eng.simulatesSystem) { app.useEngine("demo") }
                Chip("Gnosis VPN core", !eng.simulatesSystem) { if (!app.useEngine("gnosis")) ui.showSnack("Set up the Gnosis backend first: Settings → Developer screen.") }
            }
            Tiny("The demo engine simulates every state of the prototype. The Gnosis engine runs the real core; the scenario controls below only apply to the demo.")
            if (ui.ctlTab == "ntf") NtfLog(eng) else if (eng.simulatesSystem) Controls(eng, act, ui)
        } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(content: @Composable () -> Unit) = FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }

@Composable
private fun Chip(label: String, on: Boolean = false, code: String? = null, onClick: () -> Unit) {
    val p = LocalPal.current
    Row(
        Modifier.height(32.dp).clip(RoundedCornerShape(8.dp)).background(if (on) p.sc else Color.Transparent)
            .then(if (on) Modifier else Modifier.border(1.dp, p.outlineV, RoundedCornerShape(8.dp))).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (on) Ms("check", size = 16.dp, tint = p.onSc)
        if (code != null) Text(code, style = T.mono.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = if (on) p.onSc else p.onV)
        Text(label, fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (on) p.onSc else p.on)
    }
}

@Composable
private fun H3(s: String) = Text(s.uppercase(), fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp, color = LocalPal.current.onV, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun Tiny(s: String) = Text(s, style = T.bs, color = LocalPal.current.onV)

@Composable
private fun Controls(eng: Engine, act: Act, ui: UiState) {
    val p = LocalPal.current
    val (code, name) = eng.screenCode()
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surfLo).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("On screen", style = T.lbl, color = p.onV)
        Row { Text(code, style = T.mono.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = p.primaryT); Text(" $name", style = T.bm, fontWeight = FontWeight.SemiBold, color = p.on) }
    }
    H3("Jump to")
    Chips {
        Chip("Fund first", eng.gate, "I-1") { act.go("gateRestart") }; Chip("Syncing", eng.syncT != null, "I0") { act.go("restart") }; Chip("Failure", eng.fail != null, "E1") { act.go("failShow") }
        listOf("s00" to "Fresh start", "s01" to "Connected", "s07" to "Data low", "s08" to "No data left", "s10" to "Exit offline", "s13" to "Balance low", "s14" to "Balance empty",
            "s18c" to "Waiting for payment", "l1" to "Connection drops", "arc" to "Auto-renew check-in").forEach { (k, l) ->
            Chip(l, false, if (k == "arc") "S09" else k.uppercase()) { act.go("preset", "v" to k) }
        }
    }
    Tiny("Each jump keeps the notification log, so you can compare what fired.")
    H3("Settings")
    Chips {
        listOf("autoRenew" to "Auto-renew", "topup" to "Top-up message", "warm" to "Fresh launch (measuring)", "popover" to "Show data use before connecting", "lockdown" to "Lockdown", "startConnect" to "Connect at start").forEach { (k, l) ->
            val on = when (k) { "autoRenew" -> eng.autoRenew; "topup" -> eng.topup; "warm" -> eng.warm; "popover" -> eng.popover; "lockdown" -> eng.lockdown; else -> eng.startConnect }
            Chip(l, on) { act.go("tgl", "k" to k) }
        }
    }
    Chips { Chip(if (eng.arIntro) "Auto-renew intro: seen · show again" else "Auto-renew intro: armed", !eng.arIntro) { act.go("arReset") } }
    Tiny("The intro shows once per install, the next time 600 MB gets set aside with Auto-renew on and data use shown.")
    H3("Balance")
    Chips { listOf(4800.0, 1200.0, 600.0, 0.0).forEach { v -> Chip(mb(v), eng.balance == v) { act.go("bal", "v" to v.toInt().toString()) } } }
    H3("This session")
    Chips { listOf(600, 300, 120, 60, 20, 5).forEach { v -> Chip("$v MB") { act.go("sess", "v" to "$v") } } }
    Tiny("Sets what’s left of the current 600 MB at this exit (connects if needed). Runs out at $RATE MB/s, faster with ×10 / ×60. Turn Auto-renew off to see the prompts.")
    H3("Network events")
    Chips {
        Chip("Exit goes offline") { act.go("evOffline") }; Chip("Connection drops") { act.go("evDrop") }; Chip("Payment arrives") { act.go("evPay") }
        Chip("Update available") { act.go("evUpdate") }; Chip("Two messages") { act.go("evTwo") }
    }
    Chips { Chip("Next pick fills up", eng.nextFull) { act.go("tgl", "k" to "nextFull") }; Chip("Renewals fail", eng.failRenew) { act.go("tgl", "k" to "failRenew") } }
    H3("Time  ${eng.clock()}")
    Chips { listOf(1, 10, 60).forEach { v -> Chip("×$v", eng.speed == v) { act.go("speed", "v" to "$v") } }; Chip("+5 min") { act.go("skip") } }
    Tiny("No better-exit suggestions while connected. Auto refreshes the best location every minute while idle.")
    H3("Phone")
    Chips { listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (v, l) -> Chip(l, ui.mode == v) { ui.changeMode(v) } } }
    Chips { Chip("Detailed metrics", !eng.simple) { act.go("view") }; Chip("Screen codes on phone", ui.codes) { ui.codes = !ui.codes }; Chip("Rule notes in notifications", ui.rules) { ui.rules = !ui.rules } }
    Chips { Chip("Reset everything") { act.go("reset") }; Chip("Clear notification log") { act.go("clearLog") } }
}

@Composable
private fun NtfLog(eng: Engine) {
    val p = LocalPal.current
    Tiny("What Android shows, at the moment the app posts it, and the rule behind it. Pull down the status bar to see and use them.")
    if (eng.notes.isEmpty()) Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.surfLo).padding(12.dp)) {
        Text(rich("Nothing sent yet. With Auto-renew on and enough balance the app stays quiet. Try **Data low** or **Balance low** in Jump to, or run time at ×60."), style = T.bs, color = p.onV)
    }
    eng.notes.forEach { n ->
        val r = RULES.first { it.k == n.rule }; val code = n.code ?: r.code.split(" · ").last()
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (n.silent) Color.Transparent else p.surfLo)
            .then(if (n.silent) Modifier.border(1.dp, p.outlineV, RoundedCornerShape(12.dp)) else Modifier).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (n.silent) r.code else if (n.proposed) "Proposed" else code, style = T.mono.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = if (n.proposed) p.warn else p.primaryT)
                Text(fmtT(n.at), style = T.mono.copy(fontSize = 11.sp), color = p.onV)
                if (n.gone) Text("dismissed", style = T.bs, color = p.onV)
            }
            Text(if (n.silent) "No notification · ${n.title}" else n.title, fontFamily = Inter, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.on)
            Text(n.body, style = T.bs, color = p.on)
            if (!n.silent) Text(rich("**Why:** ${r.rule}"), style = T.bs, color = p.onV)
        }
    }
    H3("When notifications show up")
    RULES.forEach { r ->
        val keys = eng.fired.keys.filter { it.split(":")[0] == r.k }
        val (st, col) = when {
            (r.k == "u3" || r.k == "a3") && eng.autoRenew -> "Muted · Auto-renew on" to p.onV
            r.silent -> "Silent" to p.onV
            keys.isNotEmpty() -> "Sent ${fmtT(eng.fired[keys.last()]!!)}" to p.okT
            else -> "Armed" to p.primaryT
        }
        RuleRow(r.code, r.name, r.rule, st, col)
    }
    RuleRow("Ongoing", "VPN status, while not idle", "Android requires it for a VPN service. Carries Disconnect or the paused action.", if (eng.conn == Conn.IDLE) "Hidden" else "Shown", p.okT)
    RuleRow("In-app", "Lockdown reconnecting, better exit, renewal failed", "Main screen only. No system notification on the canvas.", "App only", p.onV)
}

@Composable
private fun RuleRow(code: String, name: String, rule: String, st: String, col: Color) {
    val p = LocalPal.current
    Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV))
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(code, style = T.mono.copy(fontSize = 10.sp), color = p.onV, modifier = Modifier.width(56.dp))
        Column(Modifier.weight(1f)) { Text(name, style = T.bs, color = p.on); Text(rule, fontFamily = Inter, fontSize = 11.sp, lineHeight = 14.sp, color = p.onV) }
        Text(st, fontFamily = Inter, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = col)
    }
}
