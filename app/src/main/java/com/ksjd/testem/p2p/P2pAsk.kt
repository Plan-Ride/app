package com.ksjd.testem.p2p

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 2.19.0: a phone without data asks phones around for a stop's departures. It broadcasts a question (the stop's code with
 * two cover stops nearby, in random order, so whoever hears it can't tell which one is wanted); a phone with data that
 * hears it fetches the signed boards from emHUB and broadcasts them to everyone around, and phones without data pass
 * them on. Nobody is asked directly and nothing says who asked. Only while the app is open on the asked stop.
 */
object P2pAsk {
    data class Asking(val stationId: String, val name: String, val sinceMs: Long, val codes: List<Int>)

    private val _asking = MutableStateFlow<Asking?>(null)
    val asking: StateFlow<Asking?> = _asking

    fun ask(context: Context, stationId: String, name: String) {
        val cover = com.ksjd.testem.offline.OfflineTimetables.cover(stationId, 2)
        val codes = (cover + stationId).shuffled().map(P2pPack::code)
        P2pStore.asked = setOf(stationId)
        _asking.value = Asking(stationId, name, System.currentTimeMillis(), codes)
        P2pLog.add("asking for $name with ${cover.size} cover stops: codes ${codes.joinToString { "%06x".format(it) }}")
        P2pRadio.ask(context, codes)
    }

    fun stop(context: Context) {
        if (_asking.value != null) P2pLog.add("asking stopped")
        _asking.value = null
        P2pStore.asked = emptySet()
        P2pRadio.stopAsking(context)
    }
}
