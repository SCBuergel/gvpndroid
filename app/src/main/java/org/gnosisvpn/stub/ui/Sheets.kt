package org.gnosisvpn.stub.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.roundToInt

/** The sheet to show now, or null; a quota sheet waits while a page is open (as in the demo). */
fun Engine.visibleSheet(): Sheet? = sheet?.takeIf { !(it.type == "quota" && page() != null) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(eng: Engine, act: Act, onBack: () -> Unit) {
    val sh = eng.visibleSheet() ?: return
    val p = LocalPal.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onBack, sheetState = state, containerColor = p.surfLo, scrimColor = p.scrim, modifier = Modifier.testTag("sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SheetBody(eng, act, sh)
        }
    }
}

@Composable
private fun SheetButtons(vararg b: Triple<BK, String, () -> Unit>) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        b.forEach { (k, l, f) -> Btn(k, l, f, Modifier.weight(1f).height(48.dp)) }
    }
}

@Composable
private fun SheetLink(label: String, f: () -> Unit) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Btn(BK.TEXT, label, f) }

@Composable
private fun SheetBody(eng: Engine, act: Act, sh: Sheet) {
    val p = LocalPal.current
    when {
        sh.type == "balance" -> {
            Text("Balance", style = T.lbl, color = p.onV)
            Text(eng.balanceText(), style = T.big, color = p.on)
            Text(eng.usePhrase(), style = T.bm, color = p.onV)
            if (eng.left() > 0) Dl("Left at ${eng.ex(eng.loc).city}", "${eng.left().roundToInt()} MB", mono = true, divider = false)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn(BK.TEXT, "Usage details", { act.go("usage") }, Modifier.weight(1f).height(48.dp))
                Btn(BK.FILLED, "Add data", { act.go("addData") }, Modifier.weight(1f).height(48.dp), icon = "add")
            }
        }
        sh.type == "quota" && sh.check -> {
            CheckGraphic()
            Text("Four renewals done", style = T.hs, color = p.on)
            val rem = max(0.0, sh.until - eng.t)
            Text(rich("Auto-renew stops after four in a row unless you say so. Renew four more, or it pauses in **${(rem / 60).toInt()}:${p2(rem % 60)}**."), style = T.bm, color = if (rem < 60) p.err else p.onV)
            SheetButtons(Triple(BK.OUTLINED, "Pause") { act.go("arCheckNo") }, Triple(BK.FILLED, "Renew 4 more") { act.go("arCheckGo") })
            SheetLink("Always renew past 4") { act.go("arPastOn") }
        }
        sh.type == "quota" -> {
            val l = eng.ex(sh.id); val on = eng.isOn() || eng.conn == Conn.PAUSED
            QuotaGraphic(eng, sh.id)
            Text(if (sh.intro) "Auto-renew is on." else if (sh.soon) "Internet pauses soon." else if (sh.renew) "${eng.chunkTxt()} used up." else "${l.city} gets ${eng.chunkTxt()}.", style = T.hs, color = p.on)
            val sub = if (sh.intro) "Each exit gets ${eng.chunkTxt()}. Renews by itself." else if (sh.soon) "About ${dur(eng.minsLeft())} left here. Renew now to keep going." else if (sh.renew) "Renew to keep going." else ""
            if (sub.isNotEmpty()) Text(sub, style = T.bm, color = p.onV)
            if (!sh.renew) Leave(eng, sh.id)
            SheetButtons(Triple(BK.OUTLINED, if (sh.renew) "Not now" else "Cancel") { act.go("sheetNo") }, Triple(BK.FILLED, if (sh.renew) "Renew" else if (on) "Switch" else "Connect") { act.go("sheetGo") })
            if (!eng.autoRenewFixed) { if (sh.intro) SheetLink("Ask me each time") { act.go("arOffGo") } else SheetLink("Turn on Auto-renew") { act.go("arOnGo") } }
        }
        sh.type == "switch" -> {
            val e = eng.ex(sh.id)
            Text("Switch to ${e.city}?", style = T.hs, color = p.on)
            Text("Unlike traditional VPNs, Gnosis VPN sets aside data from your balance for each exit you use.", style = T.bm, color = p.onV)
            Flow(eng, listOf("Balance" to eng.balanceText(), e.city to "${eng.chunkTxt()}"), e.id)
            Leave(eng, e.id)
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { act.go("dont") }.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Checkbox(sh.dont, { act.go("dont") }, colors = CheckboxDefaults.colors(checkedColor = p.primary))
                Column { Text("Don’t ask again", style = T.bm, color = p.on); Text("Switch right away next time.", style = T.bs, color = p.onV) }
            }
            SheetButtons(Triple(BK.OUTLINED, "Cancel") { act.go("sheetNo") }, Triple(BK.FILLED, "Switch · ${eng.chunkTxt()}") { act.go("sheetGo") })
        }
        else -> { // nobal
            val e = eng.ex(sh.id); val cur = eng.ex(eng.loc)
            Ms("data_alert", fill = true, size = 32.dp, tint = p.err)
            Text("Not enough data to switch", style = T.hs, color = p.on)
            Text("Switching would set aside ${eng.chunkTxt()} for ${e.city}.", style = T.bm, color = p.onV)
            Flow(eng, listOf("Balance" to eng.balanceText(), "Needed" to "${eng.chunkTxt()}"), null, badFirst = true)
            Text("Your balance is ${if (eng.balance <= 0) "empty" else "too low"}. Add data first${if (eng.isOn()) ", or stay at ${cur.city}" else ""}.", style = T.bm, color = p.onV)
            SheetButtons(Triple(BK.OUTLINED, if (eng.isOn()) "Stay at ${cur.country}" else "Cancel") { act.go("stay") }, Triple(BK.FILLED, "Add data") { act.go("addData") })
        }
    }
}

@Composable
private fun Flow(eng: Engine, parts: List<Pair<String, String>>, iconId: String?, badFirst: Boolean = false) {
    val p = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.surfHi).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        parts.forEachIndexed { i, (k, v) ->
            if (i == 1 && iconId != null) { Ms("arrow_forward", tint = p.onV); XIcon(iconId, eng) }
            Column { Text(k, fontFamily = Inter, fontSize = 13.sp, color = p.onV); Text(v, style = T.mono.copy(fontSize = 15.sp), fontWeight = FontWeight.SemiBold, color = if (i == 0 && badFirst) p.err else p.on) }
        }
    }
}

/** "N MB stays at <exit> for …" when leaving an exit with data left (leaveHTML). */
@Composable
private fun Leave(eng: Engine, toId: String) {
    val p = LocalPal.current
    val a = eng.left(eng.loc)
    if (!eng.holdsData || !(a > 0) || toId == eng.loc || !(eng.isOn() || eng.conn == Conn.PAUSED)) return
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(p.sc).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ms("savings", size = 20.dp, tint = p.onSc)
        Text(rich("**${a.roundToInt()} MB** stays at ${eng.ex(eng.loc).city} for ${dur((eng.heldLeft(eng.loc) / 60).roundToInt())}."), style = T.bm, color = p.onSc)
    }
}

/* ---------- dialogs ---------- */
@Composable
fun DialogHost(eng: Engine, act: Act, ui: UiState) {
    val p = LocalPal.current
    @Composable fun DlgIcon(name: String, tint: Color = p.scIc) = Ms(name, size = 26.dp, tint = tint)
    @Composable fun TB(l: String, f: () -> Unit) = TextButton(f) { Text(l, fontFamily = Inter, fontWeight = FontWeight.SemiBold, color = p.primaryT) }
    if (eng.offConfirm) {
        AlertDialog(onDismissRequest = { act.go("offCancel") }, containerColor = p.surfHi,
            icon = { if (eng.lockdown) DlgIcon("lock") else DlgIcon("warning", p.warn) },
            title = { Text(if (eng.lockdown) "Lockdown on: traffic stays blocked" else "Your traffic will not be private", style = T.hs, textAlign = TextAlign.Center, color = p.on) },
            text = { Text(if (eng.lockdown) "Turning off the VPN won’t bring the internet back while Lockdown is on." else "Turning off sends everything outside Gnosis VPN.", style = T.bm, color = p.onV) },
            dismissButton = { TB("Cancel") { act.go("offCancel") } }, confirmButton = { TB("Turn off") { act.go("offDo") } })
        return
    }
    if (ui.dialog == "address") { AddressSheet(eng, act) { ui.dialog = null }; return }
    when (ui.dialog) {
        "upload" -> AlertDialog(onDismissRequest = { ui.dialog = null }, containerColor = p.surfHi, icon = { DlgIcon("upload") },
            title = { Text("Upload logs to support?", style = T.hs, textAlign = TextAlign.Center, color = p.on) },
            text = { Text("Included: app and connection logs of the last 24 hours, the app version and your phone model. Not included: the sites you visit or your VPN address.", style = T.bm, color = p.onV) },
            dismissButton = { TB("Cancel") { ui.dialog = null } }, confirmButton = { TB("Upload") { ui.dialog = null; act.go("logsUpload") } })
        "session" -> AlertDialog(onDismissRequest = { ui.dialog = null }, containerColor = p.surfHi, icon = { DlgIcon("schedule") },
            title = { Text("Time remaining", style = T.hs, textAlign = TextAlign.Center, color = p.on) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rows = eng.sessionRows()
                    if (eng.isOn() && rows != null) {
                        if (rows.isEmpty()) Text("Gnosis VPN reports the data left once the session is running.", style = T.bm, color = p.onV)
                        rows.forEachIndexed { i, (k, v) -> Dl(k, v, mono = true, divider = i < rows.size - 1) }
                    } else if (eng.isOn()) {
                        Dl("Data at this rate", eng.timeLeft() ?: "—", mono = true)
                        Dl("Session expiry", dur(max(0, ((10800 - eng.connSec) / 60).roundToInt())), mono = true, divider = false)
                        Text("Relay channels close after 3 h, because keeping them open costs data. Connecting again opens new ones.", style = T.bm, color = p.onV)
                    } else Text("Your session starts when you connect.", style = T.bm, color = p.onV)
                }
            },
            confirmButton = { TB("OK") { ui.dialog = null } })
    }
}

/** The VPN address to fund, as a QR code and text, with a link to it on the block explorer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressSheet(eng: Engine, act: Act, onClose: () -> Unit) {
    val p = LocalPal.current
    val addr = eng.address()
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onClose, sheetState = state, containerColor = p.surfLo, scrimColor = p.scrim, modifier = Modifier.testTag("address-sheet")) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Your VPN address", style = T.hs, color = p.on)
            if (addr.isEmpty()) { Text("Gnosis VPN shows your address once your node has started.", style = T.bm, color = p.onV); return@Column }
            val qr = remember(addr) { qrBitmap(addr) }
            Box(Modifier.align(Alignment.CenterHorizontally).size(208.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(10.dp)) {
                Image(qr, "QR code of your VPN address", Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
            Address(eng, act, share = true)
            eng.fundingAmounts()?.let { Text(rich("Send at least **$it**."), style = T.bm, color = p.on) }
            Text(rich("On **Gnosis Chain** only. Funds sent on other networks don’t arrive."), style = T.bs, color = p.onV)
            if (eng.explorerUrl().isNotEmpty()) Row { Btn(BK.TEXT, "View on GnosisScan", { act.go("explorer") }, trail = "open_in_new") }
        }
    }
}

/** The screen code overlay (Demo controls → Screen codes on phone). */
@Composable
fun CodeBadge(eng: Engine, modifier: Modifier) {
    val p = LocalPal.current
    val (code, name) = eng.screenCode()
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(p.inv.copy(alpha = .85f)).padding(horizontal = 8.dp, vertical = 3.dp).testTag("code")) {
        Text("$code $name", fontFamily = Inter, fontSize = 11.sp, color = p.onInv)
    }
}
