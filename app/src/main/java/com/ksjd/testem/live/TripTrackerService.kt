package com.ksjd.testem.live

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.ksjd.testem.COMMUNITY_AUTO
import com.ksjd.testem.COMMUNITY_BUTTONS
import com.ksjd.testem.CredentialsManager
import com.ksjd.testem.MainActivity
import com.ksjd.testem.hub.HubClient
import com.ksjd.testem.R
import com.ksjd.testem.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * A bus of the journey the user builds from boards: what to follow, and the same bus as a
 * planner leg with its stop positions, so the journey can be starred or made a regular ride.
 */
data class DraftLeg(
    val trip: TrackedTrip,
    val segment: com.ksjd.testem.TimetableSegment?,
    val from: Pair<Double, Double>?,
    val to: Pair<Double, Double>?
)

/** What the user asked to follow. */
data class TrackedTrip(
    val ref: TripRef,
    /** Platforms of the stop where the user gets on (empty = already on board). */
    val boardingPlatformIds: List<Int>,
    /** stopOrder where the user gets off, null = ride to the end. */
    val alightOrder: Int? = null,
    /** The buses after this one on a journey with changes; followed one after another. */
    val nextLegs: List<TrackedTrip> = emptyList(),
    /** When it leaves the user's stop (epoch ms), 0 = unknown: a later bus is followed live only shortly before (EM-0054). */
    val departsAtMs: Long = 0L,
    /** EM-0067: where the user gets on and off and when it's there, to show the whole journey (null = unknown). */
    val fromStop: String? = null,
    val toStop: String? = null,
    val arrivesAtMs: Long = 0L
)

/** The live notification's texts: title, body and the short countdown ("5 min"). */
data class Shown(val title: String, val text: String, val countdown: String)

/** The followed bus as the notification shows it, for the home screen's live card. */
data class FollowView(
    val trip: TrackedTrip,
    val detail: TripDetail,
    val progress: TripProgress,
    /** "You'll make it" etc., when the catch estimate is on. */
    val catchLine: String?,
    /** The bus's position is really known (vehicle, riders), not only the timetable. */
    val live: Boolean,
    /** Exactly what the live notification shows (the widget mirrors it). */
    val shown: Shown = Shown("", "", ""),
    /** 2.17.0: the rider is on the bus past their stop: they can say how full it is (again and again). */
    val canReportCrowd: Boolean = false,
    /** Said how full it is in the last few minutes (a thank-you shows). */
    val crowdSent: Boolean = false
)

/** Shared between the tracking service and the UI. */
object TripTracking {
    private val _active = MutableStateFlow<TrackedTrip?>(null)
    val active: StateFlow<TrackedTrip?> = _active
    private val _view = MutableStateFlow<FollowView?>(null)
    val view: StateFlow<FollowView?> = _view
    internal fun publish(context: Context, value: FollowView?) { _view.value = value; refreshWidget(context) }

    /** This trip's alerts (newest last), listed on the watch: the notifications reach it bridged from the phone. */
    data class Alert(val atMs: Long, val title: String, val body: String, val key: String = "")
    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    val alerts: StateFlow<List<Alert>> = _alerts
    internal fun recordAlert(context: Context, key: String, title: String, body: String) {
        _alerts.value = (_alerts.value + Alert(System.currentTimeMillis(), title, body, key)).takeLast(10)
        refreshWidget(context)
    }

    /** The follow notification's buttons right now ("Bus is here", "At <stop>"…): the watch shows and presses them too. */
    @Volatile internal var buttons: List<androidx.core.app.NotificationCompat.Action> = emptyList()

    /** A button pressed on the watch: the same as on the phone's notification. */
    fun pressButton(index: Int) {
        runCatching { buttons.getOrNull(index)?.actionIntent?.send() }
    }
    private val widgetScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** The watch settings changed on the phone: send the followed bus again with them. */
    fun refreshWatch(context: Context) {
        refreshWidget(context)
        context.applicationContext.let { app -> widgetScope.launch { WatchSync.sendTheme(app) } }
    }

    /** The home-screen widget (EM-0017) and the watch app (EM-0041) show the followed bus too. */
    private fun refreshWidget(context: Context) {
        val app = context.applicationContext
        widgetScope.launch { com.ksjd.testem.widget.PlanRideWidget.refresh(app) }
        widgetScope.launch { WatchSync.send(app, _active.value, _view.value) }
    }
    /**
     * EM-0100: the other followed buses, soonest first. The one leaving first is the live one ([active]: notification,
     * home card, widget, watch); when it ends or is stopped, the next one takes over.
     */
    private val _others = MutableStateFlow<List<TrackedTrip>>(emptyList())
    val others: StateFlow<List<TrackedTrip>> = _others
    private var othersLoaded = false
    private const val QUEUE_PREFS = "trip_queue"
    /** ponytail: at most this many wait their turn; raise it if somebody really follows more. */
    private const val QUEUE_MAX = 5
    /** A waiting bus that left this long ago is forgotten. */
    private const val QUEUE_LATE_MS = 10 * 60_000L

    private fun loadOthers(context: Context) {
        if (othersLoaded) return
        othersLoaded = true
        _others.value = context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).getString("trips", null)?.let { json ->
            runCatching {
                com.google.gson.Gson().fromJson<List<TrackedTrip>>(json, object : com.google.gson.reflect.TypeToken<List<TrackedTrip>>() {}.type)
            }.getOrNull()
        }.orEmpty()
    }

    private fun saveOthers(context: Context, trips: List<TrackedTrip>) {
        othersLoaded = true
        if (trips == _others.value) return
        _others.value = trips
        context.getSharedPreferences(QUEUE_PREFS, Context.MODE_PRIVATE).edit().putString("trips", com.google.gson.Gson().toJson(trips)).apply()
    }

    /** Soonest first, each bus once (the first mention wins); one whose time isn't known counts as leaving now. Pure logic. */
    internal fun soonestFirst(trips: List<TrackedTrip>): List<TrackedTrip> =
        trips.distinctBy { it.ref }.sortedBy { FollowLater.departureMs(it) ?: 0L }

    /** Pure logic of [takeNext]: the waiting buses still worth following. */
    internal fun stillAhead(trips: List<TrackedTrip>, now: Long): List<TrackedTrip> =
        trips.filter { (FollowLater.departureMs(it) ?: now) > now - QUEUE_LATE_MS }

    /** The live bus is over: the next followed one (taken off the list), if any is still to come. */
    internal fun takeNext(context: Context, now: Long = System.currentTimeMillis()): TrackedTrip? {
        loadOthers(context)
        val ahead = stillAhead(_others.value, now)
        saveOthers(context, ahead.drop(1))
        return ahead.firstOrNull()
    }

    /** EM-0100: stops following the bus [ref], live or waiting its turn. */
    fun stop(context: Context, ref: TripRef) {
        loadOthers(context)
        if (_active.value?.ref == ref) stop(context) else saveOthers(context, _others.value.filterNot { it.ref == ref })
    }

    internal fun set(context: Context, value: TrackedTrip?) {
        loadOthers(context)
        if (value == null) _alerts.value = emptyList()
        // A later bus that's planned stays followed when the live one ends (EM-0054).
        _active.value = value ?: if (FollowLater.planned.value != null) FollowLater.restore(context) else null
        refreshWidget(context)
        if (value == null || _view.value?.trip?.ref != value.ref) _view.value = null
        // Kept on disk so an app update (which kills the service) can pick the bus up again.
        val saved = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE).edit()
        if (value == null) saved.clear() else saved.putString("trip", com.google.gson.Gson().toJson(value)).putLong("at", System.currentTimeMillis())
        saved.apply()
    }

    /**
     * After an app update (the old process is killed without stopping the service) or any other
     * kill: follow the saved bus again. Called on MY_PACKAGE_REPLACED and when the app opens.
     */
    fun resume(context: Context) {
        loadOthers(context)
        if (_active.value != null) return
        FollowLater.restore(context)?.let { planned ->
            // The alarm was missed (inexact, or the phone was off): it's time to follow it live.
            if (FollowLater.startAt(context, planned) == null) runCatching { start(context, planned, now = true) }
            else { _active.value = planned; refreshWidget(context) }
            return
        }
        val saved = context.getSharedPreferences(RESUME_PREFS, Context.MODE_PRIVATE)
        val trip = saved.getString("trip", null)?.let { runCatching { com.google.gson.Gson().fromJson(it, TrackedTrip::class.java) }.getOrNull() }
        // A trip saved long ago is over; the service also stops by itself once the bus has arrived.
        if (trip == null || System.currentTimeMillis() - saved.getLong("at", 0L) > RESUME_MAX_MS) {
            saved.edit().clear().apply()
            // EM-0100: nothing live to pick up, but another followed bus may be waiting its turn.
            takeNext(context)?.let { next -> runCatching { start(context, next) } }
            return
        }
        runCatching { start(context, trip, now = true) }.onFailure { _active.value = null }
    }

    private const val RESUME_PREFS = "trip_resume"
    private const val RESUME_MAX_MS = 6 * 60 * 60 * 1000L

    /**
     * Follows [trip]; one leaving later than the lead time is only planned until then, unless [now] (EM-0054).
     * EM-0100: with another bus already followed, both are; the one leaving first is the live one.
     */
    fun start(context: Context, trip: TrackedTrip, now: Boolean = false) {
        loadOthers(context)
        val current = _active.value
        if (!now && current != null && current.ref != trip.ref) {
            val all = soonestFirst(listOf(trip, current) + _others.value).take(QUEUE_MAX + 1)
            saveOthers(context, all.drop(1))
            if (all.first().ref != current.ref) begin(context, all.first(), FollowLater.startAt(context, all.first()))
            return
        }
        saveOthers(context, _others.value.filterNot { it.ref == trip.ref })
        begin(context, trip, if (now) null else FollowLater.startAt(context, trip))
    }

    private fun begin(context: Context, trip: TrackedTrip, at: Long?) {
        if (at != null) {
            FollowLater.plan(context, trip, at)
            // A bus followed live until now ends; set(null) then keeps the planned one.
            if (_view.value != null) runCatching { context.startService(Intent(context, TripTrackerService::class.java).setAction(TripTrackerService.ACTION_STOP)) }
            _active.value = trip
            refreshWidget(context)
            return
        }
        _active.value = trip
        ContextCompat.startForegroundService(context, TripTrackerService.intent(context, trip))
        FollowLater.cancel(context)
    }

    /**
     * After the community mode or catch setting changes: restart the followed trip so the
     * service gets (or drops) the location type. Must run while the app is in the foreground,
     * or Android won't deliver the rider's GPS once the screen goes off.
     */
    fun refresh(context: Context) {
        if (FollowLater.planned.value == null) _active.value?.let { start(context, it, now = true) }
    }

    /**
     * The app came to the foreground: if the service started without the location type (from an
     * alarm or a restart), add it now, while Android allows it. Nothing else is restarted.
     */
    fun promote(context: Context) {
        if (_active.value == null || FollowLater.planned.value != null) return
        runCatching { context.startService(Intent(context, TripTrackerService::class.java).setAction(TripTrackerService.ACTION_PROMOTE)) }
    }

    /** [stop] is the one tapped: the card's "Get off at" follows it (EM-0089), none when cleared. */
    fun setAlightStop(context: Context, order: Int?, stop: TripStop? = null) {
        val current = _active.value ?: return
        val moved = current.copy(alightOrder = order, toStop = stop?.name, arrivesAtMs = stop?.scheduledMs ?: 0L)
        start(context, moved, now = FollowLater.planned.value == null)
    }

    /** A journey the user is putting together bus by bus (when the planner finds no connection). */
    private val _draft = MutableStateFlow<List<DraftLeg>>(emptyList())
    val draft: StateFlow<List<DraftLeg>> = _draft
    fun addToDraft(leg: DraftLeg) { _draft.value = _draft.value.filter { it.trip.ref != leg.trip.ref } + leg }
    fun clearDraft() { _draft.value = emptyList() }

    /** Follows [legs] as one journey: each bus until its stop, then the next one. */
    fun startJourney(context: Context, legs: List<TrackedTrip>) {
        val first = legs.firstOrNull() ?: return
        start(context, first.copy(nextLegs = legs.drop(1).map { it.copy(nextLegs = emptyList()) }))
    }

    /** 2.17.0: "You may miss line X" tapped: the planner searches from the change stop to the journey's end, from the arrival there. */
    data class Replan(val from: String, val to: String, val atMs: Long)
    val replan = MutableStateFlow<Replan?>(null)
    const val EXTRA_REPLAN_FROM = "replan_from"
    const val EXTRA_REPLAN_TO = "replan_to"
    const val EXTRA_REPLAN_AT = "replan_at"

    /** The follow notification was tapped: the app shows the followed bus's stops over the home tab. */
    const val EXTRA_SHOW_FOLLOWED = "show_followed"
    private val _showRequested = MutableStateFlow(false)
    val showRequested: StateFlow<Boolean> = _showRequested
    fun requestShow() { _showRequested.value = true }
    fun showHandled() { _showRequested.value = false }

    /** 2.17.0: the arrival shared as a link while following (the service keeps it current and ends it on arrival). */
    val eta = MutableStateFlow<com.ksjd.testem.hub.HubClient.EtaLink?>(null)

    /** 2.17.0: how full the followed bus is (1 seats free, 2 standing, 3 full), from the rider on it. */
    fun reportCrowd(context: Context, level: Int) {
        runCatching { context.startService(Intent(context, TripTrackerService::class.java).setAction(TripTrackerService.ACTION_CROWD).putExtra("level", level)) }
    }

    /** EM-0072: on foot or by car changed on the card: the estimate again now (and the drive asked again). */
    fun catchModeChanged(context: Context) {
        if (_view.value == null) return
        runCatching { context.startService(Intent(context, TripTrackerService::class.java).setAction(TripTrackerService.ACTION_CATCH_MODE)) }
    }

    /** Stops following the live bus; the next followed one, if any, takes over (EM-0100). */
    fun stop(context: Context) {
        val planned = FollowLater.planned.value != null
        FollowLater.cancel(context)
        _active.value = null
        if (planned && _view.value == null) {
            set(context, null)
            takeNext(context)?.let { next -> runCatching { start(context, next) } }
            return
        }
        runCatching { context.startService(Intent(context, TripTrackerService::class.java).setAction(TripTrackerService.ACTION_STOP)) }
    }
}

/**
 * Follows one bus trip and keeps an ongoing notification up to date. On Android 16
 * the notification is a promoted Live Update with a stop-by-stop progress bar.
 */
class TripTrackerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var loop: Job? = null
    private lateinit var repo: LiveRepository
    private var trip: TrackedTrip? = null
    private val alertsSent = mutableSetOf<String>()
    private var startedAt = 0L
    private lateinit var prefs: CredentialsManager

    // Community reports: a fresh random id per followed bus, so reports can't be linked across trips.
    private var reporterId = UUID.randomUUID().toString()
    /** The rider's own report (delay seconds, when); wins until the pooled delay is newer. */
    private var localDelay: Pair<Int, Long>? = null
    private var lastDetail: TripDetail? = null
    private var reportNote: String? = null
    /** Waiting at the stop: after "Bus is here" the button becomes "Bus is leaving", then goes away. */
    private var herePressed = false
    private var leavingPressed = false

    // Catch estimate: location stays on the phone.
    private var stopLatLon: Pair<Double, Double>? = null
    private var stopLookupDone = false
    private var lastFix: android.location.Location? = null
    /** EM-0072: the router's drive to the stop (by car), and where and when it was asked. */
    private var driveSeconds: Int? = null
    private var driveFrom: android.location.Location? = null
    private var driveAt = 0L
    /** Walking to the stop: when to look at the location again (GPS is off in between). */
    private var nextFixAt = 0L
    /** EM-0099: the location check running beside the follow loop (one at a time). */
    private var locationJob: Job? = null
    private var stopPass = StopPassDetector()
    private var lastArrivalStop = -1
    /** EM-0059: the stop the rider's phone is standing at now (automatic mode), -1 when moving or not at one. */
    private var standingAt = -1

    // Automatic mode: the rider's phone on the bus marks where it is (only the stop is sent).
    private var riderListener: android.location.LocationListener? = null
    private var routeCoords: List<Pair<Double, Double>?>? = null
    private var routeCoordsLoading = false
    private var lastRiderIndex = -1f
    /** The phone moves along the route past the boarding stop: the rider is on the bus. */
    private var ridingBus = false
    /** EM-0096: where the last location check put the phone on the route (-1: not on it yet). */
    private var checkIndex = -1f
    /** EM-0096: from one location check to the next the phone went on along the route past the stop: GPS on, it may be on the bus. */
    private var leftOnBus = false
    /** EM-0046: the stop where the rider's phone got off the bus (stands there, or reached their stop); -1 still on. */
    private var riderOffAt = -1
    /** EM-0104: the last stop the phone saw the bus leave or pass (-1: none yet), and how late it was there. */
    private var lastLeftStop = -1
    private var stopDelay: Int? = null
    /** EM-0104: how late the bus was at each stop behind it; the stop list keeps those times as they were. */
    private val passedDelay = HashMap<Int, Int>()
    /** Since when the rider's phone stands still (0: moving). */
    private var standingSince = 0L
    /** What the live notification shows right now; the widget shows the same. */
    private var shown = Shown("", "", "")
    /** The rider's phone stands at their stop after the bus was due: it hasn't come yet (EM-0033). */
    private var waitingAtStop = false
    /** The rider said the bus already left ("It already left"): stop holding it at the stop. */
    private var busGone = false
    private var lastRiderReportAt = 0L
    /** When the rider last said how full the bus is (0: not on this bus). */
    private var crowdSentAt = 0L
    private var localFromGps = false
    /** Android granted the location service type; without it no GPS arrives while the app is closed. */
    private var hasLocationType = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        repo = LiveRepository(this)
        prefs = CredentialsManager(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            finish()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_RIDE) {
            startRide(intent.getStringExtra(EXTRA_RIDE).orEmpty(), intent.getLongExtra(EXTRA_DEPARTURE, 0L))
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CATCH_MODE) {
            driveAt = 0L
            nextFixAt = 0L
            trip?.let { t -> lastDetail?.let { redraw(t, it) } }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_GONE) {
            // "It already left": the rider missed it or it went early; follow the timetable/live data again.
            busGone = true
            waitingAtStop = false
            localDelay = null
            // EM-0100: missed, and another followed bus is waiting: on to that one.
            if (TripTracking.others.value.isNotEmpty()) {
                finish()
                return START_NOT_STICKY
            }
            trip?.let { t -> lastDetail?.let { redraw(t, it) } }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CROWD) {
            val level = intent.getIntExtra("level", 0)
            val key = lastDetail?.communityKey
            val now = System.currentTimeMillis()
            // ponytail: a tap in the 20 s after the last one is dropped (a double tap is one report)
            if (level in 1..3 && key != null && now - crowdSentAt > 20_000L) {
                crowdSentAt = now
                scope.launch {
                    val crowd = runCatching { HubClient.reportCrowd(key, reporterId, level) }.getOrNull()
                    if (crowd == null) {
                        // EM-0086: not sent (no signal, emHUB busy): say so and let the next tap try again.
                        crowdSentAt = 0L
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            android.widget.Toast.makeText(this@TripTrackerService, R.string.crowd_failed, android.widget.Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                    lastDetail = lastDetail?.copy(crowd = crowd)
                    trip?.let { t -> lastDetail?.let { redraw(t, it) } }
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REPORT) {
            // Not redelivered after a restart: an old tap would report the wrong time.
            reportPosition(intent.getIntExtra("stop", -1), leaving = intent.getBooleanExtra("leaving", false))
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_PROMOTE) {
            val current = trip
            if (current != null && !hasLocationType && wantsLocation()) {
                val shown = getSystemService(android.app.NotificationManager::class.java)
                    ?.activeNotifications?.firstOrNull { it.id == NOTIFICATION_ID }?.notification
                startInForeground(shown ?: placeholderNotification(current))
            }
            return START_NOT_STICKY
        }
        val requested = intent?.let(::readTrip) ?: run {
            finish()
            return START_NOT_STICKY
        }
        if (trip?.ref != requested.ref) resetTripState()
        trip = requested
        TripTracking.set(this@TripTrackerService, requested)
        startInForeground(placeholderNotification(requested))
        loop?.cancel()
        loop = scope.launch { track() }
        return START_REDELIVER_INTENT
    }

    /**
     * A recurring ride is due: find that day's bus in the timetable, then follow it like any journey.
     * Something already followed wins; the ride is skipped with a note.
     */
    private fun startRide(rideId: String, departureMs: Long) {
        val ride = prefs.getRecurringRides().firstOrNull { it.id == rideId }
        if (ride == null || trip != null) {
            if (ride != null) notifyAlert("ride_busy_$rideId", getString(R.string.rides_busy_title, ride.label), getString(R.string.rides_busy_body))
            if (trip == null) finish()
            return
        }
        val departure = java.time.Instant.ofEpochMilli(departureMs).atZone(com.ksjd.testem.SK_ZONE)
        startInForeground(
            NotificationCompat.Builder(this, CHANNEL_LIVE).setSmallIcon(R.drawable.ic_stat_bus).setOngoing(true).setSilent(true)
                .setContentTitle(ride.label).setContentText(getString(R.string.rides_finding, Format.clock(departureMs)))
                .setContentIntent(contentIntent()).addAction(stopAction()).build()
        )
        loop?.cancel()
        loop = scope.launch {
            val legs = runCatching { com.ksjd.testem.rides.RideScheduler.findLegs(ride, departure) }.getOrNull()
            if (legs.isNullOrEmpty()) {
                notifyAlert("ride_missing", getString(R.string.rides_missing_title, ride.label), getString(R.string.rides_missing_body, Format.clock(departureMs)))
                finish()
                return@launch
            }
            val first = legs.first().copy(nextLegs = legs.drop(1))
            resetTripState()
            trip = first
            TripTracking.set(this@TripTrackerService, first)
            track()
        }
    }

    /** Forget everything about the previous bus. */
    private fun resetTripState() {
        locationJob?.cancel()  // a check started for the trip before must not write into this one
        locationJob = null
        alertsSent.clear()
        startedAt = System.currentTimeMillis()
        reporterId = UUID.randomUUID().toString()
        localDelay = null
        lastDetail = null
        reportNote = null
        herePressed = false
        leavingPressed = false
        stopLatLon = null
        stopLookupDone = false
        stopRiderTracking()
        routeCoords = null
        lastRiderIndex = -1f
        ridingBus = false
        checkIndex = -1f
        leftOnBus = false
        riderOffAt = -1
        lastLeftStop = -1
        stopDelay = null
        passedDelay.clear()
        standingSince = 0L
        waitingAtStop = false
        busGone = false
        lastRiderReportAt = 0L
        crowdSentAt = 0L
        localFromGps = false
        nextFixAt = 0L
        stopPass = StopPassDetector()
        lastArrivalStop = -1
    }

    /** Journey: the bus reached the user's change stop, so follow the next one. */
    private fun changeTo(next: TrackedTrip, rest: List<TrackedTrip>, changeStop: String) {
        val leg = next.copy(nextLegs = rest)
        trip = leg
        TripTracking.set(this@TripTrackerService, leg)
        resetTripState()
        notifyAlert("change", getString(R.string.journey_change_title, leg.ref.line), getString(R.string.journey_change_body, changeStop))
        NotificationManagerCompat.from(this).notifySafely(NOTIFICATION_ID, placeholderNotification(leg))
    }

    override fun onDestroy() {
        stopRiderTracking()
        scope.cancel()
        TripTracking.set(this@TripTrackerService, null)
        super.onDestroy()
    }

    private fun startInForeground(notification: android.app.Notification) {
        val special = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        // Location type keeps the catch estimate and automatic mode working with the screen off.
        val location = if (wantsLocation() && Locations.hasPermission(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else 0
        hasLocationType = runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special or location) }
            .onFailure {
                // Started from the background without "all the time" location: GPS only while the app is open.
                Log.w(GPS_TAG, "location type refused (background=${Locations.hasBackground(this)}): ${it.message}")
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, special)
            }.isSuccess && location != 0
        if (location != 0 && hasLocationType) Log.i(GPS_TAG, "foreground with location type")
    }

    private fun wantsLocation() = prefs.getCatchEnabled() || prefs.getCommunityMode() == COMMUNITY_AUTO

    /** The rider's own report wins until the pooled community delay is newer. */
    private fun withLocalDelay(detail: TripDetail): TripDetail {
        val (delay, at) = localDelay ?: return detail
        val communityAt = detail.community?.updatedAtMs ?: 0L
        // EM-0104: on the bus, what this phone measured stays until it measures again. emHUB's pooled answer to the
        // phone's own "arrived" report is newer, but counts an early bus as on time (it waits for its departure), and
        // the card fell back to "on time" at every stop.
        val mine = localFromGps && ridingBus && riderOffAt < 0 && System.currentTimeMillis() - at <= OWN_DELAY_MS
        return if (mine || at >= communityAt) detail.withDelay(delay) else detail
    }

    /** Buttons for riders who chose them, on any bus whose position nobody knows live. */
    private fun buttonsOn(detail: TripDetail) = prefs.getCommunityMode() == COMMUNITY_BUTTONS && HubClient.isConfigured &&
        detail.communityKey != null && !detail.positionSource.isLive

    // ------------------------------------------------------------ automatic mode (rider GPS)

    private fun updateRiderTracking(current: TrackedTrip, detail: TripDetail, p: TripProgress) {
        val wanted = prefs.getCommunityMode() == COMMUNITY_AUTO && HubClient.isConfigured &&
            detail.communityKey != null && Locations.hasPrecise(this)
        if (!wanted) {
            if (riderListener != null || prefs.getCommunityMode() == COMMUNITY_AUTO) {
                Log.i(GPS_TAG, "off: hub=${HubClient.isConfigured} key=${detail.communityKey != null} precise=${Locations.hasPrecise(this)}")
            }
            return stopRiderTracking()
        }
        if (routeCoords == null && !routeCoordsLoading) {
            routeCoordsLoading = true
            scope.launch {
                routeCoords = runCatching { repo.withCoordinates(current.ref, detail) }
                    .onFailure { Log.w(GPS_TAG, "stop coordinates failed", it) }.getOrNull()
                    ?.stops?.map { s -> s.lat?.let { it to s.lon!! } }
                Log.i(GPS_TAG, "stop coordinates: ${routeCoords?.count { it != null }} of ${detail.stops.size}")
                routeCoordsLoading = false
            }
        }
        // Live GPS only on the bus, or at the stop from 4 min before the bus can come (earlier when
        // it's early or usually is). Walking there, or earlier, occasional location checks do.
        val nearStop = stopLatLon == null || lastFix?.let { f ->
            stopLatLon?.let { (lat, lon) -> LiveRepository.distanceMeters(f.latitude, f.longitude, lat, lon) <= NEAR_STOP_M }
        } == true
        val inWindow = System.currentTimeMillis() >= earliestBoardingMs(detail, p) - LIVE_GPS_BEFORE_MS
        // EM-0078: the phone already rides the bus (it may be early, or the data late): keep listening.
        if (!p.onBoard && !ridingBus && !leftOnBus && !(inWindow && nearStop)) {
            if (riderListener != null) Log.i(GPS_TAG, "not at the stop or too early: live GPS off")
            return stopRiderTracking()
        }
        if (riderListener != null) return
        val manager = getSystemService(android.location.LocationManager::class.java) ?: return
        val listener = android.location.LocationListener { fix ->
            Shares.publish(this@TripTrackerService, fix) // to whoever this bus is shared with by a code
            onRiderFix(fix)
        }
        runCatching {
            @Suppress("MissingPermission")
            manager.requestLocationUpdates(android.location.LocationManager.GPS_PROVIDER, 5_000L, 0f, listener, android.os.Looper.getMainLooper())
            riderListener = listener
            Log.i(GPS_TAG, "listening")
        }.onFailure { Log.w(GPS_TAG, "location updates refused", it) }
    }

    private fun stopRiderTracking() {
        val listener = riderListener ?: return
        runCatching { getSystemService(android.location.LocationManager::class.java)?.removeUpdates(listener) }
        riderListener = null
    }

    /**
     * A fix from the rider's phone. It says where the bus is only once they are on it:
     * close to the route, past their boarding stop and moving at vehicle speed.
     */
    private fun onRiderFix(fix: android.location.Location) {
        val current = trip ?: return
        val detail = lastDetail ?: return
        val coords = routeCoords ?: return Log.i(GPS_TAG, "fix: no stop coordinates yet").let { }
        if (coords.count { it != null } < 2) {
            // Lookup failed (offline, emhub down): try again on the next tracking round.
            Log.i(GPS_TAG, "fix: only ${coords.count { it != null }} of ${coords.size} stops have coordinates, retrying")
            routeCoords = null
            return
        }
        val key = detail.communityKey ?: return
        lastFix = fix
        if (!fix.hasAccuracy() || fix.accuracy > 50f) return Log.i(GPS_TAG, "fix: accuracy ${fix.accuracy} m").let { }
        val p = TripProgress.from(withLocalDelay(detail), current)
        val (index, meters) = RoutePosition.snap(fix.latitude, fix.longitude, coords, RoutePosition.RIDER_GPS_MAX_M, p.position)
            ?: return RoutePosition.snap(fix.latitude, fix.longitude, coords, Double.MAX_VALUE).let { nearest ->
                Log.i(GPS_TAG, "fix: off the route, nearest %.0f m at stop %.1f".format(nearest?.second ?: -1.0, nearest?.first ?: -1f))
            }
        val boarding = (p.boardingIndex ?: 0).toFloat()
        // A standing bus still sends fixes (no minimum distance), so a low speed means it stopped.
        val moving = if (fix.hasSpeed()) fix.speed >= MOVING_MPS else lastRiderIndex >= 0f && index > lastRiderIndex + 0.05f
        lastRiderIndex = maxOf(lastRiderIndex, index)
        // EM-0091: a long wait at a stop (7 min at Krupina, aut.st.) looked like getting off and hid the seat report;
        // driving on along the route means the rider is still on the bus.
        if (riderOffAt >= 0 && moving && index > riderOffAt + 0.3f) {
            Log.i(GPS_TAG, "still on the bus after stop $riderOffAt")
            riderOffAt = -1
        }
        if (moving && index > boarding + 0.05f && !ridingBus) {
            ridingBus = true
            Log.i(GPS_TAG, "riding the bus: no more catch estimates")
        }
        Log.i(GPS_TAG, "fix: at %.2f (%.0f m off), boarding %.0f, speed %.1f".format(index, meters, boarding, fix.speed))
        val now = System.currentTimeMillis()
        val scheduled = detail.stops.map { it.scheduledMs }
        fun send(stopIndex: Int, scheduledMs: Long, kind: String = "gps") {
            lastRiderReportAt = now
            scope.launch {
                runCatching {
                    HubClient.reportPosition(key, current.ref.line, stopIndex, detail.stops[stopIndex].name, scheduledMs, reporterId, kind = kind)
                }.onFailure { Log.w(GPS_TAG, "report failed", it) }.getOrNull()?.let { pooled -> lastDetail = lastDetail?.copy(community = pooled) }
            }
        }
        // Standing within a few tens of metres of a stop: the bus is stopped there.
        val nearest = kotlin.math.round(index).toInt().coerceIn(0, detail.stops.lastIndex)
        val atStop = coords.getOrNull(nearest)?.let { (lat, lon) ->
            LiveRepository.distanceMeters(fix.latitude, fix.longitude, lat, lon) <= STOP_RADIUS_M
        } == true
        standingAt = if (!moving && atStop) nearest else -1
        // EM-0033: the rider stands at their stop after the bus was due, so it hasn't passed yet, whatever the
        // timetable says: keep it at the stop (late) and ask in the notification whether it's here or gone.
        val boardIdx = p.boardingIndex
        // EM-0073: at the rider's own stop a wider circle: its recorded point can be tens of metres off and GPS drifts in
        // a shelter or a bus (74 m in the report), and missing it let the bus run on by the timetable while it stood there.
        val atBoarding = atStop || (boardIdx != null && nearest == boardIdx && coords.getOrNull(boardIdx)?.let { (lat, lon) ->
            LiveRepository.distanceMeters(fix.latitude, fix.longitude, lat, lon) <= BOARDING_RADIUS_M
        } == true)
        if (boardIdx != null && !ridingBus && !busGone && atBoarding && nearest == boardIdx && !moving &&
            now > scheduled[boardIdx] && (waitingAtStop || p.position > boardIdx + 0.05f)) {
            if (!waitingAtStop) Log.i(GPS_TAG, "rider waits at the stop: the bus hasn't come yet")
            waitingAtStop = true
            localDelay = ((now - scheduled[boardIdx]) / 1000L).toInt() to now
            localFromGps = true
            redraw(current, detail)
            return
        }
        if (waitingAtStop && (moving || !atBoarding)) waitingAtStop = false
        // Left (after standing there) or drove past a stop: the exact delay there (timetable times are departures).
        val left = stopPass.onFix(index, moving, now, atStop)?.takeIf { it.stop >= boarding.toInt() && it.stop <= detail.stops.lastIndex }
        if (left != null) {
            val delay = ((left.atMs - scheduled[left.stop]) / 1000L).toInt()
            val kind = if (left.stopped) "departed" else "passed"
            Log.i(GPS_TAG, "$kind stop ${left.stop} (${detail.stops[left.stop].name}) %+d s".format(delay))
            localDelay = delay to now
            localFromGps = true
            lastLeftStop = maxOf(lastLeftStop, left.stop)
            stopDelay = delay
            passedDelay[left.stop] = delay
            redraw(current, detail)
            // The hub takes "now" from its own clock, so shift the stop time by how long ago it left.
            return send(left.stop, scheduled[left.stop] + (now - left.atMs), kind)
        }
        // Stopped at a stop after the boarding one (so the rider is on the bus): it arrived there.
        // The hub counts an early arrival as on time: the bus waits for its departure.
        standingSince = if (moving) 0L else standingSince.takeIf { it > 0L } ?: now
        // EM-0046: standing at their own stop (or the last one), or long at any other: the rider got off. Without this the
        // standing phone kept making the bus later (EM-0040) and following never ended.
        val endIndex = p.alightIndex ?: detail.stops.lastIndex
        if (ridingBus && riderOffAt < 0 && atStop && !moving && (nearest >= endIndex || now - standingSince > GOT_OFF_STANDING_MS)) {
            riderOffAt = nearest
            Log.i(GPS_TAG, "rider got off at stop $nearest (${detail.stops[nearest].name})")
        }
        if (!moving && atStop && nearest > boarding.toInt() && nearest > lastArrivalStop) {
            lastArrivalStop = nearest
            Log.i(GPS_TAG, "arrived at stop $nearest (${detail.stops[nearest].name})")
            return send(nearest, scheduled[nearest], "arrival")
        }
        if (index < boarding + 0.05f) return
        if (!moving) {
            // EM-0040: standing (traffic, a long stop) the bus gets later by the minute, though the timetable moves on.
            // Only later, never earlier: a bus waiting at a stop for its departure isn't early.
            val standing = RoutePosition.delayAt(index, scheduled, now)
            if (ridingBus && riderOffAt < 0 && standing > (localDelay?.first ?: detail.delaySeconds ?: 0)) {
                localDelay = standing to now
                localFromGps = true
                redraw(current, detail)
            }
            return
        }
        // EM-0104: between stops the place is measured along the straight line from stop to stop, which is rough: the
        // minutes to the next stop went up and down with every fix. The delay measured at the last stop stays, unless
        // the bus is clearly later than that (stuck on the way).
        val between = RoutePosition.delayAt(index, scheduled, now)
        val held = stopDelay
        localDelay = (if (held != null && between - held < BETWEEN_STOPS_LATER_S) held else between) to now
        localFromGps = true
        redraw(current, detail)
        // Between stops: every 15 s.
        if (now - lastRiderReportAt < 15_000L) return
        send(index.toInt().coerceIn(0, detail.stops.lastIndex), RoutePosition.referenceAt(index, scheduled, now))
    }

    /** "The bus is at stop [index] right now" ([leaving]: it is pulling out, the timetable's departure moment). */
    private fun reportPosition(index: Int, leaving: Boolean = false) {
        val current = trip ?: return
        val detail = lastDetail ?: return
        val stop = detail.stops.getOrNull(index) ?: return
        val key = detail.communityKey ?: return
        // Buttons mode, or the question asked while the rider waits at the stop (any mode, EM-0033).
        if ((prefs.getCommunityMode() != COMMUNITY_BUTTONS && !waitingAtStop) || !HubClient.isConfigured) return
        val now = System.currentTimeMillis()
        localFromGps = false
        if (leaving) leavingPressed = true else herePressed = true
        val arrival = !leaving && index == TripProgress.from(detail, current).boardingIndex
        // A bus that is already at the stop before its departure time waits for it: that's
        // "on time", not early. Everything else ("leaving", "at <stop>") is the exact moment.
        val delay = ((now - stop.scheduledMs) / 1000L).toInt().let { if (arrival) it.coerceAtLeast(0) else it }
        localDelay = delay to now
        reportNote = getString(R.string.community_thanks)
        redraw(current, detail)
        scope.launch {
            val result = runCatching {
                HubClient.reportPosition(key, current.ref.line, index, stop.name, stop.scheduledMs, reporterId,
                    kind = if (arrival) "arrival" else "position")
            }
            if (result.isFailure) reportNote = getString(R.string.community_send_failed)
            result.getOrNull()?.let { pooled -> lastDetail = lastDetail?.copy(community = pooled) }
            lastDetail?.let { redraw(current, it) }
        }
    }

    /** Where on the route this phone is, while it rides the bus and its location is fresh; else null (the clock decides). */
    private fun riderIndexNow(): Float? = lastRiderIndex.takeIf {
        ridingBus && riderOffAt < 0 && it >= 0f && lastFix?.let { fix -> System.currentTimeMillis() - fix.time <= FRESH_FIX_MS } == true
    }?.let {
        // EM-0104: a stop the bus left (or arrived at and no longer stands at) is behind it, also while the phone is a
        // few metres past it or off the drawn route: the card went back to "to" that stop as if it had not been there.
        val behind = maxOf(lastLeftStop, if (standingAt < 0) lastArrivalStop else -1)
        if (behind >= 0) maxOf(it, behind + LEFT_STOP_INDEX) else it
    }

    private fun redraw(current: TrackedTrip, raw: TripDetail) {
        val detail = withLocalDelay(raw)
        val progress = TripProgress.from(detail, current, standingAt = standingAt.takeIf { it >= 0 }, riderIndex = riderIndexNow())
        show(current, detail, progress, catchLine(detail, progress))
    }

    /**
     * EM-0104: the stops behind the bus keep the time it was really there. The list shifted every stop by the delay of
     * the moment, so a stop passed 3 minutes late showed "2 min early" once the bus caught up.
     */
    private fun withPassedTimes(detail: TripDetail, progress: TripProgress): TripDetail {
        val delay = detail.delaySeconds
        if (delay != null) for (i in 0 until progress.shownNextIndex.coerceAtMost(detail.stops.size)) passedDelay.getOrPut(i) { delay }
        if (passedDelay.isEmpty()) return detail
        return detail.copy(stops = detail.stops.mapIndexed { i, stop ->
            val was = passedDelay[i]
            if (stop.actualMs == null && was != null && i < progress.shownNextIndex) stop.copy(actualMs = stop.scheduledMs + was * 1000L) else stop
        })
    }

    /** The live notification, and the same texts for the home card and the widget (EM-0025: they must match). */
    private fun show(current: TrackedTrip, detail: TripDetail, progress: TripProgress, catch: String?) {
        shareEta(current, detail, progress)
        val notification = liveNotification(current, detail, progress, catch)
        TripTracking.publish(this, FollowView(current, withPassedTimes(detail, progress), progress, catch, positionKnown(detail), shown,
            canReportCrowd = crowdAllowed(detail, progress), crowdSent = System.currentTimeMillis() - crowdSentAt < 5 * 60_000L))
        NotificationManagerCompat.from(this).notifySafely(NOTIFICATION_ID, notification)
    }

    private var etaSentAt = 0L
    private var etaSent: Pair<Long, Int>? = null

    /**
     * 2.17.0: the shared arrival link: when the rider gets to the end of the journey (this bus's stop, or a later
     * bus's planned arrival), sent when it changes or every 2 minutes.
     */
    private fun shareEta(current: TrackedTrip, detail: TripDetail, p: TripProgress) {
        val link = TripTracking.eta.value ?: return
        val stop = detail.stops.getOrNull(p.alightIndex ?: detail.stops.lastIndex) ?: return
        val arrival = current.nextLegs.lastOrNull()?.arrivesAtMs?.takeIf { it > 0 }
            ?: stop.actualMs ?: (stop.scheduledMs + (detail.delaySeconds ?: 0) * 1000L)
        val now = System.currentTimeMillis()
        val what = arrival to (detail.delaySeconds ?: 0)
        if (what == etaSent && now - etaSentAt < 2 * 60_000L) return
        etaSent = what
        etaSentAt = now
        scope.launch { HubClient.updateEta(link, arrival, what.second, current.ref.line) }
    }

    // ------------------------------------------------------------ catch estimate

    /**
     * Walking to the stop: look at the location only now and then, sooner the closer the
     * user is (see [CatchEstimate.nextCheckMs]). At the stop or on board, live GPS takes over.
     */
    private suspend fun updateLocation(current: TrackedTrip, p: TripProgress) {
        val auto = prefs.getCommunityMode() == COMMUNITY_AUTO
        if (p.onBoard || (!auto && !prefs.getCatchEnabled()) || !Locations.hasPermission(this)) return
        if (!stopLookupDone) {
            stopLookupDone = true
            stopLatLon = repo.boardingLocation(current.ref, lastDetail, current.boardingPlatformIds, p.boardingIndex, p.boardingStopName)
            Log.i(GPS_TAG, "boarding stop location: ${if (stopLatLon != null) "known" else "unknown"}")
        }
        val now = System.currentTimeMillis()
        // An old position must not decide "leave now" / "you won't make it": the rider has likely walked on since
        // (EM-0032). When the estimate from it says so, look again right away instead of at the planned time.
        val stale = lastFix?.let { now - it.time > FRESH_FIX_MS } ?: false
        val urgent = stale && lastDetail?.let { d ->
            catchFor(withLocalDelay(d), p)?.verdict.let { it == CatchEstimate.Verdict.LeaveNow || it == CatchEstimate.Verdict.Miss }
        } == true
        if (riderListener != null || (now < nextFixAt && !urgent)) return
        // GPS alone (no network location: a phone without Google services) needs longer than 10 s for its first fix.
        val fix = Locations.current(this, maxAgeMs = 20_000L, gpsTimeoutMs = 30_000L)
        if (fix != null) lastFix = fix
        // EM-0096: the bus was thought to be still coming, but the phone has gone on down the route: the rider may be on it.
        val onRoute = fix?.takeIf { it.accuracy <= 50f }?.let { f ->
            routeCoords?.let { RoutePosition.snap(f.latitude, f.longitude, it, RoutePosition.RIDER_GPS_MAX_M)?.first }
        }
        if (onRoute != null) {
            if (checkIndex >= 0f && onRoute > checkIndex + 0.05f && onRoute > (p.boardingIndex ?: 0) + 0.05f) {
                leftOnBus = true
                Log.i(GPS_TAG, "went on along the route to %.2f: live GPS on".format(onRoute))
            }
            checkIndex = onRoute
        }
        val meters = fix?.let { f -> stopLatLon?.let { (lat, lon) -> LiveRepository.distanceMeters(f.latitude, f.longitude, lat, lon) } }
        // EM-0072: by car, the drive along the roads from the router; asked again after 300 m or 3 min.
        val stop = stopLatLon
        if (fix != null && stop != null && prefs.getCatchByCar() && (meters ?: 0) > CatchEstimate.CAR_FROM_M &&
            (driveFrom?.distanceTo(fix)?.let { it > 300f } != false || now - driveAt > 3 * 60_000L)) {
            driveSeconds = kotlinx.coroutines.withContext(Dispatchers.IO) { CatchEstimate.driveSeconds(fix.latitude, fix.longitude, stop.first, stop.second) } ?: driveSeconds
            driveFrom = fix
            driveAt = now
        }
        nextFixAt = now + (meters?.let { CatchEstimate.nextCheckMs(it) } ?: CatchEstimate.MIN_RECHECK_MS)
        Log.i(GPS_TAG, "location check: ${meters ?: "?"} m from the stop, next in ${(nextFixAt - now) / 1000} s")
    }

    private fun updateCatch(detail: TripDetail, p: TripProgress): CatchEstimate? {
        if (p.onBoard || !prefs.getCatchEnabled() || !Locations.hasPermission(this) || stopLatLon == null) return null
        return catchFor(detail, p)
    }

    /**
     * When the bus can be at the user's stop at the earliest (see [TripProgress.earliestMs]).
     */
    private fun earliestBoardingMs(detail: TripDetail, p: TripProgress): Long {
        val index = p.boardingIndex ?: return p.boardingExpectedMs
        val scheduled = detail.stops.getOrNull(index)?.scheduledMs ?: return p.boardingExpectedMs
        val known = positionKnown(detail)
        return TripProgress.earliestMs(p.boardingExpectedMs, scheduled, detail.delaySeconds ?: 0, detail.history?.atStop(index)?.takeUnless { known } ?: 0, known)
    }

    private fun catchFor(detail: TripDetail, p: TripProgress): CatchEstimate? {
        val seconds = ((earliestBoardingMs(detail, p) - System.currentTimeMillis()) / 1000L).toInt()
        // "Bus is here" / "Bus is leaving" tapped, or the phone already rides along the route: the rider is with
        // the bus, so never "you won't make it" (EM-0020), whatever the distance to the stop says.
        if (herePressed || leavingPressed || ridingBus) return CatchEstimate.from(0, seconds)
        val fix = lastFix?.takeIf { it.accuracy <= MAX_FIX_ACCURACY_M } ?: return null
        val (lat, lon) = stopLatLon ?: return null
        val meters = LiveRepository.distanceMeters(fix.latitude, fix.longitude, lat, lon)
        return CatchEstimate.from(meters, seconds, prefs.getCatchByCar(), driveSeconds)
    }

    /** Is the bus's real position known (live feed, a rider report or the community)? */
    private fun positionKnown(detail: TripDetail): Boolean =
        (detail.positionSource != PositionSource.Timetable && detail.positionSource != PositionSource.History) || localDelay != null

    /** EM-0072: by car, and far enough that it's a drive (close by it's a walk either way). */
    private fun car(): Boolean = prefs.getCatchByCar() &&
        (lastFix?.let { f -> stopLatLon?.let { (lat, lon) -> LiveRepository.distanceMeters(f.latitude, f.longitude, lat, lon) > CatchEstimate.CAR_FROM_M } } ?: false)

    private fun catchLine(detail: TripDetail, p: TripProgress): String? {
        if (p.onBoard || !prefs.getCatchEnabled()) return null
        val estimate = catchFor(detail, p) ?: return null
        val walk = (estimate.walkSeconds / 60.0).roundToInt().coerceAtLeast(1)
        val leaveIn = (estimate.marginSeconds / 60.0).roundToInt()
        val text = when (estimate.verdict) {
            CatchEstimate.Verdict.AtStop -> getString(R.string.catch_at_stop)
            CatchEstimate.Verdict.Relaxed, CatchEstimate.Verdict.LeaveSoon ->
                getString(if (car()) R.string.catch_car_leave_in else R.string.catch_leave_in, walk, leaveIn)
            CatchEstimate.Verdict.LeaveNow -> getString(if (car()) R.string.catch_car_leave_now else R.string.catch_leave_now, walk)
            CatchEstimate.Verdict.Miss -> getString(if (car()) R.string.catch_car_miss else R.string.catch_miss, walk)
        }
        return if (positionKnown(detail)) text else getString(R.string.catch_by_timetable, text)
    }

    private fun sendCatchAlerts(current: TrackedTrip, detail: TripDetail, p: TripProgress, estimate: CatchEstimate) {
        // Only a fresh position may alarm the rider (EM-0032); updateLocation fetches one first.
        val fresh = lastFix?.let { System.currentTimeMillis() - it.time <= FRESH_FIX_MS } ?: false
        if (!fresh && !herePressed && !leavingPressed && !ridingBus) return
        // Timetable-only guesses alert only if the user asked for that (fewer false alarms).
        if (!positionKnown(detail) && !prefs.getCatchTimetableAlerts()) return
        val walk = (estimate.walkSeconds / 60.0).roundToInt().coerceAtLeast(1)
        when (estimate.verdict) {
            CatchEstimate.Verdict.LeaveNow -> notifyAlert(
                "catch_leave", getString(R.string.catch_alert_leave_title, current.ref.line),
                getString(if (car()) R.string.catch_alert_car_leave_body else R.string.catch_alert_leave_body, walk, p.boardingStopName)
            )
            CatchEstimate.Verdict.Miss -> notifyAlert(
                "catch_miss", getString(R.string.catch_alert_miss_title, current.ref.line),
                getString(if (car()) R.string.catch_alert_car_miss_body else R.string.catch_alert_miss_body, walk)
            )
            else -> Unit
        }
    }

    private suspend fun track() {
        var failures = 0
        while (scope.isActive) {
            var current = trip ?: return
            // EM-0093: a flat 3 h ended Dudince > Ružomberok (3 h 07 min) 11 min before arriving; a long trip
            // is followed until an hour past its last stop's time.
            val endsAt = (lastDetail?.stops?.lastOrNull()?.scheduledMs ?: 0L) + MAX_LATE_MS
            if (System.currentTimeMillis() > maxOf(startedAt + MAX_TRACKING_MS, endsAt)) {
                finish()
                return
            }
            val fetched = runCatching { repo.getTrip(current.ref) }.getOrNull()
            // Timetable trips (journey legs) learn where the user gets on and off from their trip link.
            val board = fetched?.boardingOrder
            if (board != null && current.boardingPlatformIds.isEmpty()) {
                current = current.copy(boardingPlatformIds = listOf(board), alightOrder = current.alightOrder ?: fetched.alightOrder)
                trip = current
                TripTracking.set(this@TripTrackerService, current)
            } else if (board != null && fetched.fromTimetable && fetched.stops.none { it.platformId in current.boardingPlatformIds }) {
                // sadzv had no stop list, so stops come from the timetable with its stop numbers: without this
                // the boarding stop isn't found and the user counts as already on the bus (GPS on all the time).
                current = current.copy(boardingPlatformIds = current.boardingPlatformIds + board)
                trip = current
                TripTracking.set(this@TripTrackerService, current)
            }
            if (fetched != null && fetched.stops.isNotEmpty()) lastDetail = fetched
            var detail = fetched?.let(::withLocalDelay)
            if (detail == null || detail.stops.isEmpty()) {
                failures++
                if (failures >= 20) {
                    finish()
                    return
                }
            } else {
                failures = 0
                var progress = TripProgress.from(detail, current, standingAt = standingAt.takeIf { it >= 0 }, riderIndex = riderIndexNow())
                if (progress.finished && current.nextLegs.isNotEmpty() && stillOnBoard(progress)) {
                    // EM-0040: by the clock the bus is at the change stop, but the rider's phone is still on it before
                    // there: it is late. Keep following it, as late as where the rider is.
                    localDelay = RoutePosition.delayAt(lastRiderIndex, detail.stops.map { it.scheduledMs }, System.currentTimeMillis()) to System.currentTimeMillis()
                    localFromGps = true
                    detail = withLocalDelay(detail)
                    progress = TripProgress.from(detail, current, standingAt = standingAt.takeIf { it >= 0 }, riderIndex = riderIndexNow())
                }
                if (progress.finished || riderOffAt >= (progress.alightIndex ?: detail.stops.lastIndex)) {
                    logRide(current, detail, progress)
                    val next = current.nextLegs.firstOrNull()
                    if (next != null) {
                        changeTo(next, current.nextLegs.drop(1), progress.lastStopName)
                        continue
                    }
                    notifyAlert("arrived", getString(R.string.track_arrived_title), getString(R.string.track_arrived_body, progress.lastStopName))
                    finish()
                    return
                }
                // EM-0099: the phone's own location can take 20 s and more (indoors, or no Google location service): it
                // is looked up beside the loop, so the bus's position shows at once; what it finds is drawn when it comes.
                if (locationJob?.isActive != true) {
                    val following = current
                    locationJob = scope.launch {
                        updateLocation(following, progress)
                        shareLocation()
                        if (trip?.ref == following.ref) lastDetail?.let { redraw(trip ?: following, it) }
                    }
                }
                updateRiderTracking(current, fetched ?: detail, progress)
                val estimate = updateCatch(detail, progress)
                show(current, detail, progress, catchLine(detail, progress))
                sendAlerts(current, progress)
                sendTransferRisk(current, detail, progress)
                estimate?.let { sendCatchAlerts(current, detail, progress, it) }
                // EM-0100: the bus is known to have left the stop and the phone is still there: missed. With another
                // followed bus waiting, on to that one.
                if (TripTracking.others.value.isNotEmpty() && progress.onBoard && positionKnown(detail) && leftBehind(progress)) {
                    finish()
                    return
                }
            }
            delay(REFRESH_MS)
        }
    }

    /**
     * 2.17.0: how full the bus is can be said only once it has left the rider's stop with them on it: not while it's
     * coming or standing there, and not when the phone stayed at the stop (missed it) or got off.
     */
    private fun crowdAllowed(detail: TripDetail, p: TripProgress): Boolean {
        if (!HubClient.isConfigured || detail.communityKey == null || !p.onBoard || p.finished || riderOffAt >= 0) return false
        return !leftBehind(p)
    }

    /** The phone, fresh, still stands at the rider's stop and never rode along: the bus went without them. */
    private fun leftBehind(p: TripProgress): Boolean {
        val fix = lastFix?.takeIf { System.currentTimeMillis() - it.time <= FRESH_FIX_MS } ?: return false
        return p.boardingIndex != null && !ridingBus && stopLatLon?.let { (lat, lon) ->
            LiveRepository.distanceMeters(fix.latitude, fix.longitude, lat, lon) < NEAR_STOP_M } == true
    }

    /** 2.17.0: a bus ridden to the end of the rider's part, for the year in numbers (only on the phone). */
    private fun logRide(current: TrackedTrip, detail: TripDetail, p: TripProgress) {
        val stops = detail.stops
        fun at(i: Int?) = i?.let { stops.getOrNull(it) }?.let { it.actualMs ?: it.scheduledMs }
        val start = at(p.boardingIndex ?: 0) ?: return
        val end = at(p.alightIndex ?: stops.lastIndex) ?: return
        runCatching { prefs.addRide(com.ksjd.testem.RideLog(start, current.ref.line, p.boardingStopName.ifBlank { stops.first().name },
            p.lastStopName, ((end - start) / 60_000L).toInt())) }
    }

    /** The rider's phone, fresh, is on this bus before their stop (where the bus really is, whatever the clock says). */
    private fun stillOnBoard(p: TripProgress): Boolean {
        val alight = p.alightIndex ?: return false
        val fresh = lastFix?.let { System.currentTimeMillis() - it.time <= FRESH_FIX_MS } ?: false
        return ridingBus && riderOffAt < 0 && fresh && lastRiderIndex >= 0f && lastRiderIndex < alight - 0.3f
    }

    /**
     * EM-0040: this bus is late enough that the next one of the journey leaves before the rider can be there
     * (arrival at the change stop plus their change time). Said once, while there's still time to plan another way.
     */
    private fun sendTransferRisk(current: TrackedTrip, detail: TripDetail, p: TripProgress) {
        val next = current.nextLegs.firstOrNull() ?: return
        val alight = p.alightIndex ?: return
        if (!positionKnown(detail) || next.ref.plannedSecondOfDay < 0) return
        val stop = detail.stops.getOrNull(alight) ?: return
        val arrival = stop.actualMs ?: (stop.scheduledMs + (detail.delaySeconds ?: 0) * 1000L)
        val date = runCatching { java.time.LocalDate.parse(next.ref.serviceDate) }.getOrNull() ?: return
        val departure = date.atStartOfDay(com.ksjd.testem.SK_ZONE).toInstant().toEpochMilli() + next.ref.plannedSecondOfDay * 1000L
        if (arrival + com.ksjd.testem.TimetableService.changeMinutes * 60_000L <= departure) return
        val changeStop = p.alightStopName ?: stop.name
        val end = current.nextLegs.last().let { it.toStop ?: it.ref.destination }
        val replan = PendingIntent.getActivity(
            this, REQUEST_REPLAN,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(TripTracking.EXTRA_REPLAN_FROM, changeStop).putExtra(TripTracking.EXTRA_REPLAN_TO, end)
                .putExtra(TripTracking.EXTRA_REPLAN_AT, arrival),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        notifyAlert(
            "transfer_risk", getString(R.string.transfer_risk_title, next.ref.line),
            getString(R.string.transfer_risk_body, (detail.delaySeconds ?: 0) / 60, changeStop,
                com.ksjd.testem.ui.Format.clock(arrival), next.ref.line, com.ksjd.testem.ui.Format.clock(departure)),
            tap = replan
        )
    }

    /** "Then bus 101 → Krupina" on a journey with changes. */
    private fun thenLine(trip: TrackedTrip): String? =
        trip.nextLegs.firstOrNull()?.let { getString(R.string.journey_then, it.ref.line, it.ref.destination) }

    private fun sendAlerts(trip: TrackedTrip, p: TripProgress) {
        val line = trip.ref.line
        val lead = prefs.getArrivalAlertMinutes()
        if (!p.onBoard && p.secondsToBoarding in 0..lead * 60) {
            notifyAlert(
                "arriving",
                getString(R.string.track_alert_arriving_title, line, ((p.secondsToBoarding + 30) / 60).coerceIn(1, lead)),
                getString(R.string.track_alert_arriving_body, p.boardingStopName)
            )
        }
        val alight = p.alightStopName
        // By the stop named as next too: the alert can't come after the card already says "to <your stop>".
        if (p.onBoard && alight != null && (p.nextIsAlight || p.shownNextIndex == p.alightIndex)) {
            val next = trip.nextLegs.firstOrNull()
            notifyAlert(
                "alight", getString(R.string.track_alert_alight_title),
                if (next != null) getString(R.string.journey_alight_change, alight, next.ref.line)
                else getString(R.string.track_alert_alight_body, alight)
            )
        }
    }

    // ------------------------------------------------------------ notifications

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(TripTracking.EXTRA_SHOW_FOLLOWED, true),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun stopAction(): NotificationCompat.Action {
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, TripTrackerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Action(0, getString(R.string.track_stop), stop)
    }

    private fun baseBuilder(trip: TrackedTrip): NotificationCompat.Builder =
        NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(contentIntent())
            .addAction(stopAction())
            .setRequestPromotedOngoing(true)
            .setLocalOnly(WatchSync.watchApp) // EM-0041: no second ongoing card next to the watch app's
            .setContentTitle(getString(R.string.track_title, trip.ref.line, trip.ref.destination))

    private fun placeholderNotification(trip: TrackedTrip) =
        baseBuilder(trip).setContentText(getString(R.string.track_loading)).build()

    private fun liveNotification(trip: TrackedTrip, detail: TripDetail, p: TripProgress, catchLine: String? = null): android.app.Notification {
        val builder = baseBuilder(trip)
        val community = detail.community
        val delayText = when {
            localDelay != null && detail.delaySeconds == localDelay?.first ->
                delayText(detail.delaySeconds) + " " + getString(if (localFromGps) R.string.community_your_phone else R.string.community_yours)
            community != null && (detail.positionSource == PositionSource.RiderGps || detail.positionSource == PositionSource.Riders) ->
                delayText(detail.delaySeconds) + " " + resources.getQuantityString(R.plurals.community_riders, community.reporters, community.reporters)
            detail.positionSource == PositionSource.History -> delayText(detail.delaySeconds) + " " + getString(R.string.history_estimate)
            detail.positionSource == PositionSource.Timetable && detail.history != null -> "\n" + Format.history(this, detail.history, detail.history.now(detail))
            detail.positionSource == PositionSource.Timetable && trip.ref.isScheduleOnly -> "\n" + getString(R.string.track_schedule_only)
            else -> delayText(detail.delaySeconds)
        } + (reportNote?.let { "\n" + it } ?: "")
        TripTracking.buttons = emptyList()
        // Only when the bus can actually be seen: from 30 min before it is due at the user's stop.
        if (waitingAtStop && !p.onBoard && !herePressed) {
            waitingActions(p).also { TripTracking.buttons = it }.forEach { builder.addAction(it) }
        } else if (buttonsOn(detail) && (p.onBoard || p.secondsToBoarding <= REPORT_WINDOW_S)) {
            communityActions(detail, p).also { TripTracking.buttons = it }.forEach { builder.addAction(it) }
        }
        if (!p.onBoard) {
            // Waiting at the stop: countdown to departure, and whether the user will make it.
            shown = Shown(
                getString(R.string.track_title, trip.ref.line, detail.destination),
                getString(R.string.track_waiting_text, Format.clock(p.boardingExpectedMs), p.boardingStopName) +
                    (catchLine?.let { "\n" + it } ?: "") + delayText + (thenLine(trip)?.let { "\n" + it } ?: ""),
                shortMinutes(p.secondsToBoarding)
            )
            builder
                .setContentTitle(shown.title)
                .setContentText(shown.text)
                .setShortCriticalText(shown.countdown)
                .setWhen(p.boardingExpectedMs)
                // EM-0077: the time is in the text already; Android's own time beside the title showed it twice.
                .setShowWhen(false)
                .setUsesChronometer(false)
        } else {
            // On board: time to the next stop, current stop underneath.
            val next = p.nextStopName ?: detail.destination
            shown = Shown(
                getString(R.string.track_next_title, next, Format.clock(p.nextExpectedMs)),
                listOfNotNull(
                    p.currentStopName?.let { getString(R.string.track_now_at, it) },
                    p.alightStopName?.let { getString(R.string.track_get_off_at, it) },
                    thenLine(trip)
                ).joinToString("\n") + delayText,
                shortMinutes(p.secondsToNext)
            )
            builder
                .setContentTitle(shown.title)
                .setContentText(shown.text)
                .setShortCriticalText(shown.countdown)
                .setWhen(p.nextExpectedMs)
                .setShowWhen(false) // EM-0077: "Next: Rákoš at 7:02" already says when
        }
        builder.setStyle(progressStyle(detail, p))
        return builder.build()
    }

    /**
     * Where the bus really is, in one tap. Waiting: "Bus is here" (at my stop), "Bus is leaving",
     * then "At <next stop>". Buttons follow the predicted position, so they move on by
     * themselves: a rider who forgets a stop just marks the next one.
     * On board: "At <next>" (ahead of the timetable) or "Still at <current>" (behind).
     */
    private fun communityActions(detail: TripDetail, p: TripProgress): List<NotificationCompat.Action> {
        fun action(requestCode: Int, label: String, stopIndex: Int, leaving: Boolean = false) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(
                this, requestCode,
                Intent(this, TripTrackerService::class.java).setAction(ACTION_REPORT).putExtra("stop", stopIndex)
                    .putExtra("leaving", leaving),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        // Whole name ("Detva, aut.st."), shortened only when it would not fit on the button.
        fun short(name: String) = if (name.length > 18) name.take(17).trimEnd(',', ' ') + "…" else name
        if (!p.onBoard) {
            val boarding = p.boardingIndex ?: return emptyList()
            return when {
                // Just left: the next thing to confirm is the following stop.
                leavingPressed -> listOfNotNull(detail.stops.getOrNull(boarding + 1)?.let {
                    action(REQUEST_REPORT_NEXT, getString(R.string.community_at, short(it.name)), boarding + 1)
                })
                herePressed -> listOf(action(REQUEST_REPORT_LEAVING, getString(R.string.community_bus_leaving), boarding, leaving = true))
                else -> listOf(action(REQUEST_REPORT_HERE, getString(R.string.community_bus_here), boarding))
            }
        }
        return buildList {
            detail.stops.getOrNull(p.nextIndex)?.let { add(action(REQUEST_REPORT_NEXT, getString(R.string.community_at, short(it.name)), p.nextIndex)) }
            detail.stops.getOrNull(p.nextIndex - 1)?.let { add(action(REQUEST_REPORT_BEHIND, getString(R.string.community_still_at, short(it.name)), p.nextIndex - 1)) }
        }
    }

    /** The rider waits at the stop and the bus is late: "Bus is here" (reported for everyone) or "It already left". */
    private fun waitingActions(p: TripProgress): List<NotificationCompat.Action> {
        val boarding = p.boardingIndex ?: return emptyList()
        val here = PendingIntent.getService(
            this, REQUEST_REPORT_HERE,
            Intent(this, TripTrackerService::class.java).setAction(ACTION_REPORT).putExtra("stop", boarding).putExtra("leaving", false),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val gone = PendingIntent.getService(
            this, REQUEST_GONE, Intent(this, TripTrackerService::class.java).setAction(ACTION_GONE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return listOf(
            NotificationCompat.Action(0, getString(R.string.community_bus_here), here),
            NotificationCompat.Action(0, getString(R.string.community_bus_gone), gone)
        )
    }

    /** One segment per stop-to-stop hop; points mark where you get on and off. */
    private fun progressStyle(detail: TripDetail, p: TripProgress): NotificationCompat.ProgressStyle {
        val hops = (detail.stops.size - 1).coerceAtLeast(1)
        val primary = ContextCompat.getColor(this, R.color.track_primary)
        val style = NotificationCompat.ProgressStyle()
            .setStyledByProgress(true)
            .setProgressTrackerIcon(IconCompat.createWithResource(this, R.drawable.ic_stat_bus))
            // Android draws at most 10 segments and merges the rest into one (logging a warning each time).
            .setProgressSegments(
                if (hops <= MAX_SEGMENTS) List(hops) { NotificationCompat.ProgressStyle.Segment(STEPS_PER_HOP).setColor(primary) }
                else listOf(NotificationCompat.ProgressStyle.Segment(hops * STEPS_PER_HOP).setColor(primary))
            )
            .setProgress((p.position * STEPS_PER_HOP).roundToInt().coerceIn(0, hops * STEPS_PER_HOP))
        val points = buildList {
            p.boardingIndex?.let { add(it) }
            p.alightIndex?.let { add(it) }
        }.distinct().filter { it in 0 until detail.stops.size } // 0 too: boarding at the first stop
        if (points.isNotEmpty()) {
            style.setProgressPoints(points.map { NotificationCompat.ProgressStyle.Point(it * STEPS_PER_HOP).setColor(primary) })
        }
        return style
    }

    private fun shortMinutes(seconds: Int): String {
        val minutes = (seconds / 60.0).roundToInt()
        return if (minutes <= 0) getString(R.string.countdown_now) else getString(R.string.countdown_minutes, minutes)
    }

    private fun delayText(delaySeconds: Int?): String {
        val minutes = ((delaySeconds ?: return "") / 60.0).roundToInt()
        return when {
            minutes >= 1 -> "\n" + getString(R.string.delay_late, minutes)
            minutes <= -1 -> "\n" + getString(R.string.delay_early, -minutes)
            else -> "\n" + getString(R.string.delay_on_time)
        }
    }

    private fun notifyAlert(key: String, title: String, body: String, tap: PendingIntent? = null) {
        if (!alertsSent.add(key)) return
        TripTracking.recordAlert(this, key, title, body)
        val channel = com.ksjd.testem.reminders.AlertSound.channel(this, CHANNEL_ALERTS, getString(R.string.track_channel_alerts))
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_bus)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(tap ?: contentIntent())
            .setAutoCancel(true)
            // Account → Watch: alerts on the phone only. "Get off next" and "Leave now" the watch app announces itself,
            // each with its own vibration.
            .setLocalOnly(!prefs.getWatchAlerts() || (key in WATCH_OWN_ALERTS && WatchSync.watchApp && prefs.getWatchEnabled()))
            .build()
        NotificationManagerCompat.from(this).notifySafely(ALERT_ID_BASE + key.hashCode() % 1000, notification)
    }

    private fun NotificationManagerCompat.notifySafely(id: Int, notification: android.app.Notification) {
        runCatching { notify(id, notification) }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_LIVE, getString(R.string.track_channel_live), NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    /**
     * Shared by a code: the people it's shared with see where the phone is. Live GPS covers it on the bus; before
     * that (GPS off while walking) a location now and then, once a minute.
     */
    private suspend fun shareLocation() {
        Shares.load(this)
        if (Shares.outgoing.value.isEmpty()) return
        val fix = lastFix?.takeIf { System.currentTimeMillis() - it.time < 60_000L }
            ?: Locations.current(this, maxAgeMs = 60_000L)?.also { lastFix = it } ?: return
        Shares.publish(this, fix)
    }

    private fun finish() {
        // Sharing by code ends with following; so does a shared arrival link ("Arrived" for whoever has it open).
        val app = applicationContext
        val link = TripTracking.eta.value
        TripTracking.eta.value = null
        etaSent = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { Shares.stopOutgoing(app); link?.let { HubClient.endEta(it) } }
        // EM-0100: another followed bus takes over, live at once or planned until its lead time. (A planned one
        // already there is the one this bus was stopped for.)
        val next = if (FollowLater.planned.value == null) TripTracking.takeNext(this) else null
        val at = next?.let { FollowLater.startAt(this, it) }
        if (next != null && at == null) {
            resetTripState()
            trip = next
            TripTracking.set(this, next)
            startInForeground(placeholderNotification(next))
            val old = loop
            loop = scope.launch { track() }
            old?.cancel()  // may be the caller itself: it returns right after
            return
        }
        if (next != null && at != null) FollowLater.plan(this, next, at)
        stopRiderTracking()
        loop?.cancel()
        trip = null
        TripTracking.set(this@TripTrackerService, null)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "com.ksjd.testem.TRACK_STOP"
        const val ACTION_REPORT = "com.ksjd.testem.TRACK_REPORT"
        const val ACTION_GONE = "com.ksjd.testem.TRACK_GONE"
        const val ACTION_RIDE = "com.ksjd.testem.TRACK_RIDE"
        const val ACTION_PROMOTE = "com.ksjd.testem.TRACK_PROMOTE"
        const val ACTION_CATCH_MODE = "com.ksjd.testem.TRACK_CATCH_MODE"
        const val ACTION_CROWD = "com.ksjd.testem.TRACK_CROWD"
        private const val EXTRA_RIDE = "ride"
        private const val EXTRA_DEPARTURE = "departure"

        fun rideIntent(context: Context, rideId: String, departureMs: Long): Intent =
            Intent(context, TripTrackerService::class.java).setAction(ACTION_RIDE)
                .putExtra(EXTRA_RIDE, rideId).putExtra(EXTRA_DEPARTURE, departureMs)
        private const val REQUEST_REPORT_HERE = 10
        private const val REQUEST_REPORT_NEXT = 11
        private const val REQUEST_REPORT_BEHIND = 12
        private const val REQUEST_REPORT_LEAVING = 13
        private const val REQUEST_GONE = 14
        private const val REQUEST_REPLAN = 15
        /** Within this of the boarding stop the rider is "at the stop": live GPS on. */
        private const val NEAR_STOP_M = 150
        /** A bus standing this close to a stop is stopped there (timetable stop positions can be tens of metres off). */
        private const val STOP_RADIUS_M = 60
        /** Standing this close to the rider's own stop counts as waiting there (EM-0073). */
        private const val BOARDING_RADIUS_M = 120
        /** Slower than this (m/s, about 9 km/h) the bus counts as stopped. */
        private const val MOVING_MPS = 2.5f
        /** Alerts the watch app announces itself (the phone's copy stays off the watch). */
        private val WATCH_OWN_ALERTS = setOf("alight", "catch_leave")
        /** Standing this long at a stop on the way, the rider got off there (no bus waits that long at a stop on the way). */
        private const val GOT_OFF_STANDING_MS = 4 * 60_000L
        /** Live GPS at the stop starts this long before the bus can come. */
        private const val LIVE_GPS_BEFORE_MS = 4 * 60_000L
        private const val MAX_FIX_ACCURACY_M = 300f
        /** A position older than this is re-checked before it can say "leave now" or "you won't make it". */
        private const val FRESH_FIX_MS = 45_000L
        /** EM-0104: how long the delay this phone measured on the bus stands against newer pooled reports. */
        private const val OWN_DELAY_MS = 5 * 60_000L
        /** EM-0104: between stops, only a bus this much later than at its last stop counts as later. */
        private const val BETWEEN_STOPS_LATER_S = 60
        /** EM-0104: just past a stop on the route: far enough that the next stop is the one after it. */
        private const val LEFT_STOP_INDEX = 0.11f
        private const val REPORT_WINDOW_S = 30 * 60
        private const val NOTIFICATION_ID = 4200
        private const val ALERT_ID_BASE = 5000
        private const val CHANNEL_LIVE = "trip_live"
        private const val CHANNEL_ALERTS = "trip_alerts"
        private const val REFRESH_MS = 15_000L
        private const val MAX_TRACKING_MS = 3L * 60 * 60 * 1000
        private const val MAX_LATE_MS = 60 * 60 * 1000L
        private const val STEPS_PER_HOP = 100
        private const val MAX_SEGMENTS = 10
        private const val GPS_TAG = "RiderGps"

        fun intent(context: Context, trip: TrackedTrip): Intent =
            Intent(context, TripTrackerService::class.java)
                .putExtra("line", trip.ref.line)
                .putExtra("lineId", trip.ref.lineId)
                .putExtra("route", trip.ref.routeNumber)
                .putExtra("trip", trip.ref.tripNumber)
                .putExtra("destination", trip.ref.destination)
                .putExtra("boarding", trip.boardingPlatformIds.toIntArray())
                .putExtra("alight", trip.alightOrder ?: -1)
                .putExtra("scheduleUrl", trip.ref.scheduleUrl)
                .putExtra("serviceDate", trip.ref.serviceDate)
                .putExtra("fromStopId", trip.ref.fromStopId)
                .putExtra("planned", trip.ref.plannedSecondOfDay)
                .putExtra("next", com.google.gson.Gson().toJson(trip.nextLegs))

        private fun readTrip(intent: Intent): TrackedTrip? {
            val line = intent.getStringExtra("line") ?: return null
            return TrackedTrip(
                ref = TripRef(
                    line = line,
                    lineId = intent.getLongExtra("lineId", 0L),
                    routeNumber = intent.getStringExtra("route").orEmpty(),
                    tripNumber = intent.getIntExtra("trip", 0),
                    destination = intent.getStringExtra("destination").orEmpty(),
                    scheduleUrl = intent.getStringExtra("scheduleUrl").orEmpty(),
                    serviceDate = intent.getStringExtra("serviceDate").orEmpty(),
                    fromStopId = intent.getIntExtra("fromStopId", 0),
                    plannedSecondOfDay = intent.getIntExtra("planned", -1)
                ),
                boardingPlatformIds = intent.getIntArrayExtra("boarding")?.toList().orEmpty(),
                alightOrder = intent.getIntExtra("alight", -1).takeIf { it >= 0 },
                // ponytail: after a restart the service redelivers the first leg; finished legs pass straight on.
                nextLegs = intent.getStringExtra("next")?.let { json ->
                    runCatching {
                        com.google.gson.Gson().fromJson<List<TrackedTrip>>(json, object : com.google.gson.reflect.TypeToken<List<TrackedTrip>>() {}.type)
                    }.getOrNull()?.map { it.copy(nextLegs = emptyList()) }
                }.orEmpty()
            )
        }
    }
}

/** Where the bus is relative to the user's stops. Pure logic, no Android types. */
data class TripProgress(
    val onBoard: Boolean,
    val finished: Boolean,
    /** Fractional stop index of the bus, 0 = first stop. */
    val position: Float,
    val boardingIndex: Int?,
    val boardingStopName: String,
    val boardingExpectedMs: Long,
    val secondsToBoarding: Int,
    val currentStopName: String?,
    val nextStopName: String?,
    val nextExpectedMs: Long,
    val secondsToNext: Int,
    val alightIndex: Int?,
    val alightStopName: String?,
    val nextIsAlight: Boolean,
    val lastStopName: String,
    /** Index of the next stop the bus has not reached yet (stops.size when past the end). */
    val nextIndex: Int = 0,
    /** Index of the stop named as next ([nextStopName]): one further than [nextIndex] while the bus is at a stop. */
    val shownNextIndex: Int = nextIndex
) {
    companion object {
        /** How long before it is due the bus counts as at a stop (arriving, or standing at it). */
        private const val AT_STOP_LEAD_MS = 60_000L

        /**
         * When the bus can be at the user's stop at the earliest: its expected time, or earlier when it's running early,
         * or (nothing live known) usually leaves early. Lateness isn't counted on, so the user isn't told to leave too late.
         * EM-0096: that goes for a usual delay too: with +13 min from earlier days the bus left on time with the rider on it,
         * and live GPS waited for the predicted time.
         */
        fun earliestMs(expectedMs: Long, scheduledMs: Long, delaySeconds: Int, usualSeconds: Int, positionKnown: Boolean): Long {
            val latest = if (positionKnown) expectedMs else minOf(expectedMs, scheduledMs)
            val early = maxOf(0, -delaySeconds, -usualSeconds)
            return if (early > 0) minOf(latest, scheduledMs - early * 1000L) else latest
        }

        /**
         * [standingAt]: the stop the rider's phone stands at right now (automatic mode), if any. It and the clock (from a
         * minute before the bus is due) make a stop "now" while the bus is at it; before EM-0059 the stop before stayed
         * "now" until the bus had left, so a rider at Štadlerovo read "Now: Rákoš".
         * [riderIndex]: where on the route the rider's phone is while it rides the bus (fractional stop index). Then the
         * phone, not the clock, says which stop is next: the clock's guess ran a stop ahead or behind the real bus, and
         * "get off at the next stop" came when the bus was already there.
         */
        fun from(detail: TripDetail, trip: TrackedTrip, now: Long = System.currentTimeMillis(), standingAt: Int? = null,
                 riderIndex: Float? = null): TripProgress {
            val stops = detail.stops
            val expected = stops.mapIndexed { i, s -> s.actualMs ?: (s.scheduledMs + (detail.delayAt(i) ?: 0) * 1000L) }
            // Actual times are not always reported, so also infer progress from the clock.
            val nextIndex = stops.indices.firstOrNull { i -> stops[i].actualMs == null && expected[i] >= now - 30_000L }
                ?: stops.size
            val boardingIndex = stops.indexOfFirst { it.platformId in trip.boardingPlatformIds }.takeIf { it >= 0 }
            val alightIndex = trip.alightOrder?.let { order -> stops.indexOfFirst { it.order == order } }?.takeIf { it >= 0 }
            val endIndex = alightIndex ?: stops.lastIndex
            val onBoard = boardingIndex == null || nextIndex > boardingIndex
            val finished = nextIndex > endIndex

            // Interpolate between the last passed stop and the next one for a smooth bar.
            val position = when {
                nextIndex <= 0 -> 0f
                nextIndex >= stops.size -> stops.lastIndex.toFloat()
                else -> {
                    val from = expected[nextIndex - 1]
                    val to = expected[nextIndex]
                    val fraction = if (to > from) ((now - from).toFloat() / (to - from)).coerceIn(0f, 1f) else 0f
                    (nextIndex - 1) + fraction
                }
            }
            val boardingMs = boardingIndex?.let { expected[it] } ?: 0L
            // The stop the bus is at (only for the "now" and "next" names; alerts keep going by nextIndex).
            // A stop counts as left once the phone is clearly past it (a tenth of the way to the next one).
            val riderNext = riderIndex?.let { r -> (if (r - floor(r) > 0.1f) floor(r).toInt() + 1 else floor(r).toInt()).coerceIn(0, stops.size) }
            val atIndex = standingAt?.takeIf { it in stops.indices && it >= (riderNext ?: nextIndex) - 1 }
                ?: nextIndex.takeIf { riderNext == null && it < stops.size && now >= expected[it] - AT_STOP_LEAD_MS }
            val shownNext = if (atIndex != null) atIndex + 1 else riderNext ?: nextIndex
            val nextMs = expected.getOrNull(shownNext) ?: expected.lastOrNull() ?: now
            return TripProgress(
                onBoard = onBoard,
                finished = finished,
                position = position,
                boardingIndex = boardingIndex,
                boardingStopName = boardingIndex?.let { stops[it].name }.orEmpty(),
                boardingExpectedMs = boardingMs,
                secondsToBoarding = ((boardingMs - now) / 1000L).toInt(),
                currentStopName = stops.getOrNull(atIndex ?: ((riderNext ?: nextIndex) - 1))?.name,
                nextStopName = stops.getOrNull(shownNext)?.name,
                nextExpectedMs = nextMs,
                secondsToNext = ((nextMs - now) / 1000L).toInt().coerceAtLeast(0),
                alightIndex = alightIndex,
                alightStopName = alightIndex?.let { stops[it].name },
                nextIsAlight = alightIndex != null && nextIndex == alightIndex,
                lastStopName = stops.getOrNull(endIndex)?.name.orEmpty(),
                nextIndex = nextIndex,
                shownNextIndex = shownNext
            )
        }
    }
}
