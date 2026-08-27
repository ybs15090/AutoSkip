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

    const val MAX_CLICK_ATTEMPTS = 4
    const val MAX_UNAVAILABLE_CHECKS = 6
    const val NON_CLICKABLE_INITIAL_DELAY_MILLIS = 300L
    const val VERIFICATION_DELAY_MILLIS = 600L
    const val RETRY_DELAY_MILLIS = 400L

    private const val CENTER_TOLERANCE_PIXELS = 32

    data class CandidateFingerprint(
        val ruleId: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        fun matches(
            actualRuleId: String,
            actualLeft: Int,
            actualTop: Int,
            actualRight: Int,
            actualBottom: Int
        ): Boolean {
            if (ruleId != actualRuleId) return false
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
        completedAttempts: Int
    ): VerificationDecision {
        if (candidateState == CandidateState.GONE) {
            return VerificationDecision.CONFIRMED
        }
        return if (completedAttempts < MAX_CLICK_ATTEMPTS) {
            VerificationDecision.RETRY
        } else {
            VerificationDecision.GIVE_UP
        }
    }
}
