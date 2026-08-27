package top.xjunz.automator.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionAttemptPolicyTest {

    @Test
    fun nonClickableVirtualNodesReceiveAShortInitialDelay() {
        assertEquals(
            RecognitionAttemptPolicy.NON_CLICKABLE_INITIAL_DELAY_MILLIS,
            RecognitionAttemptPolicy.initialDelayMillis(
                configuredDelayMillis = 0,
                clickable = false,
                parentClickable = false
            )
        )
        assertEquals(
            0L,
            RecognitionAttemptPolicy.initialDelayMillis(
                configuredDelayMillis = 0,
                clickable = true,
                parentClickable = false
            )
        )
        assertEquals(
            1_500L,
            RecognitionAttemptPolicy.initialDelayMillis(
                configuredDelayMillis = 1_500,
                clickable = false,
                parentClickable = false
            )
        )
    }

    @Test
    fun fingerprintToleratesSmallLayoutMovementButRejectsAnotherCandidate() {
        val fingerprint = RecognitionAttemptPolicy.CandidateFingerprint(
            ruleId = "skip",
            left = 230,
            top = 164,
            right = 315,
            bottom = 219
        )
        assertTrue(fingerprint.matches("skip", 236, 168, 321, 223))
        assertFalse(fingerprint.matches("close", 236, 168, 321, 223))
        assertFalse(fingerprint.matches("skip", 600, 164, 685, 219))
    }

    @Test
    fun verificationConfirmsDisappearanceAndBoundsRetries() {
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.CONFIRMED,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.GONE,
                completedAttempts = 1
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.RETRY,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.PRESENT,
                completedAttempts = 1
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.RETRY,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.UNKNOWN,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS - 1
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.GIVE_UP,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.PRESENT,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS
            )
        )
    }
}
