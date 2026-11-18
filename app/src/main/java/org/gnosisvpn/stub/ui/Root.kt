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
    fun changeMode(m: String) { mode = m; saveMode(m) }
    fun showSnack(msg: String) { snack = msg; snackSeq++ }
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
    val viz = remember { Viz() }
    GnosisTheme(ui.mode) {
        val p = LocalPal.current
        Box(Modifier.fillMaxSize().background(p.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
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
                    "settings" -> SettingsPage(eng, act, debug, extra)
                    "add" -> AddPage(eng, act)
                    "payout" -> PayOutPage(eng, act)
                    "traffic" -> { LaunchedEffect(Unit) { viz.eps.clear(); viz.c = null; viz.dpk = 0.0; viz.upk = 0.0 }; TrafficPage(eng, act, viz, ui.vizMb) { ui.vizMb = !ui.vizMb } }
                    else -> Box(Modifier)
                }
            }
            SheetHost(eng, act) { goBack(eng, ui) }
            DialogHost(eng, act, ui)
            val host = remember { SnackbarHostState() }
            LaunchedEffect(ui.snackSeq) { ui.snack?.let { host.currentSnackbarData?.dismiss(); host.showSnackbar(it) } }
            SnackbarHost(host, Modifier.align(Alignment.BottomCenter).padding(12.dp)) { Snackbar(it, containerColor = p.inv, contentColor = p.onInv, shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp)) }
            if (ui.codes) CodeBadge(eng, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            Box(Modifier.testTag("screen-code:${eng.screenCode().first}"))
        }
        BackHandler(enabled = canGoBack(eng, ui)) { goBack(eng, ui) }
    }
}
