package top.xjunz.automator

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.app.UiAutomationConnection
import android.app.UiAutomationHidden
import android.content.pm.IPackageManager
import android.os.*
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants.SEEK_SET
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.rikka.tools.refine.Refine
import rikka.shizuku.SystemServiceHelper
import top.xjunz.automator.model.Result
import top.xjunz.automator.recognition.LearningCandidate
import top.xjunz.automator.recognition.RecognitionAttemptPolicy
import top.xjunz.automator.recognition.RecognitionConfiguration
import top.xjunz.automator.recognition.RecognitionEngine
import top.xjunz.automator.recognition.RecognitionRuleCodec
import top.xjunz.automator.rules.ApplicationRuleMatcher
import top.xjunz.automator.util.Records
import top.xjunz.automator.util.formatCurrentTime
import java.io.*
import java.util.*
import kotlin.system.exitProcess


/**
 * The implementation of [IAutomatorConnection]. This class is expected to be executed in a privileged
 * process, e.g. shell and su, to perform functions normally.
 *
 * @see IAutomatorConnection
 *
 * @author xjunz 2021/6/22 23:01
 */
class AutomatorConnection : IAutomatorConnection.Stub() {

    companion object {
        private const val APPLICATION_ID = "top.xjunz.automator"
        const val TAG = "automator"
        const val MAX_RECORD_COUNT: Short = 500
        private const val LEARNING_CAPTURE_INTERVAL_MILLIS = 250L
        private const val MAX_STORED_LEARNING_CANDIDATES = 120
    }

    private lateinit var uiAutomationHidden: UiAutomationHidden
    private val uiAutomation by lazy {
        Refine.unsafeCast<UiAutomation>(uiAutomationHidden)
    }
    private val handlerThread = HandlerThread("AutomatorHandlerThread")
    private val handler by lazy {
        Handler(handlerThread.looper)
    }
    private var serviceStartTimestamp = -1L
    private var skippingCount = -1
    private var countFileDescriptor: ParcelFileDescriptor? = null
    private var logFileDescriptor: ParcelFileDescriptor? = null
    private var recordFileDescriptor: ParcelFileDescriptor? = null
    private val recordQueue = LinkedList<String>()
    private var firstCheckRecordIndex = -1
    private val records by lazy {
        Records(recordFileDescriptor!!.fileDescriptor)
    }
    private val recordLock = Any()
    private var monitoring: Boolean = false
    private var monitoringGeneration = 0L
    private val ruleLock = Any()
    private var applicationRulesEnabled = true
    private var strictMode = false
    @Volatile
    private var singleClickLimitEnabled = false
    private val whitelist = mutableSetOf<String>()
    private val blacklist = mutableSetOf<String>()
    @Volatile
    private var recognitionConfiguration = RecognitionConfiguration()
    private val recognitionEngine by lazy { RecognitionEngine(uiAutomation) }
    private val learningLock = Any()
    private var learningPackageName: String? = null
    private var lastLearningCaptureTimestamp = 0L
    private val learningCandidates = linkedMapOf<String, LearningCandidate>()

    private data class PendingRecognitionAttempt(
        val token: Long,
        val generation: Long,
        val packageName: String,
        val ruleId: String,
        var completedAttempts: Int = 0,
        var unavailableChecks: Int = 0,
        var fingerprint: RecognitionAttemptPolicy.CandidateFingerprint? = null,
        var lastResult: Result? = null
    )

    init {
        try {
            log("========Start Connecting========")
            log(sayHello())
            handlerThread.start()
            uiAutomationHidden = UiAutomationHidden(handlerThread.looper, UiAutomationConnection())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uiAutomationHidden.connect(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            } else {
                log("Marshmallow don't support FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES")
                uiAutomationHidden.connect()
            }
            log("The UiAutomation is connected at ${formatCurrentTime()}")
        } catch (t: Throwable) {
            dumpError(t)
            exitProcess(0)
        }
    }

    private val launcherName by lazy {
        IPackageManager.Stub.asInterface(SystemServiceHelper.getSystemService("package"))
            ?.getHomeActivities(arrayListOf())?.packageName
    }

    private fun log(any: Any?, queued: Boolean = true) {
        (any?.toString() ?: "null").let {
            Log.i(TAG, it)
            if (queued) {
                recordQueue.add(it)
                if (recordQueue.size > MAX_RECORD_COUNT) {
                    if (firstCheckRecordIndex >= 0) {
                        //truncate check result records
                        recordQueue.removeAt(firstCheckRecordIndex)
                    } else {
                        recordQueue.removeFirst()
                    }
                }
            }
        }
    }

    private fun dumpResult(result: Result, queued: Boolean) {
        if (firstCheckRecordIndex == -1) firstCheckRecordIndex = recordQueue.size
        val sb = StringBuilder()
        sb.append("========Check Result========")
            .append("\ntimestamp: ${formatCurrentTime()}")
            .append("\nresult: $result")
        if (result.passed) {
            val injectType = if (result.getInjectionType() == Result.INJECTION_ACTION) "action" else "event"
            sb.append("\nskip: count=$skippingCount, injection type=$injectType")
        }
        log(sb.toString(), queued)
    }

    private fun dumpError(t: Throwable) {
        log("========Error Occurred========")
        log(t.stackTraceToString())
    }

    override fun startMonitoring() {
        val generation = ++monitoringGeneration
        var distinct = false
        var oldPkgName: String? = null
        var nextAttemptToken = 0L
        val activeAttempts = mutableMapOf<String, PendingRecognitionAttempt>()
        uiAutomation.serviceInfo = uiAutomation.serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }

        fun processResult(result: Result) {
            if (result.passed && distinct) {
                synchronized(recordLock) {
                    skippingCount++
                    distinct = false
                    records.putResult(result)
                }
            }
            if (result.getReason() != Result.REASON_ILLEGAL_TARGET &&
                lastResultHash != result.hashCode()
            ) {
                dumpResult(result, true)
                lastResultHash = result.hashCode()
            }
        }

        fun isAttemptActive(attempt: PendingRecognitionAttempt): Boolean {
            return monitoring && attempt.generation == monitoringGeneration &&
                attempt.generation == generation &&
                activeAttempts[attempt.packageName]?.token == attempt.token
        }

        fun finishAttempt(attempt: PendingRecognitionAttempt) {
            if (activeAttempts[attempt.packageName]?.token == attempt.token) {
                activeAttempts.remove(attempt.packageName)
            }
        }

        fun isAttemptAllowed(attempt: PendingRecognitionAttempt): Boolean {
            return !isLearningPackage(attempt.packageName) &&
                isPackageEnabled(attempt.packageName) &&
                !(singleClickLimitEnabled && !distinct)
        }

        fun confirmAttempt(attempt: PendingRecognitionAttempt) {
            if (!isAttemptActive(attempt)) return
            val result = attempt.lastResult ?: run {
                finishAttempt(attempt)
                return
            }
            finishAttempt(attempt)
            result.passed = true
            processResult(result)
        }

        fun giveUpAttempt(attempt: PendingRecognitionAttempt) {
            if (!isAttemptActive(attempt)) return
            attempt.lastResult?.passed = false
            finishAttempt(attempt)
            log(
                "Skip click not confirmed: pkg=${attempt.packageName}, " +
                    "rule=${attempt.ruleId}, attempts=${attempt.completedAttempts}"
            )
        }

        lateinit var executeAttempt: (PendingRecognitionAttempt) -> Unit
        lateinit var verifyAttempt: (PendingRecognitionAttempt) -> Unit

        fun scheduleExecution(attempt: PendingRecognitionAttempt, delayMillis: Long) {
            handler.postDelayed({ executeAttempt(attempt) }, delayMillis.coerceAtLeast(0L))
        }

        fun handleVerificationDecision(
            attempt: PendingRecognitionAttempt,
            candidateState: RecognitionAttemptPolicy.CandidateState
        ) {
            if (!isAttemptActive(attempt)) return
            when (RecognitionAttemptPolicy.decide(candidateState, attempt.completedAttempts)) {
                RecognitionAttemptPolicy.VerificationDecision.CONFIRMED -> confirmAttempt(attempt)
                RecognitionAttemptPolicy.VerificationDecision.RETRY -> scheduleExecution(
                    attempt,
                    RecognitionAttemptPolicy.RETRY_DELAY_MILLIS
                )
                RecognitionAttemptPolicy.VerificationDecision.GIVE_UP -> giveUpAttempt(attempt)
            }
        }

        verifyAttempt = verify@{ attempt ->
            if (!isAttemptActive(attempt)) return@verify
            if (!isAttemptAllowed(attempt)) {
                finishAttempt(attempt)
                return@verify
            }
            var root: AccessibilityNodeInfo? = null
            var currentMatch: RecognitionEngine.Match? = null
            var candidateState = RecognitionAttemptPolicy.CandidateState.UNKNOWN
            try {
                val activeRoot = uiAutomation.rootInActiveWindow
                root = activeRoot
                val currentPackageName = activeRoot?.packageName?.toString()
                if (activeRoot != null && currentPackageName != null &&
                    currentPackageName != attempt.packageName
                ) {
                    candidateState = RecognitionAttemptPolicy.CandidateState.GONE
                } else if (activeRoot != null && currentPackageName != null) {
                    currentMatch = recognitionEngine.findBestMatch(
                        activeRoot,
                        attempt.packageName,
                        recognitionConfiguration.rulesFor(attempt.packageName),
                        attempt.ruleId
                    )
                    val match = currentMatch
                    candidateState = if (match == null) {
                        RecognitionAttemptPolicy.CandidateState.GONE
                    } else {
                        val fingerprint = attempt.fingerprint
                        if (fingerprint != null && fingerprint.matches(
                                match.rule.id,
                                match.bounds.left,
                                match.bounds.top,
                                match.bounds.right,
                                match.bounds.bottom
                            )
                        ) {
                            RecognitionAttemptPolicy.CandidateState.PRESENT
                        } else {
                            RecognitionAttemptPolicy.CandidateState.GONE
                        }
                    }
                }
            } catch (t: Throwable) {
                dumpError(t)
            } finally {
                currentMatch?.recycle()
                root?.recycle()
            }
            handleVerificationDecision(attempt, candidateState)
        }

        executeAttempt = execute@{ attempt ->
            if (!isAttemptActive(attempt)) return@execute
            if (!isAttemptAllowed(attempt)) {
                finishAttempt(attempt)
                return@execute
            }
            var root: AccessibilityNodeInfo? = null
            var currentMatch: RecognitionEngine.Match? = null
            try {
                val activeRoot = uiAutomation.rootInActiveWindow
                root = activeRoot
                if (activeRoot == null) {
                    attempt.unavailableChecks++
                    if (attempt.unavailableChecks < RecognitionAttemptPolicy.MAX_UNAVAILABLE_CHECKS) {
                        scheduleExecution(attempt, RecognitionAttemptPolicy.RETRY_DELAY_MILLIS)
                    } else if (attempt.lastResult != null) {
                        giveUpAttempt(attempt)
                    } else {
                        finishAttempt(attempt)
                    }
                    return@execute
                }
                val currentPackageName = activeRoot.packageName?.toString()
                if (currentPackageName == null) {
                    attempt.unavailableChecks++
                    if (attempt.unavailableChecks < RecognitionAttemptPolicy.MAX_UNAVAILABLE_CHECKS) {
                        scheduleExecution(attempt, RecognitionAttemptPolicy.RETRY_DELAY_MILLIS)
                    } else if (attempt.lastResult != null) {
                        giveUpAttempt(attempt)
                    } else {
                        finishAttempt(attempt)
                    }
                    return@execute
                }
                if (currentPackageName != attempt.packageName) {
                    if (attempt.lastResult != null) confirmAttempt(attempt) else finishAttempt(attempt)
                    return@execute
                }
                currentMatch = recognitionEngine.findBestMatch(
                    activeRoot,
                    attempt.packageName,
                    recognitionConfiguration.rulesFor(attempt.packageName),
                    attempt.ruleId
                )
                val match = currentMatch
                if (match == null) {
                    if (attempt.lastResult != null) {
                        confirmAttempt(attempt)
                    } else {
                        attempt.unavailableChecks++
                        if (attempt.unavailableChecks < RecognitionAttemptPolicy.MAX_UNAVAILABLE_CHECKS) {
                            scheduleExecution(attempt, RecognitionAttemptPolicy.RETRY_DELAY_MILLIS)
                        } else {
                            finishAttempt(attempt)
                        }
                    }
                    return@execute
                }
                attempt.unavailableChecks = 0
                attempt.completedAttempts++
                val outcome = recognitionEngine.execute(match)
                if (!outcome.successful) {
                    handleVerificationDecision(
                        attempt,
                        RecognitionAttemptPolicy.CandidateState.UNKNOWN
                    )
                    return@execute
                }
                val result = Result()
                recognitionEngine.writeResult(match, outcome, result)
                attempt.lastResult = result
                attempt.fingerprint = RecognitionAttemptPolicy.CandidateFingerprint(
                    match.rule.id,
                    match.bounds.left,
                    match.bounds.top,
                    match.bounds.right,
                    match.bounds.bottom
                )
                handler.postDelayed(
                    { verifyAttempt(attempt) },
                    RecognitionAttemptPolicy.VERIFICATION_DELAY_MILLIS
                )
            } catch (t: Throwable) {
                dumpError(t)
                handleVerificationDecision(
                    attempt,
                    RecognitionAttemptPolicy.CandidateState.UNKNOWN
                )
            } finally {
                currentMatch?.recycle()
                root?.recycle()
            }
        }

        monitoring = true
        uiAutomation.setOnAccessibilityEventListener listener@{ event ->
            var eventSource: AccessibilityNodeInfo? = null
            var scanRoot: AccessibilityNodeInfo? = null
            try {
                val packageName = event.packageName?.toString() ?: return@listener
                eventSource = event.source
                val source = uiAutomation.rootInActiveWindow ?: eventSource ?: return@listener
                scanRoot = source
                if (source.packageName?.toString() != packageName) return@listener
                if (oldPkgName != packageName) {
                    oldPkgName?.let { activeAttempts.remove(it) }
                    distinct = true
                    oldPkgName = packageName
                }
                if (isLearningPackage(packageName)) {
                    captureLearningCandidates(source, packageName)
                    return@listener
                }
                //ignore the launcher app
                if (packageName == launcherName) return@listener
                //ignore the android framework
                if (packageName == "android") return@listener
                //ignore android build-in apps
                if (packageName.startsWith("com.android")) return@listener
                //ignore the host app
                if (packageName == APPLICATION_ID) return@listener
                //ignore packages disabled by the user
                if (!isPackageEnabled(packageName)) return@listener
                //when enabled, allow only one injected click during the same foreground session
                if (singleClickLimitEnabled && !distinct) return@listener
                if (activeAttempts.containsKey(packageName)) return@listener
                val rules = recognitionConfiguration.rulesFor(packageName)
                val match = recognitionEngine.findBestMatch(source, packageName, rules)
                    ?: return@listener
                try {
                    val attempt = PendingRecognitionAttempt(
                        token = ++nextAttemptToken,
                        generation = generation,
                        packageName = packageName,
                        ruleId = match.rule.id
                    )
                    activeAttempts[packageName] = attempt
                    val delayMillis = RecognitionAttemptPolicy.initialDelayMillis(
                        match.rule.delayMillis,
                        match.clickable,
                        match.parentClickable
                    )
                    scheduleExecution(attempt, delayMillis)
                } finally {
                    match.recycle()
                }
            } catch (t: Throwable) {
                dumpError(t)
            } finally {
                if (scanRoot !== eventSource) scanRoot?.recycle()
                eventSource?.recycle()
                event.recycle()
            }
        }
        serviceStartTimestamp = System.currentTimeMillis()
        log("The monitoring is started at ${formatCurrentTime()}")
    }

    override fun isMonitoring() = monitoring

    override fun sayHello() = "Hello from the remote service! My uid is ${Os.geteuid()} & my pid is ${Os.getpid()}"

    override fun getStartTimestamp() = serviceStartTimestamp

    override fun getPid() = Os.getpid()

    override fun getSkippingCount() = synchronized(recordLock) { skippingCount }

    override fun setFileDescriptors(pfds: Array<ParcelFileDescriptor>?) {
        check(pfds != null && pfds.size == 3)
        countFileDescriptor = pfds[0]
        checkNotNull(countFileDescriptor)
        logFileDescriptor = pfds[1]
        checkNotNull(logFileDescriptor)
        recordFileDescriptor = pfds[2]
        checkNotNull(recordFileDescriptor)
        log("File descriptors received")
        try {
            FileInputStream(countFileDescriptor!!.fileDescriptor).bufferedReader().useLines {
                skippingCount = it.firstOrNull()?.toIntOrNull() ?: 0
                log("The skipping count parsed: $skippingCount")
            }
            synchronized(recordLock) {
                records.parse().getRecordCount().also {
                    if (skippingCount != it) {
                        skippingCount = it
                        log("Skipping count inconsistency detected! Reassign the skipping count to $it")
                    }
                }
            }
            log("The record file parsed")
        } catch (t: Throwable) {
            if (t is Records.ParseException) {
                log(t.message)
            } else {
                dumpError(t)
            }
        }
    }

    override fun setBasicEnvInfo(info: String?) = log(info)

    override fun configureRules(
        enabled: Boolean,
        strictMode: Boolean,
        whitelist: MutableList<String>?,
        blacklist: MutableList<String>?,
        singleClickLimitEnabled: Boolean
    ) = synchronized(ruleLock) {
        applicationRulesEnabled = enabled
        this.strictMode = strictMode
        this.singleClickLimitEnabled = singleClickLimitEnabled
        this.whitelist.clear()
        whitelist?.let(this.whitelist::addAll)
        this.blacklist.clear()
        blacklist?.let(this.blacklist::addAll)
        Unit
    }

    override fun configureRecognitionRules(rulesJson: String?) {
        recognitionConfiguration = RecognitionRuleCodec.decode(rulesJson)
        handler.post {
            log(
                "Recognition rules configured: global=${recognitionConfiguration.globalRules.size}, " +
                    "applications=${recognitionConfiguration.applicationRules.size}"
            )
        }
    }

    override fun startRuleLearning(packageName: String?) = synchronized(learningLock) {
        require(!packageName.isNullOrBlank())
        learningPackageName = packageName
        lastLearningCaptureTimestamp = 0L
        learningCandidates.clear()
        handler.post { log("Rule learning started for $packageName") }
        Unit
    }

    override fun getRuleLearningCandidates() = synchronized(learningLock) {
        RecognitionRuleCodec.encodeCandidates(
            learningCandidates.values.sortedWith(
                compareByDescending<LearningCandidate> { it.score }
                    .thenByDescending { it.timestamp }
            )
        )
    }

    override fun stopRuleLearning() = synchronized(learningLock) {
        learningPackageName?.let { packageName ->
            handler.post { log("Rule learning stopped for $packageName") }
        }
        learningPackageName = null
        Unit
    }

    private fun isPackageEnabled(packageName: String) = synchronized(ruleLock) {
        ApplicationRuleMatcher.isPackageEnabled(
            applicationRulesEnabled,
            strictMode,
            whitelist,
            blacklist,
            packageName
        )
    }

    private fun isLearningPackage(packageName: String) = synchronized(learningLock) {
        learningPackageName == packageName
    }

    private fun captureLearningCandidates(source: AccessibilityNodeInfo, packageName: String) {
        val timestamp = System.currentTimeMillis()
        synchronized(learningLock) {
            if (learningPackageName != packageName ||
                timestamp - lastLearningCaptureTimestamp < LEARNING_CAPTURE_INTERVAL_MILLIS
            ) {
                return
            }
            lastLearningCaptureTimestamp = timestamp
        }
        val discovered = recognitionEngine.discoverCandidates(source, packageName, timestamp)
        synchronized(learningLock) {
            if (learningPackageName != packageName) return
            discovered.forEach { learningCandidates[it.key] = it }
            if (learningCandidates.size > MAX_STORED_LEARNING_CANDIDATES) {
                val retained = learningCandidates.values.sortedWith(
                    compareByDescending<LearningCandidate> { it.score }
                        .thenByDescending { it.timestamp }
                ).take(MAX_STORED_LEARNING_CANDIDATES)
                learningCandidates.clear()
                retained.forEach { learningCandidates[it.key] = it }
            }
        }
    }

    override fun setSkippingCount(count: Int) {
        check(count > -1)
        synchronized(recordLock) {
            skippingCount = count
        }
    }

    /**
     * The hashcode record of the last check [Result].
     */
    private var lastResultHash = -1

    /**
     * Launch a standalone check.
     *
     * @param listener a listener to be notified the result
     */
    override fun standaloneCheck(listener: OnCheckResultListener) {
        handler.post {
            val standaloneResult = Result()
            var root: AccessibilityNodeInfo? = null
            var match: RecognitionEngine.Match? = null
            try {
                root = uiAutomation.rootInActiveWindow
                val packageName = root?.packageName?.toString()
                if (root == null || packageName == null) {
                    standaloneResult.maskReason(Result.REASON_ILLEGAL_TARGET)
                } else {
                    match = recognitionEngine.findBestMatch(
                        root,
                        packageName,
                        recognitionConfiguration.rulesFor(packageName)
                    )
                    if (match == null) {
                        standaloneResult.maskReason(Result.REASON_ILLEGAL_TARGET)
                    } else {
                        recognitionEngine.writeResult(
                            match,
                            RecognitionEngine.ExecutionOutcome(
                                successful = true,
                                effectiveBounds = match.bounds
                            ),
                            standaloneResult,
                            detectionOnly = true
                        )
                    }
                }
            } catch (t: Throwable) {
                dumpError(t)
                standaloneResult.maskReason(Result.REASON_ERROR)
            } finally {
                match?.recycle()
                root?.recycle()
                //dump the result before calling the listener, cuz a marshall of result would
                //recycle the node, hence, we could not dump it any more.
                dumpResult(standaloneResult, !standaloneResult.passed)
                listener.onCheckResult(standaloneResult)
            }
        }
    }

    override fun getRecords() = synchronized(recordLock) { records.asList() }

    override fun deleteRecord(packageName: String?): Boolean = synchronized(recordLock) {
        require(!packageName.isNullOrBlank())
        val deleted = records.removePackage(packageName)
        if (deleted) {
            skippingCount = records.getRecordCount()
            persistRecordStateLocked()
            log("Deleted records for $packageName")
        }
        deleted
    }

    override fun clearRecords() = synchronized(recordLock) {
        records.clear()
        skippingCount = 0
        persistRecordStateLocked()
        log("All skipping records cleared")
        Unit
    }

    /**
     * Truncate the file's length to 0 and seek its r/w position to 0, namely, clear its content.
     */
    private fun truncate(pfd: ParcelFileDescriptor) {
        try {
            Os.ftruncate(pfd.fileDescriptor, 0)
            Os.lseek(pfd.fileDescriptor, 0, SEEK_SET)
        } catch (e: ErrnoException) {
            dumpError(e)
        }
    }

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            log("Service is dead at ${formatCurrentTime()}. Goodbye, world!")
            persistRecordState()
            persistLog()
        })
    }

    override fun persistLog() {
        if (recordQueue.size != 0) logFileDescriptor?.run {
            truncate(this)
            FileOutputStream(fileDescriptor).bufferedWriter().use { writer ->
                recordQueue.forEach {
                    writer.write(it)
                    writer.newLine()
                }
                writer.flush()
            }
        }
    }

    private inline fun writeFile(
        pfd: ParcelFileDescriptor,
        write: (BufferedWriter) -> Unit
    ) {
        truncate(pfd)
        val duplicate = ParcelFileDescriptor.dup(pfd.fileDescriptor)
        ParcelFileDescriptor.AutoCloseOutputStream(duplicate).bufferedWriter().use { writer ->
            write(writer)
            writer.flush()
        }
    }

    private fun persistRecordState() = synchronized(recordLock) {
        persistRecordStateLocked()
    }

    private fun persistRecordStateLocked() {
        persistSkippingCountLocked()
        persistRecordsLocked()
    }

    private fun persistSkippingCountLocked() {
        if (skippingCount != -1) {
            countFileDescriptor?.run {
                writeFile(this) {
                    it.write(skippingCount.toString())
                }
            }
        }
    }

    private fun persistRecordsLocked() {
        recordFileDescriptor?.run {
            writeFile(this) {
                records.forEach { record ->
                    it.write(record.toString())
                    it.newLine()
                }
            }
        }
    }


    override fun destroy() {
        try {
            uiAutomationHidden.disconnect()
        } catch (t: Throwable) {
            dumpError(t)
        } finally {
            if (handlerThread.isAlive) {
                handlerThread.quitSafely()
            }
            exitProcess(0)
        }
    }
}
