package com.lenscode.app.ui

/**
 * Code editor font size, in sp. Set by voice ("set font size to 16", "make the
 * code bigger") — typing a number in AR is the thing we're avoiding — and kept
 * inside a readable range so a misheard "160" can't make the editor unusable.
 */
object EditorFontSize {
    const val MIN = 8
    const val MAX = 32
    const val DEFAULT = 12
    const val STEP = 2

    /**
     * The next size: an absolute [size] wins; otherwise [change] is "larger",
     * "smaller" or "reset" relative to [current]. Anything else keeps [current].
     * Always clamped to [MIN]..[MAX].
     */
    fun resolve(current: Int, size: Int?, change: String?): Int {
        val next = when {
            size != null -> size
            change == "larger" -> current + STEP
            change == "smaller" -> current - STEP
            change == "reset" -> DEFAULT
            else -> current
        }
        return next.coerceIn(MIN, MAX)
    }
}
