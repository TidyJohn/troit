package app.tunes

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration

/** Device capability, rather than screen size, selects the remote-driven television UI. */
internal fun Context.isTelevisionDevice(): Boolean =
    getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)

internal fun Context.playbackDeviceName(): String = if (isTelevisionDevice()) "Android TV" else "Phone"
