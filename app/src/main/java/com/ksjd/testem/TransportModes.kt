package com.ksjd.testem

import android.content.Context

/**
 * 2.18.0: the kinds of transport emHUB knows: buses between towns ("bus"), city transport ("city": city buses, trams,
 * trolleybuses) and trains ("rail"). The planner's three switches choose which it may use; stop search and boards
 * always ask for all of them.
 */
object TransportModes {
    val ALL = listOf("bus", "city", "rail")

    @Volatile
    var planner: Set<String> = ALL.toSet()
        private set

    fun load(context: Context) {
        planner = CredentialsManager(context).getPlannerModes()
    }

    /** Switches one kind on or off; the last one stays on (a search with nothing would find nothing). */
    fun toggle(context: Context, mode: String): Set<String> {
        val next = if (mode in planner) planner - mode else planner + mode
        if (next.isNotEmpty()) {
            planner = next
            CredentialsManager(context).savePlannerModes(next)
        }
        return planner
    }

    /** emHUB's "modes" (and the offline region's "rail") for a timetable request. */
    fun params(path: String): Map<String, String> = when (path) {
        "plan" -> mapOf("modes" to ALL.filter { it in planner }.joinToString(","))
        "stops", "departures" -> mapOf("modes" to ALL.joinToString(","))
        "offline/region" -> mapOf("rail" to "1")
        else -> emptyMap()
    }
}
