package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Tactile feedback like Swiggy: a barely-there tick when a button is tapped. A vibration only, no sound.
 * The rider can switch it off in Profile.
 */
object AppFeedback {
    private const val PREFS = "partner_feedback"
    private const val KEY_TAP = "tap_haptic"
    private var appContext: Context? = null

    @Volatile var tapEnabled: Boolean = true
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        tapEnabled = appContext!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TAP, true)
    }

    fun setTapEnabled(enabled: Boolean) {
        tapEnabled = enabled
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean(KEY_TAP, enabled)?.apply()
        if (enabled) tap() // let the rider feel it right away
    }

    /** Any button tap: a soft tick. */
    fun tap() {
        if (tapEnabled) vibrate(9)
    }

    private fun vibrator(): Vibrator? {
        val ctx = appContext ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun vibrate(ms: Long) {
        try {
            val v = vibrator() ?: return
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        } catch (_: Exception) {
        }
    }
}

/**
 * App-wide tap feedback: any press that a button, tab, chip or switch already consumed gets the soft tick. Put it on the
 * root container. Scrolling and touches on plain areas are left alone, because nothing consumes their first down event.
 */
fun Modifier.globalTapFeedback(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        // Final pass: the children (the clickable) have already seen the event by now.
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
        if (down.isConsumed) AppFeedback.tap()
    }
}

/** The same tap feedback as a ready-made modifier, for dialogs: a dialog is its own window, so the root one cannot hear it. */
val TapFeedbackModifier: Modifier get() = Modifier.globalTapFeedback()
