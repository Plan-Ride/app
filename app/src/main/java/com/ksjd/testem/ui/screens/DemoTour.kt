package com.ksjd.testem.ui.screens

import android.app.Activity
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.Euro
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ksjd.testem.AppTab
import com.ksjd.testem.AppViewModel
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.Demo
import com.ksjd.testem.QRCodeGenerator
import com.ksjd.testem.R
import com.ksjd.testem.Wallet
import com.ksjd.testem.live.LiveViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private data class Caption(val title: String, val body: String, val ms: Long, val key: Int)
/** The caption showing now; also drawn over the trip map, which is a window of its own. */
private val captionNow = kotlinx.coroutines.flow.MutableStateFlow<Caption?>(null)

/** The demo strip for another window (the trip map), so the tour's caption stays readable there. */
@Composable
fun DemoStripOver() {
    if (!Demo.on) return
    val c = Demo.control.collectAsState().value
    DemoStrip(captionNow.collectAsState().value, Demo.touring.collectAsState().value, c.message.takeIf { it.isNotBlank() && c.messageStyle == "strip" })
}
private enum class Slide { Intro, Transfer, OwnJourney, Alerts, Watch, Values, Slovakia, Count, Try, Message }

/**
 * The open day phone (Demo.kt): the app under a strip that says it is a demo and holds the tour's captions, so they
 * never cover the app. The tour goes through the app by itself; a touch ends it; a minute without one starts it all
 * again ([onReset]) for the next visitor, on any screen.
 */
@Composable
fun DemoFrame(viewModel: AppViewModel, live: LiveViewModel, onReset: () -> Unit, content: @Composable () -> Unit) {
    val activity = LocalContext.current as Activity
    val touring by Demo.touring.collectAsState()
    val caption by captionNow.collectAsState()
    var slide by remember { mutableStateOf<Slide?>(null) }

    val control by Demo.control.collectAsState()
    val resetAsked by Demo.resetAsked.collectAsState()

    LaunchedEffect(Unit) {
        Demo.connect(activity.applicationContext)
        if (!Demo.tourStarted) {
            Demo.tourStarted = true
            delay(1_200)
            Demo.lastTouch = SystemClock.elapsedRealtime()
            Demo.resetDone = Demo.lastTouch
            Demo.touring.value = Demo.control.value.mode != "off"
        }
        while (true) {
            Demo.watchWindows()
            Demo.applyKiosk(activity)
            if (Demo.removeAsk.value) Demo.remove(activity)
            val c = Demo.control.value
            val idle = SystemClock.elapsedRealtime() - Demo.lastTouch > c.idleMs
            // A visitor left something to wipe, or the tour should be running and isn't (also when "No tour" ends).
            if (idle && (Demo.lastTouch > Demo.resetDone || (c.mode != "off" && !Demo.touring.value))) onReset()
            delay(500)
        }
    }
    // The dashboard's Reset (for all phones or this one).
    LaunchedEffect(resetAsked) { if (resetAsked) { Demo.resetAsked.value = false; onReset() } }
    // emHUB knows at once when a visitor takes over or the tour comes back.
    LaunchedEffect(touring) { if (!touring) Demo.stepNow = ""; Demo.sendState() }

    // Only what the tour itself depends on starts it again: the control also carries today's search count, the kiosk and
    // the PIN, and each change of those restarted the tour mid-way (the intro showed after the planner or the watch).
    val tourKey = control.run { listOf(mode, hold, epoch, steps, offsetMs, message, messageStyle) }
    LaunchedEffect(touring, tourKey) {
        if (control.mode == "off") Demo.touring.value = false
        if (!touring || control.mode == "off") { captionNow.value = null; slide = null; return@LaunchedEffect }
        if (control.message.isNotBlank() && control.messageStyle == "slide") {
            // The dashboard's message, full screen, while nobody uses the phone (after the tour step being left has ended).
            delay(50)
            captionNow.value = null
            slide = Slide.Message
            Demo.stepNow = "Správa"
            Demo.sendState()
            return@LaunchedEffect
        }
        tour(activity, viewModel, live, control, { captionNow.value = it }, { slide = it })
    }
    // The dashboard's Blink: the screen flashes with this phone's name, to find it among the others.
    val blinkAt by Demo.blink.collectAsState()
    var blinking by remember { mutableStateOf(false) }
    // Only a fresh Blink: a reset rebuilds this frame and the old one must not flash again.
    LaunchedEffect(blinkAt) {
        val left = blinkAt + 4_000 - System.currentTimeMillis()
        if (blinkAt > 0 && left > 0) { blinking = true; delay(left); blinking = false }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            DemoStrip(caption, touring, control.message.takeIf { it.isNotBlank() && control.messageStyle == "strip" }, Demo.lockWanted(control))
            Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.statusBars)) {
                content()
                // An open trip's sheet doesn't shade the screen in the demo (it would shade this strip too): the app is shaded here.
                val tripOpen = live.state.collectAsState().value.trip != null
                val shade by animateFloatAsState(if (tripOpen) 0.32f else 0f, tween(300), label = "shade")
                if (shade > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade)))
            }
        }
        // Slides one after another keep their background; only the content turns over, like pages.
        var lastSlide by remember { mutableStateOf(Slide.Intro) }
        slide?.let { lastSlide = it }
        AnimatedVisibility(slide != null, enter = fadeIn(tween(450)), exit = fadeOut(tween(400)), modifier = Modifier.fillMaxSize()) {
            val scheme = MaterialTheme.colorScheme
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(lerpTo(scheme.primary, Color.Black, 0.35f), scheme.primary)))) {
                AnimatedContent(
                    lastSlide,
                    label = "slide",
                    transitionSpec = {
                        (fadeIn(tween(500, delayMillis = 150)) + slideInHorizontally(tween(550)) { it / 3 }) togetherWith
                            (fadeOut(tween(300)) + androidx.compose.animation.slideOutHorizontally(tween(450)) { -it / 3 })
                    },
                    modifier = Modifier.fillMaxSize()
                ) { s -> SlideScreen(s) }
            }
        }
        val locked = Demo.lockWanted(control)
        // Five taps in the top left corner: the PIN unlocks this phone (also without emHUB: its hash is kept).
        if (Demo.unlockAsk.collectAsState().value) PinDialog(activity)
        // A touch the lock swallowed: say why nothing happens.
        val tappedAt by Demo.lockedTap.collectAsState()
        var lockNote by remember { mutableStateOf(false) }
        LaunchedEffect(tappedAt) { if (tappedAt > 0) { lockNote = true; delay(2_500); lockNote = false } }
        AnimatedVisibility(lockNote, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut(), modifier = Modifier.align(Alignment.Center)) {
            Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.inverseSurface, contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 8.dp) {
                Row(Modifier.padding(horizontal = 22.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.VisibilityOff, null, modifier = Modifier.size(22.dp))
                    Text("Ukážka, len na pozeranie", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 10.dp))
                }
            }
        }
        // While the tour is in the app (not on a slide): now and then a hand says the phone may be touched.
        var hint by remember { mutableStateOf(false) }
        LaunchedEffect(touring, slide == null, locked) {
            hint = false
            if (!touring || slide != null || locked) return@LaunchedEffect
            while (true) { delay(9_000); hint = true; delay(3_500); hint = false }
        }
        AnimatedVisibility(hint, enter = fadeIn(tween(500)) + scaleIn(initialScale = 0.7f), exit = fadeOut(tween(500)),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp)) {
            val beat by rememberInfiniteTransition(label = "hint").animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "b")
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.tertiary, contentColor = MaterialTheme.colorScheme.onTertiary,
                shadowElevation = 10.dp, modifier = Modifier.scale(beat)) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.TouchApp, null, modifier = Modifier.size(26.dp))
                    Text("Dotknite sa a skúste", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
        if (blinking) {
            val flash by rememberInfiniteTransition(label = "blink").animateFloat(0f, 1f, infiniteRepeatable(tween(350), RepeatMode.Reverse), label = "f")
            val scheme = MaterialTheme.colorScheme
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.lerp(scheme.primary, Color.White, flash)), contentAlignment = Alignment.Center) {
                // Long names ("TAB S6 Lite pouzivatela Michal") wrap: smaller, with a line height to match (they overlapped).
                val name = control.name.ifBlank { android.os.Build.MODEL }
                val size = when { name.length <= 12 -> 56; name.length <= 24 -> 44; else -> 34 }
                Text(name, fontSize = size.sp, lineHeight = (size * 1.2f).sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center,
                    color = androidx.compose.ui.graphics.lerp(Color.White, scheme.primary, flash), modifier = Modifier.padding(24.dp))
            }
        }
    }
}

/** One step of the tour: its own time slot, so every phone shows it at the same moment. [join]: a phone may start here. */
private class Step(val id: String, val name: String, val ms: Long, val join: Boolean = true, val run: suspend () -> Unit)

/**
 * The tour, round and round, on emHUB's clock: every phone works out from the tour's start ([Demo.Control.epoch]) which
 * step is on now and joins at the next one it can start at (the intro shows until then), so the phones nobody touches
 * show the same thing. "hold": emHUB keeps one step on screen, over and over.
 */
private suspend fun tour(
    activity: Activity,
    viewModel: AppViewModel,
    live: LiveViewModel,
    control: Demo.Control,
    setCaption: (Caption?) -> Unit,
    setSlide: (Slide?) -> Unit
) {
    val theme = com.ksjd.testem.DefaultThemePresets.all.first().id
    if (viewModel.appState.value.selectedThemeId != theme) viewModel.selectThemePreset(theme)
    val prefs = CredentialsManager(activity)
    var n = 0
    fun caption(title: String, body: String, ms: Long) = setCaption(Caption(title, body, ms, n++))
    fun show(s: Slide) { setCaption(null); setSlide(s) }
    suspend fun app() { setSlide(null); delay(400) }
    suspend fun type(text: String, set: (String) -> Unit) { for (i in 1..text.length) { set(text.take(i)); delay(110) } }
    suspend fun <T> waitFor(ms: Long, get: () -> T?): T? = withTimeoutOrNull(ms) {
        var value = get()
        while (value == null) { delay(150); value = get() }
        value
    }
    fun cleanUp() {
        Demo.tripMap.value = false
        live.closeTrip()
        live.closeStop()
    }
    val allSteps = listOf(
        Step("intro", "Úvod", 5_500) { show(Slide.Intro) },
        Step("ticket", "Lístok", 5_000) {
            app(); cleanUp(); Demo.tab.value = AppTab.Home
            caption("Lístok vždy po ruke", "QR kód sa sám obnovuje každých pár sekúnd. Kredit vidíte vpravo hore.", 4_600)
        },
        Step("tickets", "Dva lístky", 6_000) {
            app(); Demo.tab.value = AppTab.Home
            caption("Dva účty, dva lístky", "SAD Zvolen aj IDS BBSK. Lístok prepnete potiahnutím do strany.", 5_600)
            delay(1_000); viewModel.setTicketDefault(Wallet.IDS)
            delay(3_500); viewModel.setTicketDefault(Wallet.SADZV)
        },
        Step("follow", "Sledovanie autobusu", 17_000) {
            app(); Demo.tab.value = AppTab.Home
            caption("Sledovanie autobusu",
                "Farba karty ukazuje meškanie: teplejšia, keď autobus mešká, chladnejšia, keď ide skôr. Lístok sa prepne sám podľa linky.", 16_500)
            Demo.simulateFollow(activity, 1_800)
        },
        Step("departures", "Odchody naživo", 15_000) {
            app(); cleanUp(); Demo.tab.value = AppTab.Departures
            caption("Odchody naživo", "Nájdite zastávku a uvidíte, kedy autobus naozaj príde, aj s meškaním.", 14_500)
            // Banská Bystrica's railway station: intercity buses with live positions.
            type("Železničná", live::search)
            waitFor(5_000) { live.state.value.results.let { r -> r.firstOrNull { "Bystrica" in it.name } ?: r.firstOrNull() } }?.let { live.openStop(it) }
        },
        Step("trip", "Celá cesta autobusu", 5_500, join = false) {
            live.state.value.board?.departures?.let { d -> d.firstOrNull { it.isRealtime } ?: d.firstOrNull() }?.let(live::openTrip)
            caption("Celá cesta autobusu", "Všetky zastávky, kde je autobus teraz a o koľko mešká.", 5_000)
        },
        Step("map", "Mapa cesty", 7_500, join = false) {
            if (waitFor(2_000) { live.state.value.trip?.detail } != null) {
                Demo.tripMap.value = true
                caption("Podrobná mapa", "Celá trasa po cestách aj poloha autobusu na mape.", 7_000)
            }
        },
        Step("planner", "Plánovač ciest", 16_000) {
            app(); cleanUp(); live.search("")
            Demo.tab.value = AppTab.Planner
            Demo.plannerScroll.value = "top"
            caption("Plánovač ciest", "Autobusy aj vlaky po celom Slovensku, aj s prestupmi.", 15_500)
            viewModel.setTimetableTime("")
            viewModel.setTimetableDate("")
            type("Zvolen", viewModel::updateTimetableFromInput)
            waitFor(3_000) { viewModel.timetableState.value.fromSuggestions.firstOrNull() }?.let(viewModel::selectTimetableFromSuggestion)
            type("Banská Bystrica", viewModel::updateTimetableToInput)
            waitFor(3_000) { viewModel.timetableState.value.toSuggestions.firstOrNull() }?.let(viewModel::selectTimetableToSuggestion)
            viewModel.loadTimetables()
        },
        Step("price", "Cena a kredit", 6_500, join = false) {
            Demo.plannerScroll.value = "results"
            caption("Cena a kredit", "Pri spojení vidíte cenu s vašou kartou aj to, či vám kredit stačí.", 6_000)
        },
        Step("places", "Domov a práca", 7_000, join = false) {
            // The two towns become the demo's home and work: the planner's buttons fill in.
            viewModel.timetableState.value.let { s ->
                s.selectedFromSuggestion?.let { prefs.savePlace("home", it) }
                s.selectedToSuggestion?.let { prefs.savePlace("work", it) }
            }
            Demo.places.value++
            Demo.plannerScroll.value = "top"
            caption("Domov a práca", "Uložte si domov a prácu. Jedno ťuknutie nájde cestu z najbližšej zastávky.", 6_500)
            delay(2_500)
            prefs.getPlace("home")?.let { viewModel.takeMeTo(it) {} }
        },
        Step("transfer", "Vlastný prestup", 7_500) { show(Slide.Transfer) },
        Step("journey", "Vlastná cesta", 7_500) { show(Slide.OwnJourney) },
        Step("history", "História jázd", 6_000) {
            app(); Demo.tab.value = AppTab.History
            caption("História jázd", "Kde ste cestovali, koľko ste minuli a kedy ste dobili kredit.", 5_500)
        },
        Step("alerts", "Upozornenia", 6_500) { show(Slide.Alerts) },
        Step("colors", "Vaše farby", 9_000) {
            app(); Demo.tab.value = AppTab.Account
            caption("Vaše farby", "Vyberte si tému alebo si nastavte vlastné farby celej aplikácie.", 8_500)
            viewModel.appState.value.themePresets.filter { it.id != theme }.take(4).forEach {
                viewModel.selectThemePreset(it.id)
                delay(1_600)
            }
            viewModel.selectThemePreset(theme)
        },
        Step("watch", "Hodinky", 8_000) { show(Slide.Watch) },
        Step("values", "Bez háčikov", 7_000) { show(Slide.Values) },
        Step("slovakia", "Celé Slovensko", 8_000) { show(Slide.Slovakia) },
        Step("count", "Dnes v Plan&Ride", 6_000) { show(Slide.Count) },
        Step("try", "Google Play QR kód", 9_000) { show(Slide.Try) },
    )
    // Groups: a step a phone can start at, with the ones after it that need it (the trip and its map go with the departures).
    val groups = mutableListOf<MutableList<Step>>()
    allSteps.forEach { if (it.join || groups.isEmpty()) groups.add(mutableListOf(it)) else groups.last().add(it) }
    Demo.steps = groups.map { it[0].id to it[0].name }
    // The dashboard's Tour content: which groups, in which order (none chosen: all, in this order).
    val steps = control.steps.mapNotNull { id -> groups.firstOrNull { it[0].id == id } }.ifEmpty { groups }.flatten()
    suspend fun play(step: Step, endsAt: Long) {
        Demo.stepNow = step.name
        Demo.lastStep = step.name
        Demo.sendState()
        withTimeoutOrNull((endsAt - Demo.now() - 200).coerceAtLeast(0)) { step.run() }
        delay((endsAt - Demo.now()).coerceAtLeast(0))
    }
    try {
        if (control.mode == "hold") {
            val step = allSteps.firstOrNull { it.id == control.hold } ?: allSteps.last()
            while (true) play(step, Demo.now() + step.ms)
        }
        val cycle = steps.sumOf { it.ms }
        val starts = steps.runningFold(0L) { at, s -> at + s.ms }
        // The wave: each phone [Demo.Control.offsetMs] later than the tour's own clock.
        val into = Math.floorMod(Demo.now() - control.epoch - control.offsetMs, cycle)
        var k = starts.indexOfLast { it <= into }.coerceAtMost(steps.lastIndex)
        var at = Demo.now() - into + starts[k]
        // Join at the next step a phone can start at; the intro shows meanwhile.
        do { at += steps[k].ms; k = (k + 1) % steps.size } while (!steps[k].join)
        show(Slide.Intro)
        delay((at - Demo.now()).coerceAtLeast(0))
        while (true) {
            play(steps[k], at + steps[k].ms)
            at += steps[k].ms
            k = (k + 1) % steps.size
        }
    } finally {
        setCaption(null)
        setSlide(null)
        com.ksjd.testem.live.TripTracking.demoShow(null, null)
        Demo.tripMap.value = false
        if (viewModel.appState.value.selectedThemeId != theme) viewModel.selectThemePreset(theme)
        if (viewModel.appState.value.ticketDefault != Wallet.SADZV) viewModel.setTicketDefault(Wallet.SADZV)
    }
}

/** Always says it is a demo; during the tour it holds the caption, pushing the app down instead of covering it. */
@Composable
private fun DemoStrip(caption: Caption?, touring: Boolean, message: String? = null, locked: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 6.dp).animateContentSize(spring(stiffness = Spring.StiffnessLow))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pulse by rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(scheme.tertiary))
            Text("Ukážková verzia", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = scheme.tertiary, modifier = Modifier.padding(start = 8.dp))
            Text(
                if (locked) "  Len na pozeranie" else if (touring) "  Dotknite sa a vyskúšajte si to sami" else "  Údaje sú vymyslené, nič sa neodosiela",
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1
            )
        }
        // The dashboard's message for the strip: also while a visitor uses the phone.
        AnimatedVisibility(message != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Surface(color = scheme.tertiaryContainer, contentColor = scheme.onTertiaryContainer, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.padding(top = 6.dp).fillMaxWidth()) {
                Text(message.orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        AnimatedVisibility(caption != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            AnimatedContent(caption, label = "caption", transitionSpec = { fadeIn(tween(350)) togetherWith fadeOut(tween(200)) }) { c ->
                if (c != null) CaptionCard(c)
            }
        }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun CaptionCard(c: Caption) {
    val progress = remember(c.key) { Animatable(0f) }
    LaunchedEffect(c.key) { progress.animateTo(1f, tween(c.ms.toInt(), easing = LinearEasing)) }
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text(c.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(c.body, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            LinearProgressIndicator(
                progress = { progress.value },
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth().height(3.dp).clip(CircleShape),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.12f)
            )
        }
    }
}

@Composable
private fun SlideScreen(s: Slide) {
    val scheme = MaterialTheme.colorScheme
    val bob by rememberInfiniteTransition(label = "float").animateFloat(0f, 1f, infiniteRepeatable(tween(1_600), RepeatMode.Reverse), label = "bob")
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.systemBarsPadding().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            val on = Color.White
            @Composable fun Title(text: String) = Text(text, color = on, fontSize = 34.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 38.sp)
            @Composable fun Body(text: String) = Text(text, color = on.copy(alpha = 0.92f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            when (s) {
                Slide.Intro -> {
                    Box(Modifier.offset(y = (-10 * bob).dp).size(150.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(200.dp))
                    }
                    Text("Plan&Ride", color = on, fontSize = 46.sp, fontWeight = FontWeight.Black)
                    Body("Autobusy a vlaky na Slovensku v jednej aplikácii")
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        StaggeredFeature(0, Icons.Outlined.Schedule, "Odchody naživo, s meškaním", on)
                        StaggeredFeature(1, Icons.Outlined.Train, "Plánovač pre autobusy aj vlaky", on)
                        StaggeredFeature(2, Icons.Outlined.QrCode2, "Lístok a kredit v mobile", on)
                    }
                }
                Slide.Transfer -> {
                    Title("Prestúpte, kde chcete vy")
                    TransferMockup()
                    Body("Ťuknite na inú zastávku prestupu a zvyšok cesty sa prepočíta.")
                }
                Slide.OwnJourney -> {
                    Title("Vlastná cesta")
                    OwnJourneyMockup()
                    Body("Poskladajte si cestu spoj po spoji priamo z tabúľ odchodov a uložte si ju na domovskú obrazovku.")
                }
                Slide.Alerts -> {
                    Title("Upozorní vás včas")
                    NotificationMockup()
                    Body("Keď autobus prichádza aj keď máte vystúpiť. Pravidelné jazdy začne sledovať sama.")
                }
                Slide.Watch -> {
                    Title("Z mobilu na hodinky")
                    WatchHandoff()
                    Body("Sledovaný autobus prejde na hodinky s Wear OS. Keď máte vystúpiť, zápästie vám to pripomenie.")
                }
                Slide.Slovakia -> {
                    val c = Demo.control.collectAsState().value
                    Title("Celé Slovensko")
                    SlovakiaMap()
                    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        if (c.operators > 0) CountUp(c.operators, "dopravcov", on)
                        if (c.stops > 0) CountUp(c.stops, "zastávok", on)
                    }
                    Body("Autobusy, mestská doprava aj vlaky v jednom plánovači.")
                }
                Slide.Count -> {
                    val c = Demo.control.collectAsState().value
                    // Few searches yet (the morning): the stops instead, a number that always impresses.
                    if (c.searchesToday >= 20) {
                        Title("Dnes")
                        CountUp(c.searchesToday, "ciest už ľudia dnes naplánovali v Plan&Ride", on, big = true)
                    } else {
                        Title("Kamkoľvek")
                        CountUp(c.stops.coerceAtLeast(1), "zastávok, z ktorých vás Plan&Ride navedie", on, big = true)
                    }
                    Body("Odchody, spojenia aj meškania, každý deň čerstvé.")
                }
                Slide.Values -> {
                    Title("Pre všetkých, bez háčikov")
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        ValueBadge(0, Icons.Outlined.Block, "Bez reklám", "Nič vám nevyskočí, nič nezdržiava.")
                        ValueBadge(1, Icons.Outlined.Euro, "Zadarmo", "Všetky funkcie, bez predplatného.")
                        ValueBadge(2, Icons.Outlined.Code, "Otvorený kód", "Ktokoľvek si môže pozrieť, ako funguje.")
                        ValueBadge(3, Icons.Outlined.VisibilityOff, "Súkromie a anonymita na prvom mieste", "Vaše údaje nepredávame a štatistiky si môžete úplne vypnúť.")
                    }
                }
                Slide.Message -> {
                    val text = Demo.control.collectAsState().value.message
                    Box(Modifier.offset(y = (-6 * bob).dp).size(96.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                        Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(128.dp))
                    }
                    Text(text, color = on, fontSize = 38.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center, lineHeight = 44.sp)
                }
                Slide.Try -> {
                    Title("Vyskúšajte si to")
                    Body("Dotknite sa obrazovky a aplikácia je vaša. Naskenujte kód: Android aj iPhone.")
                    val qr = remember { QRCodeGenerator.generateQRCode(Demo.QR_URL, 600, 600)?.asImageBitmap() }
                    if (qr != null) Box(Modifier.scale(1f + 0.03f * bob).clip(RoundedCornerShape(24.dp)).background(Color.White).padding(14.dp)) {
                        Image(qr, "Google Play", Modifier.size(220.dp))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(0.6f + 0.4f * bob)) {
                        Icon(Icons.Outlined.TouchApp, null, tint = on, modifier = Modifier.size(28.dp))
                        Text("Dotknite sa", color = on, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private fun lerpTo(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** One feature line, coming in a moment after the one before it. */
@Composable
private fun StaggeredFeature(index: Int, icon: ImageVector, text: String, color: Color) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(500L + index * 350L); shown = true }
    AnimatedVisibility(shown, enter = fadeIn(tween(400)) + slideInHorizontally(spring(dampingRatio = 0.6f)) { -it / 3 }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(26.dp))
            Text(text, color = color, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 12.dp))
        }
    }
}

/** One promise, popping in after the one before it. */
@Composable
private fun ValueBadge(index: Int, icon: ImageVector, title: String, text: String) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(400L + index * 450L); shown = true }
    val pop by animateFloatAsState(if (shown) 1f else 0f, spring(dampingRatio = 0.45f, stiffness = 260f), label = "pop")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().alpha(pop.coerceIn(0f, 1f)).scale(0.6f + 0.4f * pop)) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
        }
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(text, color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A white card like the app's own, for the mockups. */
@Composable
private fun MockCard(content: @Composable () -> Unit) =
    Surface(shape = RoundedCornerShape(22.dp), color = Color.White, shadowElevation = 12.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) { content() }
    }

@Composable
private fun LegRow(line: String, train: Boolean, from: String, to: String, times: String, highlight: Boolean = false) {
    val primary = MaterialTheme.colorScheme.primary
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Icon(if (train) Icons.Outlined.Train else Icons.Outlined.DirectionsBus, null, tint = primary, modifier = Modifier.size(22.dp))
        Box(Modifier.padding(start = 8.dp).clip(RoundedCornerShape(6.dp)).background(primary).padding(horizontal = 7.dp, vertical = 2.dp)) {
            Text(line, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Text(from, color = Color.Black, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(to, color = if (highlight) primary else Color.Black, style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
        Text(times, color = Color.DarkGray, style = MaterialTheme.typography.labelMedium)
    }
}

/** A connection whose change stop moves back and forth; the second bus's times follow it. */
@Composable
private fun TransferMockup() {
    var alt by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2_200); alt = !alt } }
    MockCard {
        Text("Zvolen, Západ → Banská Bystrica", color = Color.Black, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        AnimatedContent(alt, label = "transfer", transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(250)) }) { a ->
            Column {
                LegRow("10", false, "Zvolen, Západ  7:02", if (a) "Zvolen, Námestie SNP  7:10" else "Zvolen, AS  7:14", "", highlight = true)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    Icon(Icons.Outlined.SwapHoriz, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                    Text(if (a) "Prestup na Námestí SNP, 6 min" else "Prestup na AS, 4 min", color = MaterialTheme.colorScheme.tertiary,
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
                }
                LegRow("060411", false, if (a) "Zvolen, Námestie SNP  7:16" else "Zvolen, AS  7:18", "Banská Bystrica, AS", if (a) "7:46" else "7:48")
            }
        }
    }
}

/** Three rides dropping in one after another, joined into one journey and starred. */
@Composable
private fun OwnJourneyMockup() {
    var count by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (count < 4) { delay(1_000); count++ } }
    val starScale by animateFloatAsState(if (count >= 4) 1f else 0f, spring(dampingRatio = 0.35f, stiffness = 300f), label = "star")
    MockCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Moja cesta do školy", color = Color.Black, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.Star, "Uložené", tint = Color(0xFFF4B400), modifier = Modifier.size(28.dp).scale(starScale))
        }
        Spacer(Modifier.height(6.dp))
        val legs = listOf(
            listOf("10", "", "Zvolen, Západ  7:02", "Zvolen, AS  7:14"),
            listOf("Os 7231", "train", "Zvolen, os. st.  7:22", "Sliač  7:29"),
            listOf("060411", "", "Sliač  7:35", "Banská Bystrica, AS  7:52")
        )
        legs.forEachIndexed { i, leg ->
            AnimatedVisibility(count > i, enter = fadeIn() + slideInHorizontally(spring(dampingRatio = 0.55f, stiffness = 220f)) { it }) {
                LegRow(leg[0], leg[1] == "train", leg[2], leg[3], "")
            }
        }
    }
}

/** A follow notification as the phone shows it, its bus driving along the stops. */
@Composable
private fun NotificationMockup() {
    val along = remember { Animatable(0.1f) }
    LaunchedEffect(Unit) { along.animateTo(0.9f, tween(5_500, easing = LinearEasing)) }
    val stopsLeft = (4 - (along.value * 4).toInt()).coerceAtLeast(1)
    MockCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.NotificationsActive, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Text("Plan&Ride  teraz", color = Color.Gray, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 6.dp))
        }
        Text(if (stopsLeft == 1) "Ďalšia zastávka: vystupujete" else "Linka 10 do Námestia SNP", color = Color.Black,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(if (stopsLeft == 1) "Pripravte sa, autobus prichádza na Námestie SNP" else "Autobus je $stopsLeft zastávky od vás",
            color = Color.DarkGray, style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.padding(top = 14.dp).fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(Color(0xFFE3E3E3)))
            Box(Modifier.fillMaxWidth(along.value).height(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                repeat(5) { Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White).border(2.dp, Color.Gray, CircleShape)) }
            }
            Icon(Icons.Outlined.DirectionsBus, null, tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.offset(x = maxWidth * along.value - 14.dp).size(28.dp).background(Color.White, CircleShape))
        }
    }
}

/**
 * The followed bus going from the phone to the watch: the card on a small phone flies over to the watch, the watch
 * counts down, and at the stop it shakes with "Vystupujte".
 */
@Composable
private fun WatchHandoff() {
    val fly = remember { Animatable(0f) }
    var minutes by remember { mutableIntStateOf(3) }
    val shake = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(900)
        fly.animateTo(1f, tween(1_100, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        while (minutes > 0) { delay(1_100); minutes-- }
        repeat(6) { shake.animateTo(if (it % 2 == 0) 8f else -8f, tween(70)) }
        shake.animateTo(0f, tween(70))
    }
    val primary = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth().height(230.dp)) {
        val gap = maxWidth - 110.dp - 190.dp
        // The phone, its follow card leaving it.
        Box(Modifier.align(Alignment.CenterStart).size(110.dp, 210.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xFF202124)).padding(6.dp)) {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(15.dp)).background(Color.White))
        }
        val card = 1f - fly.value
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset(x = 14.dp + (110.dp + gap + 34.dp - 14.dp) * fly.value, y = (-40).dp * kotlin.math.sin(fly.value * Math.PI.toFloat()))
                .scale(1f - 0.35f * fly.value).alpha(if (fly.value >= 1f) 0f else 1f)
                .size(82.dp, 54.dp).clip(RoundedCornerShape(12.dp)).background(primary),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.DirectionsBus, null, tint = Color.White, modifier = Modifier.size(20.dp))
                Text("Linka 10", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.alpha(0.4f + 0.6f * card))
            }
        }
        // The watch: empty until the card lands, then the countdown, then the stop.
        Box(
            Modifier.align(Alignment.CenterEnd).graphicsLayer { rotationZ = shake.value }.size(190.dp).clip(CircleShape)
                .background(Color(0xFF202124)).border(9.dp, Color(0xFF5F6368), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (fly.value >= 1f) AnimatedContent(minutes, label = "min", transitionSpec = { (fadeIn() + scaleIn(initialScale = 1.3f)) togetherWith fadeOut() }) { m ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.DirectionsBus, null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(26.dp))
                    if (m > 0) {
                        Text("$m min", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black)
                        Text("do Nám. SNP", color = Color(0xFFBDC1C6), style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Vystupujte", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("Námestie SNP", color = Color(0xFFBDC1C6), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/** A number counting up from zero, with what it counts under it. */
@Composable
private fun CountUp(target: Int, label: String, color: Color, big: Boolean = false) {
    val value = remember { Animatable(0f) }
    LaunchedEffect(target) { value.animateTo(target.toFloat(), tween(1_800, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%,d".format(java.util.Locale("sk"), value.value.toInt()), color = color, fontSize = if (big) 72.sp else 40.sp,
            fontWeight = FontWeight.Black, lineHeight = if (big) 80.sp else 46.sp)
        Text(label, color = color.copy(alpha = 0.9f), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

// Slovakia's border (longitude, latitude), roughly: enough for a silhouette everyone knows.
private val SK_BORDER = listOf(
    16.95 to 48.62, 17.10 to 48.85, 17.40 to 48.82, 17.70 to 48.87, 17.95 to 49.02, 18.15 to 49.25, 18.40 to 49.35,
    18.85 to 49.52, 19.25 to 49.52, 19.45 to 49.60, 19.80 to 49.20, 20.05 to 49.18, 20.30 to 49.40, 20.90 to 49.30,
    21.30 to 49.45, 21.80 to 49.38, 22.20 to 49.16, 22.56 to 49.08, 22.30 to 48.70, 22.15 to 48.41, 21.70 to 48.38,
    21.40 to 48.56, 20.80 to 48.58, 20.50 to 48.53, 20.10 to 48.25, 19.60 to 48.22, 19.00 to 48.07, 18.80 to 47.83,
    18.20 to 47.75, 17.80 to 47.75, 17.40 to 47.98, 17.15 to 48.00, 17.05 to 48.15, 16.90 to 48.40
)
// Towns lighting up one after another, west to east, and the lines drawn between neighbours.
private val SK_TOWNS = listOf(
    17.11 to 48.15, 17.59 to 48.38, 18.09 to 48.31, 18.16 to 47.99, 18.04 to 48.89, 18.74 to 49.22, 18.92 to 49.07,
    19.15 to 48.74, 19.13 to 48.58, 19.67 to 48.33, 19.61 to 49.08, 20.30 to 49.06, 20.53 to 48.66, 21.24 to 49.00,
    21.26 to 48.72, 21.92 to 48.75, 21.91 to 48.93
)
private val SK_LINES = listOf(0 to 1, 1 to 2, 2 to 3, 1 to 4, 4 to 5, 5 to 6, 6 to 7, 7 to 8, 8 to 2, 8 to 9, 6 to 10,
    10 to 11, 11 to 13, 7 to 12, 12 to 14, 13 to 14, 14 to 15, 15 to 16, 13 to 16)

@Composable
private fun SlovakiaMap() {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) { progress.animateTo(1f, tween(4_500, easing = LinearEasing)) }
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(170.dp)) {
        val (w, h) = size.width to size.height
        // Longitude squeezed by cos(48.7°) so the country keeps its shape.
        val sx = 0.66f
        val scale = minOf(w / ((22.6f - 16.85f) * sx), h / (49.65f - 47.7f))
        val ox = (w - (22.6f - 16.85f) * sx * scale) / 2
        fun at(p: Pair<Double, Double>) = androidx.compose.ui.geometry.Offset(
            ox + ((p.first - 16.85) * sx * scale).toFloat(), ((49.65 - p.second) * scale).toFloat())
        val border = androidx.compose.ui.graphics.Path().apply {
            SK_BORDER.forEachIndexed { i, p -> at(p).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } }
            close()
        }
        drawPath(border, Color.White.copy(alpha = 0.14f))
        drawPath(border, Color.White.copy(alpha = 0.7f), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
        val t = progress.value * (SK_TOWNS.size + 3)
        SK_LINES.forEach { (a, b) ->
            val grow = (t - maxOf(a, b) - 0.3f).coerceIn(0f, 1f)
            if (grow > 0f) {
                val from = at(SK_TOWNS[a]); val to = at(SK_TOWNS[b])
                drawLine(Color.White.copy(alpha = 0.85f), from, from + (to - from) * grow, strokeWidth = 2.5.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
        SK_TOWNS.forEachIndexed { i, p ->
            val pop = (t - i).coerceIn(0f, 1f)
            if (pop > 0f) {
                drawCircle(Color.White.copy(alpha = 0.3f * (1f - pop)), radius = 16.dp.toPx() * pop, center = at(p))
                drawCircle(Color.White, radius = 4.5.dp.toPx() * pop, center = at(p))
            }
        }
    }
}

/** The PIN that unlocks this phone (kiosk and touch lock off until emHUB turns them on again), or removes the app. */
@Composable
private fun PinDialog(activity: Activity) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    fun check(then: () -> Unit) { if (Demo.pinOk(activity, pin)) then() else { wrong = true; pin = "" } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { Demo.unlockAsk.value = false },
        title = { Text("Odomknúť tento telefón") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8); wrong = false }, label = { Text("PIN z emHUB") }, singleLine = true,
                    isError = wrong, supportingText = { if (wrong) Text("Nesprávny PIN") },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword)
                )
                androidx.compose.material3.TextButton(onClick = { check { Demo.unlockHere(activity); Demo.removeAsk.value = true } }) {
                    Text("Odomknúť a odinštalovať aplikáciu")
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { check { Demo.unlockHere(activity) } }) { Text("Odomknúť") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { Demo.unlockAsk.value = false }) { Text("Zrušiť") } }
    )
}
