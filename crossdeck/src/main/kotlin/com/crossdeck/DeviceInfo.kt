// Device + runtime context. Auto-attached to every event so
// dashboards can slice by os_version / manufacturer / model.
//
// What goes on the wire:
//   * platform — "android"
//   * os_version — Build.VERSION.RELEASE (e.g. "14")
//   * sdk_int — Build.VERSION.SDK_INT (e.g. 34)
//   * manufacturer + model
//   * locale + timezone
//   * app_package, app_version
//   * sdk_name + sdk_version
//
// What we DELIBERATELY don't collect:
//   * Advertising ID — needs user consent + Play Store privacy rules
//   * Android ID — easily de-anonymises across apps
//   * Hardware serial — same

package com.crossdeck

import android.content.Context
import android.os.Build
import java.util.Locale
import java.util.TimeZone

public data class DeviceInfo(
    val platform: String,
    val osVersion: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val locale: String,
    val timezone: String,
    val appPackage: String?,
    val appVersion: String?,
    val sdkName: String,
    val sdkVersion: String,
) {
    public fun asPayload(): Map<String, String> {
        val p = mutableMapOf(
            "platform" to platform,
            "os_version" to osVersion,
            "sdk_int" to sdkInt.toString(),
            "manufacturer" to manufacturer,
            "model" to model,
            "locale" to locale,
            "timezone" to timezone,
            "sdk_name" to sdkName,
            "sdk_version" to sdkVersion,
        )
        appPackage?.let { p["app_package"] = it }
        appVersion?.let { p["app_version"] = it }
        return p
    }

    public companion object {
        public fun capture(context: Context?): DeviceInfo {
            val pkg = context?.packageName
            val versionName: String? = try {
                if (context != null && pkg != null) {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(pkg, 0).versionName
                } else {
                    null
                }
            } catch (_: Exception) {
                null
            }

            return DeviceInfo(
                platform = "android",
                osVersion = Build.VERSION.RELEASE ?: "unknown",
                sdkInt = Build.VERSION.SDK_INT,
                manufacturer = Build.MANUFACTURER ?: "unknown",
                model = Build.MODEL ?: "unknown",
                locale = Locale.getDefault().toString(),
                timezone = TimeZone.getDefault().id,
                appPackage = pkg,
                appVersion = versionName,
                sdkName = Sdk.NAME,
                sdkVersion = Sdk.VERSION,
            )
        }
    }
}
