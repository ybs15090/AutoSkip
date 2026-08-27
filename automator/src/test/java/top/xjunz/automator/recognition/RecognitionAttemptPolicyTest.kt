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
            windowId = 7,
            left = 230,
            top = 164,
            right = 315,
            bottom = 219
        )
        assertTrue(fingerprint.matches("skip", 7, 236, 168, 321, 223))
        assertFalse(fingerprint.matches("skip", 8, 236, 168, 321, 223))
        assertFalse(fingerprint.matches("close", 7, 236, 168, 321, 223))
        assertFalse(fingerprint.matches("skip", 7, 600, 164, 685, 219))
    }

    @Test
    fun verificationRetriesEveryTwoHundredMillisecondsAndStopsAtBounds() {
        assertEquals(200L, RecognitionAttemptPolicy.RETRY_INTERVAL_MILLIS)
        assertEquals(20, RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS)
        assertEquals(4_000L, RecognitionAttemptPolicy.MAX_ATTEMPT_DURATION_MILLIS)
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.CONFIRMED,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.GONE,
                completedAttempts = 1,
                elapsedSinceFirstClickMillis = 200L
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.RETRY,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.PRESENT,
                completedAttempts = 1,
                elapsedSinceFirstClickMillis = 200L
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.WAIT,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.UNKNOWN,
                completedAttempts = 1,
                elapsedSinceFirstClickMillis = 200L
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.RETRY,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.PRESENT,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS - 1,
                elapsedSinceFirstClickMillis = 3_800L
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.GIVE_UP,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.PRESENT,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS,
                elapsedSinceFirstClickMillis = 3_800L
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.GIVE_UP,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.PRESENT,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS - 1,
                elapsedSinceFirstClickMillis =
                    RecognitionAttemptPolicy.MAX_ATTEMPT_DURATION_MILLIS
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.GIVE_UP,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.UNKNOWN,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS - 1,
                elapsedSinceFirstClickMillis =
                    RecognitionAttemptPolicy.MAX_ATTEMPT_DURATION_MILLIS
            )
        )
        assertEquals(
            RecognitionAttemptPolicy.VerificationDecision.CONFIRMED,
            RecognitionAttemptPolicy.decide(
                RecognitionAttemptPolicy.CandidateState.GONE,
                completedAttempts = RecognitionAttemptPolicy.MAX_CLICK_ATTEMPTS,
                elapsedSinceFirstClickMillis =
                    RecognitionAttemptPolicy.MAX_ATTEMPT_DURATION_MILLIS
            )
        )
    }
}
