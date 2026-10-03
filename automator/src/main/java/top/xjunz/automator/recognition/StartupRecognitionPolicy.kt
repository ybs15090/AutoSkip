package top.xjunz.automator.recognition

/** Tracks a bounded startup window using uptime, independently of rule selection and counting. */
internal class StartupRecognitionPolicy {

    var foregroundPackageName: String? = null
        private set
    private var startupUptimeMillis = -1L

    /** Starting monitoring while an application is already open must not start a new window. */
    fun reset(currentPackageName: String?) {
        foregroundPackageName = currentPackageName
        startupUptimeMillis = -1L
    }

    /** Call only for a verified active application window, excluding keyboards/system overlays. */
    fun onForegroundApplicationChanged(packageName: String, eventUptimeMillis: Long) {
        if (foregroundPackageName == packageName) return
        val hadForegroundApplication = foregroundPackageName != null
        foregroundPackageName = packageName
        startupUptimeMillis = if (hadForegroundApplication) eventUptimeMillis else -1L
    }

    fun isRecognitionAllowed(packageName: String, startupOnly: Boolean, nowUptimeMillis: Long): Boolean {
        if (!startupOnly) return true
        if (foregroundPackageName != packageName || startupUptimeMillis < 0L) return false
        val elapsed = nowUptimeMillis - startupUptimeMillis
        return elapsed >= 0L && elapsed < MAX_STARTUP_DURATION_MILLIS
    }

    fun onSkipConfirmed(packageName: String) {
        if (foregroundPackageName == packageName) startupUptimeMillis = -1L
    }

    companion object {
        const val MAX_STARTUP_DURATION_MILLIS = 8_000L
    }
}
