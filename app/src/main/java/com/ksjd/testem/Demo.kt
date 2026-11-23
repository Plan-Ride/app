package com.ksjd.testem

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The open day phone (the "demo" build type): signed in to a made-up SAD Zvolen account, a tour that shows the app by
 * itself, nothing written to emHUB, and after [IDLE_MS] without a touch everything wiped and started again for the next
 * visitor. Timetables, boards and trips are real (read from emHUB as usual).
 */
object Demo {
    val on: Boolean get() = BuildConfig.DEMO
    const val EMAIL = "demo@par.karafa.net"
    const val IDLE_MS = 60_000L
    const val PLAY_URL = "https://play.google.com/store/apps/details?id=com.ksjd.testem"

    /** The tour is running; any touch ends it. */
    val touring = MutableStateFlow(false)
    /** The tour asks the main screen for a tab. */
    val tab = MutableStateFlow<AppTab?>(null)
    @Volatile var lastTouch = SystemClock.elapsedRealtime()
    /** Raised by [reset]: the whole screen is built again from nothing (MainActivity keys its content on it). */
    val generation = MutableStateFlow(0)
    /** The tour opens the open trip's map. */
    val tripMap = MutableStateFlow(false)
    /** The tour scrolls the planner: "results" to the first connection, "top" back up. */
    val plannerScroll = MutableStateFlow<String?>(null)
    /** Home and work changed behind the planner's back (the tour sets them): it reads them again. */
    val places = MutableStateFlow(0)
    /** The tour starts by itself once per [generation]. */
    @Volatile var tourStarted = false

    /** A fresh install or a wiped one: both demo accounts, Slovak, no first-run questions. */
    fun seed(context: Context) {
        val prefs = CredentialsManager(context)
        com.google.firebase.analytics.FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(false)
        if (prefs.getEmail() == EMAIL || prefs.isOnboarded()) return
        prefs.saveCredentials(EMAIL, "demo")
        prefs.saveIdsCredentials(EMAIL, "demo")
        prefs.markOnboarded()
        prefs.markPrivacyAsked()
        prefs.saveWhatsNewSeenVersion(Int.MAX_VALUE)
        prefs.saveLanguageCode("sk")
    }

    // ------------------------------------------------------------ the made-up account

    private val day = 24 * 3600L

    fun account(creditCents: Int): AccountSnapshot {
        val now = System.currentTimeMillis() / 1000
        return AccountSnapshot(
            userName = "Návštevník Dňa otvorených dverí",
            cards = listOf(AccountDetails(
                snr = "DEMO0042", cardTypeName = "Virtuálna karta", organizationName = "SAD Zvolen (ukážka)",
                cardValidFrom = now - 300 * day, cardValidTo = now + 700 * day,
                discountValidFrom = now - 80 * day, discountValidTo = now + 220 * day,
                creditLastBalance = creditCents / 100.0, currencySymbol = "€"
            ))
        )
    }

    /** The IDS BBSK demo account: credit and the virtual card with its QR code. */
    fun idsAccount(): IdsAccount {
        val now = System.currentTimeMillis()
        return IdsAccount(
            email = EMAIL, name = "Návštevník Dňa otvorených dverí", credit = 8.60,
            creditNote = "Kredit je viazaný na virtuálnu kartu (ukážka)",
            identifiers = listOf(IdsIdentifier(1, "Virtuálna karta", "DEMO 0042", listOf("Kredit aktívny"), true, true, "demo")),
            topUps = listOf(IdsTopUp("demo-1", now - 9 * 86_400_000L, "Dobitie kreditu", "Zaplatené", 1000))
        )
    }

    /**
     * A made-up followed bus for the tour: the real home card shows it, its bus moving along the stops while the delay
     * goes up and down, so the card's colour visibly changes with it (warmer when late, cooler when early). [stepMs] per
     * delay value; nothing is sent anywhere and no service runs.
     */
    suspend fun simulateFollow(context: Context, stepMs: Long = 2_500) {
        val names = listOf("Zvolen, AS", "Zvolen, Sekier", "Kováčová", "Sliač, kúpele", "Sliač", "Vlkanová", "Banská Bystrica, AS")
        val trip = com.ksjd.testem.live.TrackedTrip(
            ref = com.ksjd.testem.live.TripRef("060411", 0, "060411", 1, "Banská Bystrica, AS"),
            boardingPlatformIds = listOf(1003), alightOrder = 6, fromStop = names[3], toStop = names[6]
        )
        val delays = listOf(0, 2, 5, 8, 6, 3, 0, -2, 0)
        val start = System.currentTimeMillis()
        try {
            for (delay in delays) {
                val until = SystemClock.elapsedRealtime() + stepMs
                while (SystemClock.elapsedRealtime() < until) {
                    val now = System.currentTimeMillis()
                    // The bus passes a stop every 4 s of the tour; the rider's stop (index 3) comes about 12 s in.
                    val ride = (now - start) * 30 // 30 times faster than real time
                    val detail = com.ksjd.testem.live.TripDetail(
                        line = trip.ref.line, destination = names.last(), delaySeconds = delay * 60,
                        positionSource = com.ksjd.testem.live.PositionSource.BusGps,
                        crowd = com.ksjd.testem.hub.Crowd(2, 3),
                        stops = names.mapIndexed { i, name ->
                            com.ksjd.testem.live.TripStop(name, 1000 + i, i, now + i * 120_000L - ride - delay * 60_000L, null)
                        }
                    )
                    val progress = com.ksjd.testem.live.TripProgress.from(detail, trip, now)
                    com.ksjd.testem.live.TripTracking.demoShow(trip,
                        com.ksjd.testem.live.FollowView(trip, detail, progress, catchLine = "Stihnete to, pešo 4 min", live = true, canReportCrowd = true))
                    kotlinx.coroutines.delay(250)
                }
            }
        } finally {
            com.ksjd.testem.live.TripTracking.demoShow(null, null)
        }
    }

    /** About a month of rides, newest first, ending at [creditCents]; plus the demo's own top-ups. */
    fun history(creditCents: Int, topUps: List<Pair<Long, Int>>): List<CardHistoryItem> {
        val rides = listOf(
            Triple("Obyčajný lístok MHD Zvolen", "Zvolen, Námestie SNP", 60),
            Triple("Lístok Zvolen – Banská Bystrica", "Zvolen, AS", 185),
            Triple("Obyčajný lístok MHD Zvolen", "Zvolen, Západ", 60),
            Triple("Lístok Banská Bystrica – Zvolen", "Banská Bystrica, AS", 185),
            Triple("Lístok Zvolen – Detva", "Zvolen, AS", 160),
            Triple("Obyčajný lístok MHD Zvolen", "Zvolen, Poliklinika", 60),
        )
        val now = System.currentTimeMillis()
        val events = mutableListOf<Triple<Long, Int, Triple<String, String, Int>?>>() // time, signed cents, ride
        val r = java.util.Random(42)
        for (d in 1..30) if (d % 7 !in listOf(0, 6)) repeat(2) { i ->
            val at = now - d * 86_400_000L + (if (i == 0) 7 else 15) * 3_600_000L + r.nextInt(50) * 60_000L
            val ride = rides[r.nextInt(rides.size)]
            events += Triple(at, -ride.third, ride)
        }
        events += Triple(now - 12 * 86_400_000L, 1000, null)
        events += Triple(now - 26 * 86_400_000L, 2000, null)
        topUps.forEach { (at, cents) -> events += Triple(at, cents, null) }
        var balance = creditCents
        return events.sortedByDescending { it.first }.mapIndexed { i, (at, cents, ride) ->
            val before = balance - cents
            fun eur(c: Int) = "%.2f €".format(java.util.Locale("sk"), c / 100.0)
            CardHistoryItem(
                id = "demo-$i", sourceType = HistorySourceType.TICKET, timestampMs = at,
                title = ride?.first ?: "Vklad na kartu",
                subtitle = listOfNotNull(ride?.second, "${eur(before)} → ${eur(balance)}").joinToString("  ·  "),
                amountText = (if (cents > 0) "+" else "−") + eur(kotlin.math.abs(cents)),
                amountCents = cents.toLong(), stopName = ride?.second.orEmpty(), isTopUp = cents > 0
            ).also { balance = before }
        }
    }

    // ------------------------------------------------------------ touches and the reset

    /**
     * Every window of the app (the screen, sheets, dialogs) reports its touches here: the first touch during the
     * tour only ends it, and the time of the last one decides when to start over.
     */
    fun watchWindows() {
        rootViews().forEach { root ->
            val window = windowOf(root) ?: return@forEach
            val original = window.callback ?: return@forEach
            if (original is Watcher) return@forEach
            window.callback = Watcher(original)
        }
    }

    private class Watcher(private val original: Window.Callback) : Window.Callback by original {
        private var swallowing = false
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouch = SystemClock.elapsedRealtime()
                if (touring.value) { touring.value = false; swallowing = true }
            }
            if (swallowing) {
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowing = false
                return true
            }
            return original.dispatchTouchEvent(event)
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            lastTouch = SystemClock.elapsedRealtime()
            if (touring.value) { touring.value = false; return true }
            return original.dispatchKeyEvent(event)
        }
    }

    // ponytail: reflection on WindowManagerGlobal/DecorView (as Square's curtains does); sheets and dialogs have no public hook.
    @Suppress("UNCHECKED_CAST")
    private fun rootViews(): List<View> =
        if (android.os.Build.VERSION.SDK_INT >= 29) android.view.inspector.WindowInspector.getGlobalWindowViews()
        else runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val global = c.getMethod("getInstance").invoke(null)
            (c.getDeclaredField("mViews").apply { isAccessible = true }.get(global) as List<View>).toList()
        }.getOrDefault(emptyList())

    private fun windowOf(root: View): Window? = runCatching {
        var c: Class<*>? = root.javaClass
        while (c != null && c.name != "com.android.internal.policy.DecorView") c = c.superclass
        c?.getDeclaredField("mWindow")?.apply { isAccessible = true }?.get(root) as Window?
    }.getOrNull()

    /**
     * Everything the last visitor did goes: settings, accounts, followed buses, alarms, notifications. The app is not
     * closed (a pinned app would go black): the screen and its view models are built again in the same activity.
     * [applySettings] puts back what MainActivity sets up from the settings when it starts.
     */
    fun reset(activity: androidx.activity.ComponentActivity, applySettings: () -> Unit) {
        val context = activity.applicationContext
        touring.value = false
        runCatching { com.ksjd.testem.live.TripTracking.demoShow(null, null) }
        runCatching { com.ksjd.testem.live.FollowLater.cancel(context) }
        runCatching { com.ksjd.testem.live.TripTracking.clearDraft() }
        runCatching { context.stopService(Intent(context, com.ksjd.testem.live.TripTrackerService::class.java)) }
        runCatching { androidx.work.WorkManager.getInstance(context).cancelAllWork() }
        androidx.core.app.NotificationManagerCompat.from(context).cancelAll()
        java.io.File(context.applicationInfo.dataDir, "shared_prefs").listFiles()?.forEach { file ->
            context.getSharedPreferences(file.name.removeSuffix(".xml"), Context.MODE_PRIVATE).edit().clear().commit()
        }
        seed(context)
        TransportModes.load(context)
        applySettings()
        activity.viewModelStore.clear()
        tourStarted = false
        lastTouch = SystemClock.elapsedRealtime()
        generation.value++
    }
}
