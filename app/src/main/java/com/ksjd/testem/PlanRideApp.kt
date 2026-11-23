package com.ksjd.testem

import android.app.Application

/** Runs first in every process start, also when only the tracking service or an alarm starts the app. */
class PlanRideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        com.ksjd.testem.hub.AppLogs.installCrashRecorder(this)
        // Never stops the app from starting (the Keystore once refused its key on an Android 10 phone).
        if (Demo.on) runCatching { Demo.seed(this) }
        com.ksjd.testem.offline.OfflineTimetables.init(this)
        TransportModes.load(this)
    }
}
