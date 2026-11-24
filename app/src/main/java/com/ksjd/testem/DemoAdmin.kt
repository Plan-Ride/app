package com.ksjd.testem

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

/**
 * The open day phone's device owner (declared in the demo build's manifest only; set once over adb):
 *   adb shell dpm set-device-owner com.ksjd.testem/.DemoAdmin
 * It lets the app hold the screen (kiosk: no home, recents or status bar) and update itself without a tap.
 */
class DemoAdmin : DeviceAdminReceiver() {

    /**
     * The way out without emHUB or the PIN, from a computer (only adb's shell may send it, android.permission.DUMP):
     *   adb shell am broadcast -a com.ksjd.testem.DEMO_UNLOCK -p com.ksjd.testem                 kiosk and lock off
     *   adb shell am broadcast -a com.ksjd.testem.DEMO_UNLOCK -p com.ksjd.testem --ez remove true  and no longer the owner
     */
    class Release : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Demo.unlockHere(context)
            if (intent.getBooleanExtra("remove", false)) Demo.giveUpOwner(context)
        }
    }

    /** An update installed or the phone restarted: back into the demo by itself (a device owner may start it from here). */
    class Back : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!Demo.isOwner(context)) return
            runCatching {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    /** The installer's answer to Demo.update: without the device owner, Android asks on screen first. */
    class Installed : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    Demo.updating = "waiting for a tap on the phone"
                }
                PackageInstaller.STATUS_SUCCESS -> Demo.updating = ""
                else -> Demo.updating = "failed: " + (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown").take(60)
            }
            Demo.sendState()
        }
    }
}
