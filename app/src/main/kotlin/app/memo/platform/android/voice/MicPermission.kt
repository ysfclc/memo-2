package app.memo.platform.android.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Microphone permission checks. The system request itself is made by the UI, not here.
 *
 * [AudioCapture] calls [isGranted] before it opens the microphone and refuses to start without
 * it, so no capture can begin from a code path that skipped the UI.
 */
object MicPermission {

    /** The Android permission that guards microphone access. */
    const val PERMISSION: String = Manifest.permission.RECORD_AUDIO

    /**
     * Plain-language reason shown before the system prompt. It describes what the microphone is
     * used for and where audio goes. Moves to string resources with the localisation task.
     */
    const val RATIONALE: String =
        "Memo uses the microphone only while you start a voice conversation or turn on \"Hey Memo\". " +
            "Speech is processed on this device, and audio is not saved."

    /** True when the app currently holds [PERMISSION]. */
    fun isGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the UI should show [RATIONALE] before asking again. Android returns true after a
     * denial that was not "don't ask again".
     */
    fun shouldShowRationale(activity: Activity): Boolean =
        ActivityCompat.shouldShowRequestPermissionRationale(activity, PERMISSION)
}
