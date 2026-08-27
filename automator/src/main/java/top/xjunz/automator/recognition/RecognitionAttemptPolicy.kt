package top.xjunz.automator.recognition

import kotlin.math.abs

/**
 * Timing and verification policy for a recognition click.
 *
 * UiAutomation only reports whether an input event was injected. The target application may still
 * ignore it, for example when a virtual accessibility node is published before its touch handler is
 * ready. This policy keeps retries bounded and separates an injected click from a confirmed skip.
 */
internal object RecognitionAttemptPolicy {

    const val MAX_CLICK_ATTEMPTS = 20
    const val MAX_UNAVAILABLE_CHECKS = 6
    const val NON_CLICKABLE_INITIAL_DELAY_MILLIS = 300L
    const val RETRY_INTERVAL_MILLIS = 200L
    const val MAX_ATTEMPT_DURATION_MILLIS = 4_000L

    private const val CENTER_TOLERANCE_PIXELS = 32

    data class CandidateFingerprint(
        val ruleId: String,
        val windowId: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun matches(
            actualRuleId: String,
            actualWindowId: Int,
            actualLeft: Int,
            actualTop: Int,
            actualRight: Int,
            actualBottom: Int
        ): Boolean {
            if (ruleId != actualRuleId || windowId != actualWindowId) return false
            val expectedCenterX = left + right
            val expectedCenterY = top + bottom
            val actualCenterX = actualLeft + actualRight
            val actualCenterY = actualTop + actualBottom
            return abs(expectedCenterX - actualCenterX) <= CENTER_TOLERANCE_PIXELS * 2 &&
                abs(expectedCenterY - actualCenterY) <= CENTER_TOLERANCE_PIXELS * 2
        }
    }

    enum class CandidateState {
        GONE,
        PRESENT,
        UNKNOWN
    }

    enum class VerificationDecision {
        CONFIRMED,
        RETRY,
        WAIT,
        GIVE_UP
    }

    fun initialDelayMillis(
        configuredDelayMillis: Long,
        clickable: Boolean,
        parentClickable: Boolean
    ): Long {
        val automaticDelay = if (!clickable && !parentClickable) {
            NON_CLICKABLE_INITIAL_DELAY_MILLIS
        } else {
            0L
        }
        return maxOf(configuredDelayMillis.coerceAtLeast(0L), automaticDelay)
    }

    fun decide(
        candidateState: CandidateState,
        completedAttempts: Int,
        elapsedSinceFirstClickMillis: Long
    ): VerificationDecision {
        if (candidateState == CandidateState.GONE) {
            return VerificationDecision.CONFIRMED
        }
        if (completedAttempts >= MAX_CLICK_ATTEMPTS ||
            elapsedSinceFirstClickMillis >= MAX_ATTEMPT_DURATION_MILLIS
        ) {
            return VerificationDecision.GIVE_UP
        }
        return when (candidateState) {
            CandidateState.PRESENT -> VerificationDecision.RETRY
            CandidateState.UNKNOWN -> VerificationDecision.WAIT
            CandidateState.GONE -> VerificationDecision.CONFIRMED
        }
    }
}
