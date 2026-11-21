package ai.gradium.phonon.tts

/**
 * Volume boost for the synthesized voice (route 1: one fixed gain for everything).
 * Pure math, no Android dependency, so the host JVM can test it.
 */
object VolumeGain {
    const val MIN_PERCENT = 100
    const val MAX_PERCENT = 300
    const val STEP_PERCENT = 5
    const val DEFAULT_PERCENT = 100

    /** SharedPreferences file and key the slider and the service share. */
    const val PREFS_NAME = "phonon"
    const val PREF_KEY = "volume_percent"

    /** Number of SeekBar steps from MIN to MAX (40 steps of 5%). */
    const val STEPS = (MAX_PERCENT - MIN_PERCENT) / STEP_PERCENT

    fun percentToStep(percent: Int): Int =
        (percent.coerceIn(MIN_PERCENT, MAX_PERCENT) - MIN_PERCENT) / STEP_PERCENT

    fun stepToPercent(step: Int): Int =
        MIN_PERCENT + step.coerceIn(0, STEPS) * STEP_PERCENT

    /** Multiply every sample by percent/100, clamped to [-1, 1]. Modifies the array in place. */
    fun apply(pcm: FloatArray, percent: Int): FloatArray {
        val gain = percent.coerceIn(MIN_PERCENT, MAX_PERCENT) / 100f
        if (gain == 1f) return pcm
        for (i in pcm.indices) {
            pcm[i] = (pcm[i] * gain).coerceIn(-1f, 1f)
        }
        return pcm
    }
}
