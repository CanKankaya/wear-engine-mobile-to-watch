package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Helpers around Android's battery optimization (Doze + App Standby) whitelist.
 *
 * Why we need this: a foreground service prevents our process from being killed,
 * but it does NOT prevent Doze from suspending CPU + network when the screen is
 * off and the device is unplugged. The result on Android 10 is that our 5s
 * Wear Engine sender stops dispatching to the watch until the user plugs in a
 * charger (which exits Doze).
 *
 * The documented fix for "peripheral device companion app" use cases is to ask
 * the user to whitelist us via [Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS].
 */
object BatteryOptimization {

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Pops the system dialog "Allow [app] to ignore battery optimizations?".
     * If the user accepts, our app is exempted from Doze + App Standby. This
     * is the only programmatic way to keep network/CPU running with screen
     * off + unplugged on stock Android 10+.
     *
     * NOTE: Do NOT set FLAG_ACTIVITY_NEW_TASK here. This intent is launched
     * via [androidx.activity.result.ActivityResultLauncher] which already
     * starts it in the correct task; the NEW_TASK flag causes some OEMs
     * (including Huawei EMUI) to silently no-op the launch, with no dialog
     * shown to the user.
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreIntent(context: Context): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    /**
     * True iff some activity on this device claims to handle the
     * battery-optimization request intent. Some heavily customized Android
     * builds (and some emulators) don't, in which case we must fall back to
     * [settingsListIntent].
     */
    fun canRequestIgnoreOptimizations(context: Context): Boolean {
        return requestIgnoreIntent(context).resolveActivity(context.packageManager) != null
    }

    /**
     * Opens the system battery-optimization settings list as a fallback (e.g.
     * if the device blocks the direct request intent).
     */
    fun settingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    /**
     * Ordered list of known OEM "app launch" / "autostart" / "protected apps"
     * activities. Many of these are NOT exported on newer OS versions, so
     * `resolveActivity` may return null even when the activity actually
     * exists — that's why we also include action-only intents and a final
     * `ACTION_APPLICATION_DETAILS_SETTINGS` fallback that is always
     * resolvable, so the user can navigate from App info → Battery → App
     * launch manually.
     *
     * Each entry is (label, intent-builder).
     */
    private fun oemPowerManagerCandidates(context: Context): List<Pair<String, Intent>> {
        fun component(pkg: String, cls: String): Intent =
            Intent().apply {
                component = ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        return listOf(
            // --- Huawei / Honor (EMUI / HarmonyOS / MagicUI) ---
            "Huawei App launch" to component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            "Huawei Startup manager" to component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            "Huawei Protected apps" to component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            "Huawei Power manager" to component(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
            ),
            // --- Oppo / ColorOS ---
            "Oppo Startup manager" to component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity"
            ),
            "Oppo Startup manager" to component(
                "com.coloros.safecenter",
                "com.coloros.safecenter.startupapp.StartupAppListActivity"
            ),
            "Oppo Power manager" to component(
                "com.oppo.safe",
                "com.oppo.safe.permission.startup.StartupAppListActivity"
            ),
            // --- Vivo / Funtouch ---
            "Vivo Background manager" to component(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            // Samsung / Pixel / most others don't need a vendor-specific
            // screen — the standard battery-optimization whitelist is enough,
            // and on Samsung the system manager activities are non-exported
            // anyway. So we don't try to open anything custom for them; the
            // app-info fallback below covers it if the user still wants to
            // poke around.
            // --- Honor (Magic UI, sometimes separate package) ---
            "Honor App launch" to component(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            // --- Guaranteed fallback: app's own App info page ---
            // From here the user is one tap away from Battery → App launch on
            // Huawei, and "Unrestricted" on stock Android.
            "App info (fallback)" to Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /**
     * Tries each known OEM power manager intent in order, then falls back to
     * the app's own settings page. Returns the label of the activity that was
     * actually started, or null if every attempt failed.
     */
    fun openOemPowerManager(context: Context): String? {
        val pm = context.packageManager
        for ((label, intent) in oemPowerManagerCandidates(context)) {
            // resolveActivity is unreliable on Huawei (many activities are not
            // exported but still launchable from third-party apps), so we just
            // try-start every candidate. If it throws (ActivityNotFound,
            // SecurityException for non-exported, etc.) we move on.
            val ok = runCatching { context.startActivity(intent) }.isSuccess
            if (ok) return label
            // For the action-only fallback, resolveActivity IS reliable.
            if (intent.component == null &&
                intent.resolveActivity(pm) != null &&
                runCatching { context.startActivity(intent) }.isSuccess
            ) return label
        }
        return null
    }

    /**
     * Back-compat: returns the first OEM intent that the package manager
     * claims to resolve. Prefer [openOemPowerManager] which actually tries
     * to launch (and handles non-exported activities).
     */
    fun oemPowerManagerIntent(context: Context): Intent? {
        val pm = context.packageManager
        for ((_, intent) in oemPowerManagerCandidates(context)) {
            if (intent.resolveActivity(pm) != null) return intent
        }
        return null
    }

    /**
     * True on API levels where Doze actually exists and matters for us.
     * Doze landed in API 23 (M).
     */
    val supported: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M

    /**
     * True if running on a Huawei / Honor device. These devices ship
     * additional, non-AOSP power management (PowerGenie, HwPFWService,
     * App Launch manager) that the standard battery whitelist does NOT
     * disable, so we need to surface extra instructions to the user.
     */
    fun isHuawei(): Boolean {
        val m = Build.MANUFACTURER.orEmpty().lowercase()
        val b = Build.BRAND.orEmpty().lowercase()
        return m.contains("huawei") || m.contains("honor") ||
            b.contains("huawei") || b.contains("honor")
    }
}
