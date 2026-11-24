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
    /** The QR code's page (emHUB counts the scans): Google Play for Android, the web version for an iPhone. */
    val QR_URL = "${BuildConfig.HUB_URL}/den?z=qr"
    /** Without emHUB this long, a locked phone unlocks by itself (the Wi-Fi gone must not trap anyone in the app). */
    const val OFFLINE_RELEASE_MS = 10 * 60_000L

    /** The tour is running; any touch ends it. */
    val touring = MutableStateFlow(false)
    /** The tour asks the main screen for a tab. */
    val tab = MutableStateFlow<AppTab?>(null)
    @Volatile var lastTouch = SystemClock.elapsedRealtime()
    /** When the last reset was: touched since then means a visitor left something to wipe. */
    @Volatile var resetDone = lastTouch
    /** Raised by [reset]: the whole screen is built again from nothing (MainActivity keys its content on it). */
    val generation = MutableStateFlow(0)
    /** The tour opens the open trip's map. */
    val tripMap = MutableStateFlow(false)
    /** The tour scrolls the planner: "results" to the first connection, "top" back up. */
    val plannerScroll = MutableStateFlow<String?>(null)
    // ------------------------------------------------------------ emHUB's control (Server tab: Demo phones)

    /**
     * What emHUB says this phone shows: [mode] "tour", "hold" (one step, [hold]) or "off"; a [message] ("slide": full
     * screen when nobody uses the phone, "strip": in the top strip); the tour's [steps] in order (empty = all); and
     * [offsetMs], this phone's place in a wave across the table.
     */
    data class Control(
        val mode: String = "tour", val hold: String = "", val epoch: Long = 0, val idleMs: Long = IDLE_MS, val resetAt: Long = 0,
        val name: String = "", val message: String = "", val messageStyle: String = "slide", val steps: List<String> = emptyList(),
        val offsetMs: Long = 0,
        /** [kiosk]: the app holds the screen; [locked]: touches do nothing; both since [lockAt] (a PIN unlock before it stands). */
        val kiosk: Boolean = false, val locked: Boolean = false, val lockAt: Long = 0, val pinHash: String = "",
        /** The uploaded build ([apkSha]) to install when [updateAt] is new; [removeAt]: give up the owner and ask to be uninstalled. */
        val apkSha: String = "", val updateAt: Long = 0, val removeAt: Long = 0,
        /** For the tour's slides: carriers and stops in the timetable, journey searches today. */
        val operators: Int = 0, val stops: Int = 0, val searchesToday: Int = 0
    )
    val control = MutableStateFlow(Control())
    /** emHUB's clock minus this phone's: every phone counts the tour from the same moment. */
    @Volatile var clockOffset = 0L
    fun now() = System.currentTimeMillis() + clockOffset
    /** The best few looks at emHUB's clock (time there and back, offset): the quickest answer is the truest. */
    private val clockSamples = ArrayDeque<Pair<Long, Long>>()
    /** emHUB asked for a reset (the dashboard's Reset): MainActivity's frame does it. */
    val resetAsked = MutableStateFlow(false)
    private var resetSeen = -1L
    /** The dashboard's Blink: when it was asked (the frame flashes this phone's name). */
    val blink = MutableStateFlow(0L)
    /** The tour's steps (id, name), sent so the dashboard can offer them; and the one showing now. */
    @Volatile var steps: List<Pair<String, String>> = emptyList()
    @Volatile var stepNow = ""
    /** The last tour step shown: where a visitor picked the phone up (the dashboard counts them). */
    @Volatile var lastStep = ""
    /** The tab a visitor is on (for the dashboard: "Visitor: Trasa"). */
    @Volatile var screen = ""
    /** Someone touched the phone since its last reset: a visitor has it. */
    val visitor: Boolean get() = !touring.value && lastTouch > resetDone

    @Volatile private var socket: okhttp3.WebSocket? = null
    @Volatile private var connected = false
    private var appContext: Context? = null

    /**
     * The live line to emHUB (once per app process; reconnects by itself, 2 s up to 30 s): emHUB's orders arrive at once,
     * and this phone says what it shows when that changes and every 5 s. Each answer carries the send time back ("t"),
     * so the way there and back is measured exactly and the clock follows the quickest of the last six answers.
     */
    fun connect(context: Context) {
        if (socket != null) return
        appContext = context.applicationContext
        val device = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: "unknown"
        var wait = 2_000L
        fun open() {
            socket = com.ksjd.testem.hub.HubClient.demoSocket(device, object : okhttp3.WebSocketListener() {
                override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                    connected = true; wait = 2_000L; lastOnline = SystemClock.elapsedRealtime(); sendState()
                }
                override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                    val back = System.currentTimeMillis()
                    val m = runCatching { com.google.gson.JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
                    when (m.get("type")?.asString) {
                        "blink" -> { blink.value = back; return }
                        "control" -> Unit
                        else -> return
                    }
                    fun long(k: String) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
                    fun text(k: String) = m.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    val sent = long("t")
                    if (sent > 0 && back - sent in 0..1_500) synchronized(clockSamples) {
                        clockSamples.addLast((back - sent) to long("now") - (sent + back) / 2)
                        while (clockSamples.size > 6) clockSamples.removeFirst()
                        clockOffset = clockSamples.minBy { it.first }.second
                    }
                    val next = Control(
                        mode = text("mode").ifBlank { "tour" }, hold = text("hold"), epoch = long("epoch"),
                        idleMs = long("idleSeconds").takeIf { it > 0 }?.times(1000) ?: IDLE_MS, resetAt = long("resetAt"),
                        name = text("name"), message = text("message"), messageStyle = text("messageStyle").ifBlank { "slide" },
                        steps = m.getAsJsonArray("steps")?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty(),
                        offsetMs = long("offsetMs"),
                        kiosk = m.get("kiosk")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                        locked = m.get("locked")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                        lockAt = long("lockAt"), pinHash = text("pinHash"), apkSha = text("apkSha"),
                        updateAt = long("updateAt"), removeAt = long("removeAt"),
                        operators = m.getAsJsonObject("facts")?.get("operators")?.asInt ?: 0,
                        stops = m.getAsJsonObject("facts")?.get("stops")?.asInt ?: 0,
                        searchesToday = m.getAsJsonObject("facts")?.get("searchesToday")?.asInt ?: 0
                    )
                    lastOnline = SystemClock.elapsedRealtime()
                    // Only a Remove pressed while this app runs (an old one would remove a reinstalled app again).
                    if (removeSeen >= 0 && next.removeAt > removeSeen) removeAsk.value = true
                    removeSeen = next.removeAt
                    appContext?.let { c ->
                        // The PIN works without emHUB later on: kept (its hash) where the reset doesn't wipe.
                        if (next.pinHash.isNotBlank() && next.pinHash != store(c).getProperty("pinHash")) save(c, "pinHash", next.pinHash)
                        maybeUpdate(c, next)
                    }
                    if (resetSeen >= 0 && next.resetAt > resetSeen) resetAsked.value = true
                    resetSeen = next.resetAt
                    control.value = next
                }
                override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) = again()
                override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) = again()
                fun again() {
                    connected = false
                    val delayMs = wait.also { wait = (wait * 2).coerceAtMost(30_000L) }
                    java.util.Timer().schedule(object : java.util.TimerTask() { override fun run() { open() } }, delayMs)
                }
            })
        }
        open()
        // The power button can't be blocked (no app ever gets it): while locked, the screen it turns off comes back on.
        androidx.core.content.ContextCompat.registerReceiver(context.applicationContext, object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (lockWanted(control.value) || kioskWanted(control.value)) wakeUp(c)
            }
        }, android.content.IntentFilter(Intent.ACTION_SCREEN_OFF), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        // Every 5 s: still here, the battery, and a fresh look at emHUB's clock.
        java.util.Timer().scheduleAtFixedRate(object : java.util.TimerTask() { override fun run() { sendState() } }, 5_000L, 5_000L)
    }

    /** What this phone shows now, to emHUB (on every step, when a visitor takes over or changes tab, and every 5 s). */
    fun sendState() {
        if (!connected) return
        val battery = appContext?.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val body = com.google.gson.JsonObject().apply {
            addProperty("type", "state")
            addProperty("t", System.currentTimeMillis())
            addProperty("model", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            addProperty("version", BuildConfig.VERSION_NAME)
            addProperty("touring", touring.value)
            addProperty("visitor", visitor)
            addProperty("step", stepNow)
            addProperty("screen", if (visitor) screen else "")
            addProperty("fromStep", if (visitor) lastStep else "")
            appContext?.let { c ->
                addProperty("owner", isOwner(c))
                addProperty("kioskOn", kioskOn)
                addProperty("lockOn", lockWanted(control.value))
                addProperty("unlocked", (control.value.kiosk || control.value.locked) && unlockedAt(c) >= control.value.lockAt)
            }
            addProperty("apkSha", ownSha)
            addProperty("updating", updating)
            battery?.let { b ->
                val level = b.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) addProperty("battery", level * 100 / scale)
                addProperty("charging", b.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0)
            }
            add("steps", com.google.gson.JsonArray().apply { steps.forEach { (id, name) -> add(com.google.gson.JsonArray().apply { add(id); add(name) }) } })
        }
        socket?.send(body.toString())
    }

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

    // ------------------------------------------------------------ kiosk, touch lock, PIN, update, removal

    /** When emHUB was last heard (or the app started): offline [OFFLINE_RELEASE_MS] long, a locked phone unlocks. */
    @Volatile var lastOnline = SystemClock.elapsedRealtime()
    val offline: Boolean get() = !connected && SystemClock.elapsedRealtime() - lastOnline > OFFLINE_RELEASE_MS
    /** The lock task is on (the kiosk), as last set by [applyKiosk]. */
    @Volatile var kioskOn = false
    /** Five taps in the top left corner: MainActivity's frame asks for the PIN. */
    val unlockAsk = MutableStateFlow(false)
    /** A touch the lock swallowed (the frame says "just to look at"). */
    val lockedTap = MutableStateFlow(0L)
    /** What an update is doing, for the dashboard ("" when nothing). */
    @Volatile var updating = ""
    /** This app's own build, to tell whether emHUB's upload is a different one. */
    @Volatile var ownSha = ""
    private var removeSeen = -1L
    /** emHUB or the adb broadcast asked to give up the owner and be uninstalled: the frame does it (it needs the activity). */
    val removeAsk = MutableStateFlow(false)

    // Kept in files/, which the reset leaves alone (it wipes shared_prefs): the PIN's hash and when it was last used.
    private fun storeFile(c: Context) = java.io.File(c.filesDir, "demo-kiosk.properties")
    private fun store(c: Context) = java.util.Properties().apply { runCatching { storeFile(c).inputStream().use { load(it) } } }
    private fun save(c: Context, key: String, value: String) {
        val p = store(c).apply { setProperty(key, value) }
        runCatching { storeFile(c).outputStream().use { p.store(it, null) } }
    }
    @Volatile private var unlockedCache = -1L
    fun unlockedAt(c: Context): Long {
        if (unlockedCache < 0) unlockedCache = store(c).getProperty("unlockedAt")?.toLongOrNull() ?: 0L
        return unlockedCache
    }

    private fun lockedNow(c: Control, on: Boolean) = on && appContext?.let { unlockedAt(it) < c.lockAt } != false && !offline
    fun kioskWanted(c: Control) = lockedNow(c, c.kiosk)
    fun lockWanted(c: Control) = lockedNow(c, c.locked)

    /** The PIN (checked against emHUB's hash, kept for when emHUB can't be reached). */
    fun pinOk(c: Context, pin: String): Boolean {
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(pin.toByteArray()).joinToString("") { "%02x".format(it) }
        val known = control.value.pinHash.ifBlank { store(c).getProperty("pinHash").orEmpty() }
        return known.isNotBlank() && hash == known
    }

    /** Kiosk and lock off here until emHUB turns either on again (the PIN, or adb's broadcast). */
    fun unlockHere(c: Context) {
        unlockedCache = now() + 1
        save(c, "unlockedAt", unlockedCache.toString())
        unlockAsk.value = false
        sendState()
    }

    fun isOwner(c: Context) = runCatching {
        c.getSystemService(android.app.admin.DevicePolicyManager::class.java).isDeviceOwnerApp(c.packageName)
    }.getOrDefault(false)

    /**
     * The kiosk as emHUB wants it (called every half second; does something only on a change). As the device owner the
     * app pins itself without asking and hides home, recents, the status bar and the power menu; a pin the visitor's
     * helper set by hand (screen pinning) is left alone.
     */
    fun applyKiosk(activity: Activity) {
        if (!isOwner(activity)) { kioskOn = false; return }
        val dpm = activity.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        val admin = android.content.ComponentName(activity, DemoAdmin::class.java)
        val am = activity.getSystemService(android.app.ActivityManager::class.java)
        val state = am.lockTaskModeState
        val want = kioskWanted(control.value)
        runCatching {
            if (want && state == android.app.ActivityManager.LOCK_TASK_MODE_NONE) {
                dpm.setLockTaskPackages(admin, arrayOf(activity.packageName))
                if (android.os.Build.VERSION.SDK_INT >= 28) dpm.setLockTaskFeatures(admin, android.app.admin.DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                dpm.setStatusBarDisabled(admin, true)
                dpm.setKeyguardDisabled(admin, true)  // the power button's wake-up lands in the demo, not on a lock screen
                activity.startLockTask()
            } else if (!want && state == android.app.ActivityManager.LOCK_TASK_MODE_LOCKED) {
                activity.stopLockTask()
                dpm.setStatusBarDisabled(admin, false)
                dpm.setKeyguardDisabled(admin, false)
            }
        }
        val on = am.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_LOCKED
        if (on != kioskOn) { kioskOn = on; sendState() }
    }

    /** The screen on again at once (FULL_WAKE_LOCK is old, but the only lock that switches the screen itself on). */
    @Suppress("DEPRECATION")
    private fun wakeUp(c: Context) = runCatching {
        c.getSystemService(android.os.PowerManager::class.java).newWakeLock(
            android.os.PowerManager.FULL_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or android.os.PowerManager.ON_AFTER_RELEASE,
            "planride:demo-lock"
        ).acquire(1_000)
    }

    /** No longer the device owner (the kiosk can't come back); the next step is up to whoever holds the phone. */
    fun giveUpOwner(c: Context) {
        if (!isOwner(c)) return
        val dpm = c.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        runCatching { dpm.setStatusBarDisabled(android.content.ComponentName(c, DemoAdmin::class.java), false) }
        @Suppress("DEPRECATION") runCatching { dpm.clearDeviceOwnerApp(c.packageName) }
        sendState()
    }

    /** emHUB's "Remove the app": kiosk off, the owner given up, and Android's uninstall dialog (one tap: OK). */
    fun remove(activity: Activity) {
        removeAsk.value = false
        runCatching { if (kioskOn) activity.stopLockTask() }
        giveUpOwner(activity)
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${activity.packageName}")))
        }
    }

    /**
     * emHUB's "Update the app": the uploaded build, checked against its SHA-256, installed with the package installer:
     * by itself as the device owner (the app restarts, DemoAdmin.Back opens it), otherwise Android asks on screen.
     */
    private fun maybeUpdate(c: Context, next: Control) {
        if (ownSha.isBlank()) {
            ownSha = "…"
            Thread {
                ownSha = runCatching {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    java.io.File(c.applicationInfo.sourceDir).inputStream().use { input ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) }
                    }
                    digest.digest().joinToString("") { "%02x".format(it) }
                }.getOrDefault("")
            }.start()
            return
        }
        // The last Update tried is kept in files/ (an update keeps it): each press installs once.
        val tried = store(c).getProperty("updateTried")?.toLongOrNull() ?: 0L
        if (next.updateAt <= tried || next.apkSha.isBlank() || ownSha == "…" || next.apkSha == ownSha) return
        save(c, "updateTried", next.updateAt.toString())
        Thread {
            try {
                updating = "downloading"; sendState()
                val file = java.io.File(c.cacheDir, "demo-update.apk")
                com.ksjd.testem.hub.HubClient.demoApk(file)
                val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
                if (sha != next.apkSha) throw java.io.IOException("the download doesn't match the upload")
                updating = "installing"; sendState()
                val installer = c.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                    .apply { setAppPackageName(c.packageName) }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    session.openWrite("demo.apk", 0, file.length()).use { out -> file.inputStream().use { it.copyTo(out) }; session.fsync(out) }
                    val flags = android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (android.os.Build.VERSION.SDK_INT >= 31) android.app.PendingIntent.FLAG_MUTABLE else 0)
                    val answer = android.app.PendingIntent.getBroadcast(c, id, Intent(c, DemoAdmin.Installed::class.java), flags)
                    session.commit(answer.intentSender)
                }
                file.delete()
            } catch (e: Exception) {
                updating = "failed: ${e.message.orEmpty().take(60)}"
                sendState()
            }
        }.start()
    }

    /** The secret way to the PIN, also while touch is locked: five taps in the top left corner (72 dp) within 4 s. */
    private val cornerTaps = ArrayDeque<Long>()
    private fun cornerTap(event: MotionEvent) {
        val edge = 72 * android.content.res.Resources.getSystem().displayMetrics.density
        if (event.rawX > edge || event.rawY > edge) { cornerTaps.clear(); return }
        val t = SystemClock.elapsedRealtime()
        cornerTaps.addLast(t)
        while (cornerTaps.isNotEmpty() && t - cornerTaps.first() > 4_000) cornerTaps.removeFirst()
        if (cornerTaps.size >= 5) { cornerTaps.clear(); unlockAsk.value = true }
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
            if (event.actionMasked == MotionEvent.ACTION_DOWN) cornerTap(event)
            // emHUB's touch lock: the phone only shows; the PIN dialog (five corner taps) still takes touches.
            if (lockWanted(control.value) && !unlockAsk.value) {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) lockedTap.value = SystemClock.elapsedRealtime()
                return true
            }
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
            if (lockWanted(control.value) && !unlockAsk.value) return true
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
        resetDone = lastTouch
        generation.value++
    }
}
