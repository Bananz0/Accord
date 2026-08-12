package uk.akane.accord.ui.components

import android.content.Context
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * One vocabulary of touch feedback for the whole app.
 *
 * Everything used to go through `performHapticFeedback(CLOCK_TICK)`, which is the lightest cue the
 * framework has - a button press, a swipe arming and a swipe firing all felt identical, and all of
 * them felt like nothing. Feedback that cannot be told apart carries no information, so this
 * names the moments instead of the effects and gives each one a distinct weight.
 *
 * Composed primitives rather than the framework constants. Every device this runs on is API 31 or
 * later, and the ones that report COMPOSE_EFFECTS can be told how hard to hit - which is the whole
 * difference between a cue you notice and one you do not. Devices that cannot compose fall back to
 * predefined effects, and then to the view constants, so the vocabulary still holds.
 *
 * Routed with VibrationAttributes.USAGE_TOUCH deliberately. Driving the vibrator directly would
 * otherwise ignore the system's touch-feedback setting: somebody who has turned haptics off has
 * said so, and an app that buzzes anyway is broken rather than tactile.
 */
object Haptics {

    /** A light detent. Scrubbing past a mark, a value stepping, a list settling. */
    fun tick(view: View) = play(view, Cue.TICK)

    /** An ordinary press: a button, a row, a toggle. Alive, not forceful. */
    fun press(view: View) = play(view, Cue.PRESS)

    /**
     * A gesture has reached the point where releasing would do something.
     *
     * Firmer than a press because it is a promise: the finger is still down and this is the app
     * saying what will happen if it lifts.
     */
    fun arm(view: View) = play(view, Cue.ARM)

    /** The action fired. The heaviest cue, and the only one that should feel like a thunk. */
    fun commit(view: View) = play(view, Cue.COMMIT)

    /** The gesture reached its limit but there is nothing to do here. */
    fun reject(view: View) = play(view, Cue.REJECT)

    private enum class Cue { TICK, PRESS, ARM, COMMIT, REJECT }

    private fun play(view: View, cue: Cue) {
        val vibrator = vibrator(view.context)
        if (vibrator == null || !vibrator.hasVibrator()) {
            fallbackToView(view, cue)
            return
        }
        val effect = when {
            canCompose(vibrator) -> compose(cue)
            else -> predefined(cue)
        }
        if (effect == null) {
            fallbackToView(view, cue)
            return
        }
        runCatching {
            vibrator.vibrate(effect, TOUCH_ATTRIBUTES)
        }.onFailure { fallbackToView(view, cue) }
    }

    /**
     * Scales chosen by feel rather than by rule.
     *
     * The gaps matter more than the absolute values: a press must be clearly lighter than an arm,
     * and an arm clearly lighter than a commit, or the three collapse back into one sensation.
     */
    private fun compose(cue: Cue): VibrationEffect = when (cue) {
        Cue.TICK -> VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.45f)
            .compose()

        Cue.PRESS -> VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f)
            .compose()

        Cue.ARM -> VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f)
            .compose()

        // Two primitives, a hair apart: a single stronger click reads as a louder press, while a
        // click landing into a tick reads as something having happened.
        Cue.COMMIT -> VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1.0f)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, 40)
            .compose()

        // Deliberately dull and doubled. A refusal should not feel like a success.
        Cue.REJECT -> VibrationEffect.startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f)
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.7f, 70)
            .compose()
    }

    private fun predefined(cue: Cue): VibrationEffect? = runCatching {
        when (cue) {
            Cue.TICK -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
            Cue.PRESS -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Cue.ARM -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
            Cue.COMMIT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
            Cue.REJECT -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }
    }.getOrNull()

    private fun fallbackToView(view: View, cue: Cue) {
        val constant = when (cue) {
            Cue.TICK -> HapticFeedbackConstants.CLOCK_TICK
            Cue.PRESS -> HapticFeedbackConstants.KEYBOARD_TAP
            Cue.ARM -> HapticFeedbackConstants.CONTEXT_CLICK
            Cue.COMMIT -> HapticFeedbackConstants.LONG_PRESS
            Cue.REJECT -> HapticFeedbackConstants.REJECT
        }
        if (!view.performHapticFeedback(constant)) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun canCompose(vibrator: Vibrator): Boolean = runCatching {
        vibrator.areAllPrimitivesSupported(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
        )
    }.getOrDefault(false)

    private fun vibrator(context: Context): Vibrator? = runCatching {
        val manager = context.getSystemService(VibratorManager::class.java)
        manager?.defaultVibrator
    }.getOrNull()

    /**
     * Marks these as touch feedback, so the system silences them when the user has turned haptics
     * off and does not treat them as notifications.
     */
    private val TOUCH_ATTRIBUTES: VibrationAttributes =
        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
}
