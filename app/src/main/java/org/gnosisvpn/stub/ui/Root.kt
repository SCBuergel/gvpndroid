package org.gnosisvpn.stub.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.gnosisvpn.stub.DebugLogRelay

/** UI-only state the engine does not need to know (U in the prototype). */
class UiState(initialMode: String, private val saveMode: (String) -> Unit) {
    var mode by mutableStateOf(initialMode)
        private set
    var codes by mutableStateOf(false)
    var dialog by mutableStateOf<String?>(null)
    var moreMsgs by mutableStateOf(false)
    var vizMb by mutableStateOf(false)
    var snack by mutableStateOf<String?>(null)
    var snackSeq by mutableIntStateOf(0)
    /** DEBUG ONLY. REMOVE BEFORE PRODUCTION: whether the developer log relay is on (DebugLogRelay). */
    var logRelayOn by mutableStateOf(false)
    fun changeMode(m: String) { mode = m; saveMode(m) }
    fun showSnack(msg: String) { snack = msg; snackSeq++ }
    /** Seq of the last snack handed to a snackbar host; this state outlives the activity. */
    private var shownSeq = 0
    /** The snack not yet shown, once: a recreated activity must not replay the last one. */
    fun takeSnack(): String? = snack?.takeIf { snackSeq != shownSeq }?.also { shownSeq = snackSeq }
}

/** Back: dialog → sheet → page; on the main screen it leaves the app (the system handles that). */
fun goBack(eng: Engine, ui: UiState) {
    if (eng.offConfirm) return eng.act("offCancel")
    if (ui.dialog != null) { ui.dialog = null; return }
    val sh = eng.visibleSheet()
    if (sh != null) { if (sh.type == "balance") { eng.sheet = null; eng.emit() } else eng.act("sheetNo"); return }
    if (eng.page() != null) eng.act("back")
}

fun canGoBack(eng: Engine, ui: UiState) = eng.offConfirm || ui.dialog != null || eng.visibleSheet() != null || eng.page() != null

@Composable
fun GnosisApp(eng: Engine, ui: UiState, debug: Boolean, extra: SettingsExtra, openRealVpnSettings: () -> Unit) {
    @Suppress("UNUSED_VARIABLE") val rev = eng.rev.intValue   // recompose on every engine change
    val act: Act = { a, d ->
        when {
            a == "ui:session" -> ui.dialog = "session"
            a == "ui:upload" -> ui.dialog = "upload"
            a == "ui:address" -> ui.dialog = "address"
            else -> eng.act(a, d)
        }
    }
    GnosisTheme(ui.mode) {
        val p = LocalPal.current
        Column(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        // DEBUG ONLY. REMOVE BEFORE PRODUCTION: says on every screen that the log goes to the developer.
        if (DebugLogRelay.available) DebugLogBar(ui)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val base = when { eng.fail != null -> "fail"; eng.gate -> "gate"; eng.syncT != null -> "sync"; else -> "home" }
            AnimatedContent(base, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(150)) }, label = "base") { b ->
                when (b) {
                    "fail" -> FailScreen(eng, act)
                    "gate" -> GateScreen(eng, act, debug)
                    "sync" -> SyncScreen(eng, act, debug)
                    else -> HomeScreen(eng, act, ui.moreMsgs) { ui.moreMsgs = !ui.moreMsgs }
                }
            }
            val pg = eng.page()
            AnimatedContent(pg, transitionSpec = {
                val push = targetState != null && (initialState == null || eng.stack.size > 1)
                if (push) (slideInHorizontally(tween(320)) { it * 3 / 10 } + fadeIn(tween(320))) togetherWith fadeOut(tween(160))
                else fadeIn(tween(220)) togetherWith (slideOutHorizontally(tween(220)) { it * 3 / 10 } + fadeOut(tween(220)))
            }, label = "page") { pgx ->
                when (pgx) {
                    null -> Box(Modifier)
                    "pick" -> PickPage(eng, act)
                    "settings" -> SettingsPage(eng, act, debug, extra, ui)
                    "add" -> AddPage(eng, act)
                    "payout" -> PayOutPage(eng, act)
                    // A fresh Viz per visit: its times (bwAt, nextChurn, relT0, last) are on the visit's own clock, which starts at 0.
                    "traffic" -> { val viz = remember { Viz() }; TrafficPage(eng, act, viz, ui.vizMb) { ui.vizMb = !ui.vizMb } }
                    else -> Box(Modifier)
                }
            }
            SheetHost(eng, act) { goBack(eng, ui) }
            DialogHost(eng, act, ui)
            val host = remember { SnackbarHostState() }
            LaunchedEffect(ui.snackSeq) { ui.takeSnack()?.let { host.currentSnackbarData?.dismiss(); host.showSnackbar(it) } }
            SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Snackbar(it, containerColor = p.inv, contentColor = p.onInv, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) }
            if (ui.codes) CodeBadge(eng, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            Box(Modifier.testTag("screen-code:${eng.screenCode().first}"))
        }
        }
        BackHandler(enabled = canGoBack(eng, ui)) { goBack(eng, ui) }
    }
}

/**
 * DEBUG ONLY. REMOVE BEFORE PRODUCTION. Debug builds send their log to the developer (encrypted,
 * every 30 s at first, on by default). This bar says so on every screen, from the first one, and turns it off.
 */
@Composable
private fun DebugLogBar(ui: UiState) {
    val p = LocalPal.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val set = { v: Boolean -> ui.logRelayOn = v; DebugLogRelay.setOn(ctx, v) }
    Row(Modifier.fillMaxWidth().background(if (ui.logRelayOn) p.lavc else p.sc).clickable { set(!ui.logRelayOn) }
        .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp).testTag("debug-log-bar"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Ms("bug_report", size = 18.dp, tint = p.on)
        Text(if (ui.logRelayOn) "Debug build: your log goes to the developer (encrypted), ${DebugLogRelay.SCHEDULE}. On by default."
             else "Debug build: log sending to the developer is off.",
            style = T.bs, color = p.on, modifier = Modifier.weight(1f))
        Sw(ui.logRelayOn, { set(!ui.logRelayOn) })
    }
}
