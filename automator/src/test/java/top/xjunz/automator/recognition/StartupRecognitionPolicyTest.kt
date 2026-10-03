package top.xjunz.automator.recognition

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupRecognitionPolicyTest {

    private val policy = StartupRecognitionPolicy()
    private val packageName = "example.app"

    private fun allowed(now: Long, startupOnly: Boolean = true): Boolean {
        return policy.isRecognitionAllowed(packageName, startupOnly, now)
    }

    private fun launch(at: Long = 1_000L) {
        policy.reset("example.launcher")
        policy.onForegroundApplicationChanged(packageName, at)
    }

    @Test
    fun disabledSettingPreservesRecognitionWithoutAStartupWindow() {
        assertTrue(allowed(20_000L, startupOnly = false))
        launch()
        policy.onSkipConfirmed(packageName)
        assertTrue(allowed(20_000L, startupOnly = false))
    }

    @Test
    fun serviceStartupAndUnknownInitialForegroundDoNotOpenAWindow() {
        policy.reset(packageName)
        policy.onForegroundApplicationChanged(packageName, 1_000L)
        assertFalse(allowed(1_000L))
        policy.reset(null)
        policy.onForegroundApplicationChanged(packageName, 2_000L)
        assertFalse(allowed(2_000L))
    }

    @Test
    fun launchAllowsRecognitionOnlyBeforeTheDeadline() {
        launch()
        assertFalse(allowed(999L))
        assertTrue(allowed(1_000L))
        assertTrue(allowed(8_999L))
        assertFalse(allowed(9_000L))
        assertFalse(allowed(30_000L))
    }

    @Test
    fun internalPageChangesDoNotExtendOrReopenTheWindow() {
        launch()
        policy.onForegroundApplicationChanged(packageName, 7_000L)
        assertFalse(allowed(9_000L))
        policy.onForegroundApplicationChanged(packageName, 20_000L)
        assertFalse(allowed(20_000L))
    }

    @Test
    fun confirmedSkipEndsRecognitionUntilTheNextForegroundSession() {
        launch()
        policy.onSkipConfirmed(packageName)
        assertFalse(allowed(1_200L))
        policy.onForegroundApplicationChanged(packageName, 1_500L)
        assertFalse(allowed(1_500L))
        policy.onForegroundApplicationChanged("example.launcher", 2_000L)
        policy.onForegroundApplicationChanged(packageName, 3_000L)
        assertTrue(allowed(3_000L))
    }

    @Test
    fun leavingTheApplicationBlocksItsPendingClicksAndReturningStartsANewWindow() {
        launch()
        policy.onForegroundApplicationChanged("example.other", 2_000L)
        assertFalse(allowed(2_100L))
        policy.onForegroundApplicationChanged(packageName, 3_000L)
        assertTrue(allowed(3_100L))
        policy.onSkipConfirmed("example.other")
        assertTrue(allowed(3_200L))
    }

    @Test
    fun delayedEventDeliveryDoesNotExtendTheStartupWindow() {
        launch(at = 1_000L)
        assertFalse(allowed(10_000L))
    }

    @Test
    fun resettingMonitoringClosesAnyExistingWindow() {
        launch()
        policy.reset(packageName)
        assertFalse(allowed(1_100L))
    }
}
