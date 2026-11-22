package org.gnosisvpn.stub.ui

import org.gnosisvpn.stub.DebugLogRelay
import androidx.compose.foundation.background
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
            Text(if (eng.exits.isEmpty()) "Looking for exits…" else if (eng.ptab == "recent") "Your last 6 exits" else "Each new exit sets aside ${eng.chunkTxt()}" + if (nFull > 0) " · $nFull full exits hidden" else "",
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
        eng.ptab == "rel" -> all.sortedWith(compareBy({ -it.pv }, { -it.fr }, { if (it.measured) it.ping else Int.MAX_VALUE }))
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
                    else { Btn(BK.TONAL, if (eng.isOn()) "Switch" else "Connect", { act.go("auto") }, size = BS.SM); if (eng.metered) Price("${eng.chunkTxt()}") }
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
                    eng.exitName(x)?.let { Text(it, style = T.bm, color = p.onV, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }
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
                    else -> { Btn(BK.TONAL, if (on) "Switch" else "Connect", { act.go("pick2", "id" to x.id) }, size = BS.SM); if (eng.metered) Price(if (a > 0) "${a.roundToInt()} MB left" else if (eng.free && !on) "free" else "${eng.chunkTxt()}") }
                }
            }
        }
        if (open) RowStats(eng, x)
        val fm = eng.fullMsg
        if (fm != null && fm.id == x.id) Box(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
            MsgCard(Msg(Tone.WARN, "block", "${x.city} just filled up.", fm.alt?.let { "Next most reliable: ${eng.ex(it).city}." } ?: "",
                fm.alt?.let { listOf("${if (on) "Switch" else "Connect"} to ${eng.ex(it).country} · ${eng.chunkTxt()}" to "pick2:$it") }), { a2, d -> if (a2.startsWith("pick2:")) act.go("pick2", "id" to a2.substringAfter(":")) else act(a2, d) })
        }
    }
}

@Composable
private fun HoldPill(eng: Engine, id: String) {
    val p = LocalPal.current
    val a = eng.left(id); if (!eng.holdsData || !(a > 0) || id == eng.loc) return
    val m = (eng.heldLeft(id) / 60).roundToInt()
    Tip("Data you left here. It stays for ${dur(m)}, then it is gone.") {
        Row(Modifier.height(20.dp).clip(RoundedCornerShape(10.dp)).background(p.sc).padding(start = 5.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val f = (a / eng.chunk).coerceIn(0.0, 1.0).toFloat()
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
    "Path" to "This exit's best path through the mixnet, as a share of the best exit's path right now.",
    "Load" to "How busy the exit is: used slots out of all slots.", "Cost" to "Data set aside from your balance when you connect here.")

@Composable
private fun RowStats(eng: Engine, e: Exit) {
    val p = LocalPal.current
    val a = eng.left(e.id); val m = e.measured; val load = eng.serverLoad(e)
    val items = listOf("Latency" to (if (m) "${e.ping.grouped()} ms" else "—"), "Checked" to ago(e.checkedAt), "Path" to (if (e.pv > 0) "${(e.pv * 100).roundToInt()}%" else "—"),
        "Load" to (if (load != null) "$load%" else "—"), "Cost" to (if (a > 0) "${a.roundToInt()} MB left" else eng.chunkTxt()))
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
fun SettingsPage(eng: Engine, act: Act, debug: Boolean, extra: SettingsExtra, ui: UiState) {
    val p = LocalPal.current
    Column(Modifier.fillMaxSize().background(p.bg)) {
        AppBar("Settings", { act.go("back") })
        val tabs = listOf("general" to "General", "usage" to "Usage")
        PrimaryTabRow(selectedTabIndex = tabs.indexOfFirst { it.first == eng.stab }.coerceAtLeast(0), containerColor = p.bg, contentColor = p.primaryT, divider = { Box(Modifier.fillMaxWidth().height(1.dp).background(p.outlineV)) }) {
            tabs.forEach { (v, l) -> Tab(selected = eng.stab == v, onClick = { act.go("tab", "v" to v) }, text = { Text(l, fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp) }, selectedContentColor = p.primaryT, unselectedContentColor = p.onV) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Pad { if (eng.stab == "general") SettingsGeneral(eng, act, debug, extra, ui) else SettingsUsage(eng, act) }
        }
    }
}

/** Rows the activity adds to Settings (real app: identity export, developer screen). */
class SettingsExtra(val rows: List<Triple<String, String, String>> = emptyList(), val version: String = "", val onRow: (String) -> Unit = {})

@Composable
private fun SettingsGeneral(eng: Engine, act: Act, debug: Boolean, extra: SettingsExtra, ui: UiState) {
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
        Li("Hide data use before connecting", "Connect goes straight ahead instead of first showing the ${eng.chunkTxt()} an exit takes.", "visibility_off", { act.go("togHide") }) { Sw(!eng.popover, { act.go("togHide") }) }
        if (eng.autoRenewFixed) Li("Renews automatically", "Gnosis VPN buys the next ${eng.chunkTxt()} by itself when an exit runs low, from your balance.", "autorenew", divider = true)
        else {
            Li("Auto-renew sessions", "Adds the next ${eng.chunkTxt()} by itself when an exit runs low, without asking.", "autorenew", { act.go("togAR") }, divider = true) { Sw(eng.autoRenew, { act.go("togAR") }) }
            Li("Auto-renew past 4 cycles", "After four renewals in a row, keeps going without checking in with you.", "all_inclusive", { act.go("togP4") }, enabled = eng.autoRenew, divider = true) { Sw(eng.arPast4, { act.go("togP4") }, enabled = eng.autoRenew) }
        }
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
        if (eng.canUploadLogs) Li("Upload logs", "Sent to support. Check what’s included first.", "upload", divider = true) { Btn(BK.TEXT, "Upload", { act.go("ui:upload") }, size = BS.SM) }
        // DEBUG ONLY. REMOVE BEFORE PRODUCTION: the developer log relay (DebugLogRelay).
        if (DebugLogRelay.available) {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val flip = { ui.logRelayOn = !ui.logRelayOn; DebugLogRelay.setOn(ctx, ui.logRelayOn) }
            Li("Send log to the developer", "Debug builds only. Encrypted, ${DebugLogRelay.SCHEDULE}, to the developer’s relay. On by default.", "bug_report", flip, divider = true) { Sw(ui.logRelayOn, flip) }
        }
    }
    if (extra.rows.isNotEmpty()) {
        Text("Node", style = T.sh, color = p.primaryT, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
        ListGroup { extra.rows.forEachIndexed { i, (k, title, sub) -> Li(title, sub, if (k == "dev") "developer_mode" else "badge", { extra.onRow(k) }, divider = i > 0) { Ms("chevron_right", tint = p.onV) } } }
    }
    NoteText(extra.version, center = true)
}

@Composable
private fun SettingsUsage(eng: Engine, act: Act) {
    val p = LocalPal.current
    Card { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Available balance", style = T.lbl, color = p.onV)
        Text(eng.balanceText(), style = T.big, color = p.on)
        Text(eng.usePhrase(), style = T.bm, color = p.onV)
        eng.fundingNeeds().forEach { (tok, what, _) -> if (what.contains("you have")) Dl(tok, what.substringAfter("you have ").trim() + " " + tok, mono = true, divider = false) }
    } }
    if (eng.balanceKnown && eng.balance <= eng.chunk) MsgCard(Msg(if (eng.balance < eng.chunk) Tone.BAD else Tone.WARN, "warning", if (eng.balance < eng.chunk) "Balance empty:" else "Balance running low:", if (eng.balance < eng.chunk) "no renewals left." else "1 renewal left."), act)
    ListGroup {
        Li("Open funding site", "Pay with crypto from a wallet", "account_balance_wallet", { act.go("funding") }) { Ms("open_in_new", tint = p.onV) }
        Li("Your VPN address", "QR code, copy, GnosisScan", "qr_code_2", { act.go("ui:address") }, divider = true) { Ms("chevron_right", tint = p.onV) }
    }
    if (eng.isOn() || eng.left() > 0) {
        SectionHead("This exit")
        PadCard { Usage(eng, eng.ex(eng.loc).city, act) }
    }
}

/* ---------- S18: add data ---------- */
@Composable
fun AddPage(eng: Engine, act: Act) {
    Screen(top = { AppBar("Fund your VPN", { act.go("back") }) }, bottom = { FundButtons(act, "funding") }) {
        val p = LocalPal.current
        Pad {
            PadCard { Text("Pay with crypto", style = T.tm, color = p.on); Text("Top up your balance with crypto you have left in a wallet. The funding site walks you through it.", style = T.bm, color = p.onV) }
            if (eng.pay != null) WaitMsg() else NoteText("Your balance updates by itself a few minutes after paying.")
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
                eng.sessionTotals()?.let { (r, s2) -> Legend(p.sync, "${mb(r / 1e6)} received"); Legend(p.onV, "${mb(s2 / 1e6)} sent") }
                    ?: Text("Traffic shows here while you are connected.", style = T.bs, color = p.onV)
                if (!eng.isOn() && eng.lockdown && !eng.ldOffPay) Text("Lockdown: nothing leaves while disconnected", style = T.bs, color = p.lav)
            }
        }
    }
}

@Composable
private fun Legend(c: Color, s: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(8.dp).background(c, CircleShape)); Text(s, style = T.bs, color = LocalPal.current.onV)
}

/** "12 s ago", "3 min ago", or "—". */
fun ago(atMs: Long?): String {
    if (atMs == null) return "—"
    val s = ((System.currentTimeMillis() - atMs) / 1000).coerceAtLeast(0)
    return if (s < 90) "$s s ago" else if (s < 5400) "${s / 60} min ago" else "${s / 3600} h ago"
}
