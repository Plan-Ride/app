package com.ksjd.testem.live

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.google.gson.Gson
import com.ksjd.testem.R

/**
 * A bus as a link anyone can open: in Plan&Ride where it's installed on Android (App Links, 2.18.5), else in the web
 * version at app.par.karafa.net. Links sent before 2.18.5 (par.karafa.net/f, emHUB forwards them) open the app too.
 * The link holds only the bus (line, trip, day) and the stops where it's ridden from and to: no account, no id of
 * the person sharing, no location. Opening it shows the bus; following it there is the opener's own choice.
 */
object ShareTrip {
    private const val BASE = "https://app.par.karafa.net/f?t="
    private val HOSTS = setOf("app.par.karafa.net", "par.karafa.net")

    /**
     * EM-0133: a journey with changes goes whole: each later bus in [next] with where it's ridden from and to and when
     * ([from], [to], [leaves], [arrives]; epoch ms). Links from before have only the first bus; older apps ignore the rest.
     */
    private data class Payload(
        val ref: TripRef, val board: List<Int>?, val alight: Int?,
        val from: String? = null, val to: String? = null, val leaves: Long? = null, val arrives: Long? = null,
        val next: List<Payload>? = null
    )

    private fun of(trip: TrackedTrip, withNext: Boolean): Payload = Payload(
        trip.ref, trip.boardingPlatformIds, trip.alightOrder, trip.fromStop, trip.toStop,
        trip.departsAtMs.takeIf { it > 0 }, trip.arrivesAtMs.takeIf { it > 0 },
        if (withNext) trip.nextLegs.map { of(it, false) }.ifEmpty { null } else null
    )

    private fun Payload.trip(): TrackedTrip = TrackedTrip(
        ref, board.orEmpty(), alight, departsAtMs = leaves ?: 0L, fromStop = from, toStop = to, arrivesAtMs = arrives ?: 0L,
        nextLegs = next.orEmpty().filter { it.ref?.line?.isNotBlank() == true }.map { it.trip() }
    )

    /** The bus (and the rest of its journey) as the link's (and a share code's) payload: base64url JSON the web version reads too. */
    fun payload(trip: TrackedTrip): String {
        val json = Gson().toJson(of(trip, true))
        return Base64.encodeToString(json.toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    fun link(trip: TrackedTrip): String = BASE + payload(trip)

    /** The bus in a shared link opened in the app (https://app.par.karafa.net/f?t=…), null if it isn't one or can't be read. */
    fun fromLink(uri: android.net.Uri): TrackedTrip? =
        if (uri.scheme == "https" && uri.host in HOSTS && uri.path == "/f") uri.getQueryParameter("t")?.let(::decode) else null

    /** A shared bus back from its payload (a share code that was accepted), null if it can't be read. */
    fun decode(payload: String): TrackedTrip? = runCatching {
        val json = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
        val p = Gson().fromJson(json, Payload::class.java)
        p.trip().takeIf { it.ref.line.isNotBlank() } // Gson leaves missing fields null
    }.getOrNull()

    fun share(context: Context, trip: TrackedTrip, destination: String) {
        val text = context.getString(R.string.share_trip_text, trip.ref.line, destination.ifBlank { trip.ref.destination }, link(trip))
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.share_trip)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
