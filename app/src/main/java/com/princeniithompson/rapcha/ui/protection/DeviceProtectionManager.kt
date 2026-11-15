package com.princeniithompson.rapcha.ui.protection

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.princeniithompson.rapcha.data.DiagnosticLogEntry
import com.princeniithompson.rapcha.diagnostics.BubbleHealthLog
import com.princeniithompson.rapcha.service.FloatingBubbleManager
import com.princeniithompson.rapcha.service.FloatingBubbleService
import com.princeniithompson.rapcha.service.RapchaAccessibilityService
import java.util.Locale

enum class AccessibilityHealthState {
    CONNECTED,
    ENABLED_PAUSED,
    OFF
}

data class OemBatteryGuide(
    val manufacturerLabel: String,
    val actionTitle: String,
    val instruction: String,
    val targets: List<Pair<String, String>>
)

data class DeviceHealthStatus(
    val overlayGranted: Boolean,
    val accessibilityState: AccessibilityHealthState,
    val notificationsGranted: Boolean,
    val notificationsRequired: Boolean,
    val ignoringBatteryOptimizations: Boolean,
    val backgroundRestricted: Boolean,
    val serviceRunningNow: Boolean,
    val lastRestartReason: String,
    val oemGuide: OemBatteryGuide,
    val recentEvents: List<DiagnosticLogEntry>
) {
    val hasAnyRed: Boolean
        get() = !overlayGranted ||
            accessibilityState != AccessibilityHealthState.CONNECTED ||
            (notificationsRequired && !notificationsGranted) ||
            !ignoringBatteryOptimizations ||
            backgroundRestricted ||
            !serviceRunningNow
}

object DeviceProtectionManager {

    fun collectStatus(context: Context): DeviceHealthStatus {
        val app = context.applicationContext ?: context
        val overlay = FloatingBubbleManager.canDrawOverlays(app)
        val a11yEnabled = FloatingBubbleManager.isAccessibilityServiceEnabled(app)
        val a11yBound = RapchaAccessibilityService.instance != null
        val a11yState = when {
            a11yBound && a11yEnabled -> AccessibilityHealthState.CONNECTED
            a11yEnabled && !a11yBound -> AccessibilityHealthState.ENABLED_PAUSED
            else -> AccessibilityHealthState.OFF
        }
        val notifRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val notifGranted = if (notifRequired) {
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        val ignoringBattery = isIgnoringBatteryOptimizations(app)
        val bgRestricted = isBackgroundRestricted(app)
        val runningNow = FloatingBubbleService.instance != null
        val restartReason = BubbleHealthLog.lastRestartReason(app)
        val oemGuide = resolveOemGuide()

        return DeviceHealthStatus(
            overlayGranted = overlay,
            accessibilityState = a11yState,
            notificationsGranted = notifGranted,
            notificationsRequired = notifRequired,
            ignoringBatteryOptimizations = ignoringBattery,
            backgroundRestricted = bgRestricted,
            serviceRunningNow = runningNow,
            lastRestartReason = restartReason,
            oemGuide = oemGuide,
            recentEvents = emptyList()
        )
    }

    fun hasAnyRed(context: Context): Boolean = collectStatus(context).hasAnyRed

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Throwable) {
            false
        }
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            am.isBackgroundRestricted
        } catch (_: Throwable) {
            false
        }
    }

    fun resolveOemGuide(manufacturer: String = Build.MANUFACTURER ?: ""): OemBatteryGuide {
        val mfg = manufacturer.trim().lowercase(Locale.US)
        return when {
            mfg.contains("samsung") -> OemBatteryGuide(
                manufacturerLabel = "Samsung",
                actionTitle = "Samsung Battery & Sleeping Apps",
                instruction = "Set Rapcha to Unrestricted and keep it out of Sleeping / Deep sleeping apps.",
                targets = listOf(
                    "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.usage.BatteryUsageLimitActivity",
                    "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
                    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity"
                )
            )
            mfg.contains("xiaomi") || mfg.contains("redmi") || mfg.contains("poco") -> OemBatteryGuide(
                manufacturerLabel = "Xiaomi",
                actionTitle = "Autostart & Battery Saver",
                instruction = "Enable Autostart for Rapcha and set MIUI battery saver to No restrictions.",
                targets = listOf(
                    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
                    "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            )
            mfg.contains("oppo") || mfg.contains("realme") || mfg.contains("oneplus") -> OemBatteryGuide(
                manufacturerLabel = "Oppo / Realme / OnePlus",
                actionTitle = "Auto-launch & Battery Usage",
                instruction = "Allow Auto-launch / background activity and set battery usage to Allow background.",
                targets = listOf(
                    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
                    "com.coloros.oppoguardelf" to "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.oplus.battery" to "com.oplus.powermanager.fuelgaue.PowerUsageModelActivity",
                    "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"
                )
            )
            mfg.contains("vivo") || mfg.contains("iqoo") -> OemBatteryGuide(
                manufacturerLabel = "Vivo / iQOO",
                actionTitle = "High Background Power Consumption",
                instruction = "Allow Rapcha to run in the background and enable Autostart.",
                targets = listOf(
                    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
                )
            )
            mfg.contains("tecno") || mfg.contains("infinix") || mfg.contains("itel") || mfg.contains("transsion") -> OemBatteryGuide(
                manufacturerLabel = "Tecno / Infinix / itel",
                actionTitle = "Auto-start & Phone Master",
                instruction = "Allow Auto-start and keep Rapcha protected from background cleanup.",
                targets = listOf(
                    "com.transsion.phonemanager" to "com.transsion.phonemanager.permission.autorun.AutoRunPermissionActivity",
                    "com.itel.phonemaster" to "com.transsion.phonemanager.permission.autorun.AutoRunPermissionActivity",
                    "com.transsion.security" to "com.transsion.security.permission.autorun.AutoRunPermissionActivity"
                )
            )
            mfg.contains("huawei") || mfg.contains("honor") -> OemBatteryGuide(
                manufacturerLabel = "Huawei / Honor",
                actionTitle = "App Launch Protection",
                instruction = "Set App Launch to Manual and enable Auto-launch, Secondary launch, and Run in background.",
                targets = listOf(
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
                    "com.huawei.systemmanager" to "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"
                )
            )
            else -> OemBatteryGuide(
                manufacturerLabel = Build.MANUFACTURER?.ifBlank { "Android" } ?: "Android",
                actionTitle = "Battery & Background Limits",
                instruction = "Open Battery settings for Rapcha and set it to Unrestricted.",
                targets = emptyList()
            )
        }
    }

    fun openOverlayPermissionSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        } else {
            appDetailsIntent(context)
        }
        launchWithFallback(context, listOf(intent, appDetailsIntent(context)))
    }

    fun openAccessibilitySettings(context: Context) {
        launchWithFallback(
            context,
            listOf(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
                appDetailsIntent(context)
            )
        )
    }

    fun openNotificationSettings(context: Context) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                add(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    }
                )
            }
            add(appDetailsIntent(context))
        }
        launchWithFallback(context, intents)
    }

    fun openBatteryOptimizationPrompt(context: Context) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(
                    Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:${context.packageName}")
                    )
                )
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(appDetailsIntent(context))
        }
        launchWithFallback(context, intents)
    }

    fun openAppDetailsSettings(context: Context) {
        launchWithFallback(context, listOf(appDetailsIntent(context)))
    }

    fun openOemBatterySettings(context: Context, guide: OemBatteryGuide = resolveOemGuide()) {
        val intents = buildList {
            guide.targets.forEach { (pkg, cls) ->
                add(
                    Intent().apply {
                        component = ComponentName(pkg, cls)
                    }
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
            add(appDetailsIntent(context))
        }
        launchWithFallback(context, intents)
    }

    private fun appDetailsIntent(context: Context): Intent {
        return Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    }

    private fun launchWithFallback(context: Context, candidates: List<Intent>) {
        val pm = context.packageManager
        for (candidate in candidates) {
            val prepared = Intent(candidate).apply {
                if (context !is Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            val resolved = try {
                prepared.resolveActivity(pm) != null
            } catch (_: Throwable) {
                false
            }
            if (!resolved) continue
            try {
                context.startActivity(prepared)
                return
            } catch (_: Throwable) {
                // Try next fallback target in the chain
            }
        }
    }
}
