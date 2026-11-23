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
    DemoStrip(captionNow.collectAsState().value, Demo.touring.collectAsState().value)
}
private enum class Slide { Intro, Transfer, OwnJourney, Alerts, Watch, Values, Try }

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

    LaunchedEffect(Unit) {
        if (!Demo.tourStarted) {
            Demo.tourStarted = true
            delay(1_200)
            Demo.lastTouch = SystemClock.elapsedRealtime()
            Demo.touring.value = true
        }
        while (true) {
            Demo.watchWindows()
            if (!Demo.touring.value && SystemClock.elapsedRealtime() - Demo.lastTouch > Demo.IDLE_MS) onReset()
            delay(500)
        }
    }

    LaunchedEffect(touring) {
        if (!touring) { captionNow.value = null; slide = null; return@LaunchedEffect }
        tour(activity, viewModel, live, { captionNow.value = it }, { slide = it })
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            DemoStrip(caption, touring)
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
    }
}

/** The tour, round and round. Captions and slides go through [say] and [show]. */
private suspend fun tour(
    activity: Activity,
    viewModel: AppViewModel,
    live: LiveViewModel,
    setCaption: (Caption?) -> Unit,
    setSlide: (Slide?) -> Unit
) {
    val theme = com.ksjd.testem.DefaultThemePresets.all.first().id
    if (viewModel.appState.value.selectedThemeId != theme) viewModel.selectThemePreset(theme)
    val prefs = CredentialsManager(activity)
    var n = 0
    fun caption(title: String, body: String, ms: Long) = setCaption(Caption(title, body, ms, n++))
    suspend fun say(title: String, body: String, ms: Long) { caption(title, body, ms); delay(ms) }
    // A slide stays up until the next slide or the app's next step (back to the app: [app]).
    suspend fun show(s: Slide, ms: Long) { setCaption(null); setSlide(s); delay(ms) }
    suspend fun app() { setSlide(null); delay(400) }
    suspend fun type(text: String, set: (String) -> Unit) { for (i in 1..text.length) { set(text.take(i)); delay(110) } }
    suspend fun <T> waitFor(ms: Long, get: () -> T?): T? = withTimeoutOrNull(ms) {
        var value = get()
        while (value == null) { delay(150); value = get() }
        value
    }
    try {
        while (true) {
            show(Slide.Intro, 5_500)

            app()
            Demo.tab.value = AppTab.Home
            say("Lístok vždy po ruke", "QR kód sa sám obnovuje každých pár sekúnd. Kredit vidíte vpravo hore.", 5_000)
            caption("Dva účty, dva lístky", "SAD Zvolen aj IDS BBSK. Lístok prepnete potiahnutím do strany.", 6_000)
            delay(1_000); viewModel.setTicketDefault(Wallet.IDS)
            delay(4_000); viewModel.setTicketDefault(Wallet.SADZV)
            delay(1_000)

            caption("Sledovanie autobusu",
                "Farba karty ukazuje meškanie: teplejšia, keď autobus mešká, chladnejšia, keď ide skôr. Lístok sa prepne sám podľa linky.",
                16_500)
            Demo.simulateFollow(activity, 1_800)

            Demo.tab.value = AppTab.Departures
            live.closeStop()
            caption("Odchody naživo", "Nájdite zastávku a uvidíte, kedy autobus naozaj príde, aj s meškaním.", 9_000)
            // Banská Bystrica's railway station: intercity buses with live positions.
            type("Železničná", live::search)
            waitFor(5_000) { live.state.value.results.let { r -> r.firstOrNull { "Bystrica" in it.name } ?: r.firstOrNull() } }?.let { live.openStop(it) }
            waitFor(8_000) { live.state.value.board?.departures?.firstOrNull() }
            delay(4_000)
            live.state.value.board?.departures?.let { d -> d.firstOrNull { it.isRealtime } ?: d.firstOrNull() }?.let { departure ->
                live.openTrip(departure)
                say("Celá cesta autobusu", "Všetky zastávky, kde je autobus teraz a o koľko mešká.", 5_000)
                if (waitFor(4_000) { live.state.value.trip?.detail } != null) {
                    Demo.tripMap.value = true
                    say("Podrobná mapa", "Celá trasa po cestách aj poloha autobusu na mape.", 7_000)
                    Demo.tripMap.value = false
                    delay(500)
                }
                live.closeTrip()
            }
            live.closeStop()
            live.search("")

            Demo.tab.value = AppTab.Planner
            caption("Plánovač ciest", "Autobusy aj vlaky po celom Slovensku, aj s prestupmi.", 9_000)
            viewModel.setTimetableTime("")
            viewModel.setTimetableDate("")
            type("Zvolen", viewModel::updateTimetableFromInput)
            waitFor(4_000) { viewModel.timetableState.value.fromSuggestions.firstOrNull() }?.let(viewModel::selectTimetableFromSuggestion)
            type("Banská Bystrica", viewModel::updateTimetableToInput)
            waitFor(4_000) { viewModel.timetableState.value.toSuggestions.firstOrNull() }?.let(viewModel::selectTimetableToSuggestion)
            viewModel.loadTimetables()
            waitFor(10_000) { viewModel.timetableState.value.takeIf { !it.isLoading } }
            delay(800)
            Demo.plannerScroll.value = "results"
            say("Cena a kredit", "Pri spojení vidíte cenu s vašou kartou aj to, či vám kredit stačí.", 6_500)
            // The two towns become the demo's home and work: the planner's buttons fill in.
            viewModel.timetableState.value.let { s ->
                s.selectedFromSuggestion?.let { prefs.savePlace("home", it) }
                s.selectedToSuggestion?.let { prefs.savePlace("work", it) }
            }
            Demo.places.value++
            Demo.plannerScroll.value = "top"
            caption("Domov a práca", "Uložte si domov a prácu. Jedno ťuknutie nájde cestu z najbližšej zastávky.", 7_000)
            delay(2_500)
            prefs.getPlace("home")?.let { viewModel.takeMeTo(it) {} }
            delay(4_500)

            show(Slide.Transfer, 7_500)
            show(Slide.OwnJourney, 7_500)

            app()
            Demo.tab.value = AppTab.History
            say("História jázd", "Kde ste cestovali, koľko ste minuli a kedy ste dobili kredit.", 6_000)

            show(Slide.Alerts, 6_500)

            app()
            Demo.tab.value = AppTab.Account
            caption("Vaše farby", "Vyberte si tému alebo si nastavte vlastné farby celej aplikácie.", 8_000)
            viewModel.appState.value.themePresets.filter { it.id != theme }.take(4).forEach {
                viewModel.selectThemePreset(it.id)
                delay(1_600)
            }
            viewModel.selectThemePreset(theme)
            delay(1_200)

            show(Slide.Watch, 6_000)
            show(Slide.Values, 7_000)
            show(Slide.Try, 9_000)
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
private fun DemoStrip(caption: Caption?, touring: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp).padding(top = 6.dp).animateContentSize(spring(stiffness = Spring.StiffnessLow))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pulse by rememberInfiniteTransition(label = "dot").animateFloat(0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
            Box(Modifier.size(8.dp).alpha(pulse).clip(CircleShape).background(scheme.tertiary))
            Text("Ukážková verzia", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                color = scheme.tertiary, modifier = Modifier.padding(start = 8.dp))
            Text(
                if (touring) "  Dotknite sa a vyskúšajte si to sami" else "  Údaje sú vymyslené, nič sa neodosiela",
                style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, maxLines = 1
            )
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
                    Title("Aj na hodinkách")
                    WatchMockup(Modifier.offset(y = (-6 * bob).dp))
                    Body("Odpočet do odchodu na zápästí. Widgety a ciferníky pre hodinky s Wear OS.")
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
                Slide.Try -> {
                    Title("Vyskúšajte si to")
                    Body("Dotknite sa obrazovky a aplikácia je vaša. Naskenujte kód a stiahnite si Plan&Ride z Google Play.")
                    val qr = remember { QRCodeGenerator.generateQRCode(Demo.PLAY_URL, 600, 600)?.asImageBitmap() }
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

/** A round watch counting down to the bus. */
@Composable
private fun WatchMockup(modifier: Modifier = Modifier) {
    var minutes by remember { mutableIntStateOf(5) }
    LaunchedEffect(Unit) { while (minutes > 1) { delay(1_200); minutes-- } }
    val pulse = rememberInfiniteTransition(label = "pulse").animateFloat(1f, 1.06f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "p")
    Box(
        modifier.size(230.dp).clip(CircleShape).background(Color(0xFF202124)).border(10.dp, Color(0xFF5F6368), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.DirectionsBus, null, tint = Color(0xFF8AB4F8), modifier = Modifier.size(30.dp))
            Text("Linka 10", color = Color.White, style = MaterialTheme.typography.titleMedium)
            AnimatedContent(minutes, label = "min", transitionSpec = { (fadeIn() + scaleIn(initialScale = 1.3f)) togetherWith fadeOut() }) { m ->
                Text("$m min", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black, modifier = Modifier.scale(pulse.value))
            }
            Spacer(Modifier.width(1.dp).height(2.dp))
            Text("Námestie SNP", color = Color(0xFFBDC1C6), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
