package com.ksjd.testem.p2p

import android.content.Context
import android.content.pm.PackageManager

/**
 * 2.19.0: what the radios for phones without data did, for bug reports: the last [MAX] events (also in logcat, tag
 * "P2p"), kept apart so they survive when logcat has moved on. Other phones are named by the last two bytes of their
 * (random, changing) Bluetooth address or Nearby's endpoint id only; no packet content beyond sizes and counts.
 */
object P2pLog {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    fun add(text: String) {
        android.util.Log.i("P2p", text)
        val line = java.time.LocalTime.now(com.ksjd.testem.SK_ZONE).format(clock) + " " + text
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    /** Another phone, by the end of its address only (Bluetooth LE addresses are random and change anyway). */
    fun who(address: String?): String = "…" + address.orEmpty().takeLast(5)

    /** For a bug report: the state now (setting, permissions, radios, what is offered and heard) and the recent events. */
    fun report(context: Context): String = buildString {
        val now = System.currentTimeMillis()
        fun ago(at: Long) = if (at <= 0) "never" else "${(now - at) / 1000} s ago"
        append("setting on: ${com.ksjd.testem.CredentialsManager(context).getP2pNearby()}, online: ${P2p.online(context)}\n")
        append("permissions: ").append(P2pRadio.permissions().joinToString { p ->
            p.substringAfterLast('.') + "=" + (if (context.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED) "yes" else "NO")
        }).append('\n')
        val adapter = runCatching { context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter }.getOrNull()
        append("bluetooth: ${if (adapter == null) "none" else if (adapter.isEnabled) "on" else "OFF"}, ")
        append("Google Play services: ${runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)}\n")
        append("offering: buses ${P2pRadio.buses?.size ?: 0} B, board ${P2pRadio.board?.size ?: 0} B; ${P2pRadio.state()}\n")
        append("heard: ${P2pStore.heard.value.size} bus keys, board ${P2pStore.board.value?.let { "${it.stop}, ${it.departures.size} departures, ${ago(it.atMs)}" } ?: "none"}\n")
        append("last heard ${ago(P2pStore.lastHeardMs.value)}, last offered ${ago(P2pStore.lastOfferedMs.value)}\n")
        append("events:\n")
        synchronized(lines) { lines.forEach { append(it).append('\n') } }
    }
}
