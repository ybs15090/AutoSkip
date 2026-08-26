package top.xjunz.automator.app

import android.app.Application
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID
import top.xjunz.automator.AutomatorConnection
import top.xjunz.automator.BuildConfig
import top.xjunz.automator.IAutomatorConnection
import top.xjunz.automator.OnCheckResultListener
import top.xjunz.automator.model.Record
import top.xjunz.automator.rules.AppRulePreferences
import top.xjunz.automator.rules.RecognitionRulePreferences
import top.xjunz.automator.recognition.LearningCandidate
import top.xjunz.automator.recognition.RecognitionRuleCodec
import top.xjunz.automator.util.Records
import java.io.FileInputStream
import java.util.*
import java.util.concurrent.TimeoutException


/**
 * A [ViewModel], which manipulating the automator service. This [ViewModel] is across the
 * [Application]'s lifecycle cuz it's stored in the [AutomatorApp.appViewModelStore]. We do so
 * because we want this [ViewModel] to be shared within activities.
 *
 * @author xjunz 2021/6/26
 */
class AutomatorViewModel constructor(val app: Application) : AndroidViewModel(app) {
    private val tag = "Automator"
    var initialized = false

    fun init() {
        if (initialized) return
        initialized = true
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        readSkippingCount()
    }

    companion object {
        const val BINDING_SERVICE_TIMEOUT_MILLS = 5000L
        fun get(): AutomatorViewModel {
            return ViewModelProvider(AutomatorApp.me, ViewModelProvider.AndroidViewModelFactory(AutomatorApp.me))
                .get(AutomatorViewModel::class.java)
        }
    }

    /**
     * Whether we have got the Shizuku permission or not.
     */
    val isGranted = MutableLiveData<Boolean>()

    /**
     * Whether our service is running or not. When [isAvailable] becomes false, our service would be
     * killed expectedly. Meanwhile, [isGranted] will not affect this value once our service is started.
     */
    val isRunning = MutableLiveData<Boolean>()

    /**
     * Whether our service is under binding state.
     */
    val isBinding = MutableLiveData<Boolean>()

    /**
     * Whether the Shizuku service is started and we've obtained the binder or not.
     */
    val isAvailable = MutableLiveData<Boolean>()

    /**
     * Whether the shizuku manager is installed and enabled.
     */
    val isInstalled = MutableLiveData<Boolean>()

    /**
     * Whether the auto starter has tried to start the service on boot no matter whether it succeeded
     * or not.
     */
    val isAutoStarted = MutableLiveData<Boolean?>()

    val error = MutableLiveData<Throwable?>()

    val skippingTimes = MutableLiveData<Int?>()

    val latestAction = MutableLiveData<Record?>()

    /**
     * The millisecond timestamp when our service is started.
     */
    var serviceStartTimestamp = -1L

    var pfds = arrayOfNulls<ParcelFileDescriptor>(3)

    /**
     * Check whether the Shizuku manager is installed on this device.
     */
    fun syncShizukuInstallationState() {
        isInstalled.value = runCatching {
            app.packageManager.getApplicationInfo(MANAGER_APPLICATION_ID, PackageManager.GET_UNINSTALLED_PACKAGES)
        }.isSuccess
    }


    private val serviceNameSuffix = "service"
    private val serviceName = "${BuildConfig.APPLICATION_ID}:$serviceNameSuffix"

    /**
     * Bind a running service if any and kill all unbindable services created by us to avoid launching
     * multiple services.
     *
     * Shizuku has no build-in apis to judge whether a user service is still alive or not.
     * Then we have to fallback using the 'ps' cmd.
     *
     * V12: [Shizuku.peekUserService] is not safe in some cases.
     */
    @Suppress("Deprecation")
    fun bindRunningServiceOrKill() = viewModelScope.launch {
        isBinding.value = true
        var bound: Boolean? = null
        withContext(Dispatchers.IO) {
            val isAboveO = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            Shizuku.newProcess(if (isAboveO) arrayOf("ps", "-A", "-o", "pid", "-o", "name") else arrayOf("ps"), null, "/")
                .apply {
                    inputStream.bufferedReader().useLines { sequence ->
                        sequence.forEach { line ->
                            if (line.endsWith(serviceName)) {
                                val pid = if (isAboveO) {
                                    line.trimIndent().split(" ")[0]
                                } else {
                                    StringTokenizer(line, " ").run {
                                        nextToken()
                                        nextToken()
                                    }
                                }
                                if (bound == null) {
                                    bound = bindServiceLocked()
                                    if (bound != true) {
                                        killProcess(pid)
                                    }
                                } else {
                                    killProcess(pid)
                                }
                            }
                        }
                    }
                }.destroy()
        }
        isRunning.value = bound == true
        isBinding.value = false
    }

    @Suppress("Deprecation")
    private fun killProcess(pid: String) {
        //outputStream.write() seems not working, have to create a new process
        Shizuku.newProcess(arrayOf("kill", pid), null, "/").apply {
            waitFor()
            destroy()
        }
    }

    private val userServiceStandaloneProcessArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, AutomatorConnection::class.java.name))
            .processNameSuffix(serviceNameSuffix).debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    }

    private val deathRecipient: IBinder.DeathRecipient by lazy {
        IBinder.DeathRecipient {
            Log.i(tag, "The remote service is dead!")
            isRunning.postValue(false)
            Shizuku.unbindUserService(userServiceStandaloneProcessArgs, userServiceConnection, false)
        }
    }
    private var automatorService: IAutomatorConnection? = null
    private val userServiceConnection by lazy {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = synchronized(lock) {
                if (binder != null && binder.pingBinder()) {
                    automatorService = IAutomatorConnection.Stub.asInterface(binder)
                    automatorService?.run {
                        try {
                            Log.i(tag, sayHello())
                            binder.linkToDeath(deathRecipient, 0)
                            pushRuleConfiguration(this)
                            if (!isMonitoring) {
                                setBasicEnvInfo(AutomatorApp.getBasicEnvInfo())
                                initFileDescriptors()
                                setFileDescriptors(pfds)
                                startMonitoring()
                                Log.i(tag, "Monitoring started successfully!")
                            }
                            skippingTimes.value = skippingCount
                            latestAction.value = records.maxByOrNull { it.latestTimestamp }
                            serviceStartTimestamp = startTimestamp
                            isRunning.value = true
                        } catch (t: Throwable) {
                            t.printStackTrace()
                            error.value = t
                        }
                    }
                }
                isBinding.value = false
                notified = true
                lock.notify()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                isRunning.value = false
            }
        }
    }

    private val lock = Object()
    private var notified = false

    /**
     * Bind the remote service and wait for the binding result.
     */
    private fun bindServiceLocked(): Boolean = synchronized(lock) {
        try {
            Shizuku.bindUserService(userServiceStandaloneProcessArgs, userServiceConnection)
            lock.wait(BINDING_SERVICE_TIMEOUT_MILLS)
        } catch (t: Throwable) {
            t.printStackTrace()
            return false
        }
        return isRunning.value == true
    }

    /**
     * Bind the remote service and notify the [error] when something wrong happens.
     */
    @Suppress("BlockingMethodInNonBlockingContext")
    fun bindService() = viewModelScope.launch {
        try {
            isBinding.value = true
            withContext(Dispatchers.Default) {
                synchronized(lock) {
                    notified = false
                    Shizuku.bindUserService(userServiceStandaloneProcessArgs, userServiceConnection)
                    lock.wait(BINDING_SERVICE_TIMEOUT_MILLS)
                    if (!notified) throw TimeoutException("Timeout while connecting to the remote service")
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
            error.value = t
            isBinding.value = false
        }
    }

    fun toggleService() {
        if (isRunning.value == true) {
            Shizuku.unbindUserService(userServiceStandaloneProcessArgs, userServiceConnection, true)
        } else {
            bindService()
        }
    }

    //https://youtrack.jetbrains.com/issue/KTIJ-838
    @Suppress("BlockingMethodInNonBlockingContext")
    fun readSkippingCount() = viewModelScope.launch {
        var times = 0
        withContext(Dispatchers.IO) {
            val file = app.getFileStreamPath(COUNT_FILE_NAME)
            if (file.exists()) {
                FileInputStream(file).bufferedReader().useLines {
                    times = it.firstOrNull()?.toIntOrNull() ?: 0
                }
            }
        }
        skippingTimes.value = times
    }

    private fun initFileDescriptors() {
        val mode = ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        pfds[0] = ParcelFileDescriptor.open(app.getFileStreamPath(COUNT_FILE_NAME), mode)
        pfds[1] = ParcelFileDescriptor.open(app.getFileStreamPath(LOG_FILE_NAME), mode)
        pfds[2] = ParcelFileDescriptor.open(app.getFileStreamPath(RECORD_FILE_NAME), mode)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        isAvailable.value = true
        isGranted.value = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        if (isGranted.value == true) {
            bindRunningServiceOrKill()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        isAvailable.value = false
    }

    fun isServiceAlive(): Boolean = automatorService?.asBinder()?.pingBinder() ?: false

    private inline fun whenServiceIsAlive(block: IAutomatorConnection.() -> Unit) {
        if (isServiceAlive()) {
            try {
                block(automatorService!!)
            } catch (t: Throwable) {
                error.postValue(t)
            }
        }
    }

    fun dumpLog() = whenServiceIsAlive {
        persistLog()
    }

    fun launchStandaloneCheck(listener: OnCheckResultListener) = whenServiceIsAlive {
        standaloneCheck(listener)
    }

    fun updateSkippingCount() = whenServiceIsAlive {
        skippingTimes.value = skippingCount
    }

    private fun pushRuleConfiguration(service: IAutomatorConnection) {
        val rules = AppRulePreferences.snapshot()
        service.configureRules(
            rules.enabled,
            rules.strictMode,
            ArrayList(rules.whitelist),
            ArrayList(rules.blacklist),
            rules.singleClickLimitEnabled
        )
        service.configureRecognitionRules(RecognitionRulePreferences.configurationJson())
    }

    fun syncRuleConfiguration() = whenServiceIsAlive {
        pushRuleConfiguration(this)
    }

    fun startRuleLearning(packageName: String): Boolean {
        var started = false
        whenServiceIsAlive {
            this.startRuleLearning(packageName)
            started = true
        }
        return started
    }

    fun getRuleLearningCandidates(): List<LearningCandidate> {
        var candidates = emptyList<LearningCandidate>()
        whenServiceIsAlive {
            candidates = RecognitionRuleCodec.decodeCandidates(ruleLearningCandidates)
        }
        return candidates
    }

    fun stopRuleLearning() = whenServiceIsAlive {
        this.stopRuleLearning()
    }

    fun updateLatestAction() = viewModelScope.launch {
        val latest = withContext(Dispatchers.IO) {
            val remoteRecords = runCatching {
                if (isServiceAlive()) automatorService?.records else null
            }.getOrNull()
            val availableRecords = remoteRecords ?: runCatching {
                val file = app.getFileStreamPath(RECORD_FILE_NAME)
                if (file.exists()) {
                    app.openFileInput(RECORD_FILE_NAME).use { Records(it.fd).parse().asList() }
                } else {
                    emptyList()
                }
            }.getOrDefault(emptyList())
            availableRecords.maxByOrNull { it.latestTimestamp }
        }
        latestAction.value = latest
    }

    private data class RecordMutationResult(
        val successful: Boolean,
        val count: Int,
        val latest: Record?
    )

    suspend fun deleteRecord(packageName: String): Boolean {
        val result = withContext(Dispatchers.IO) {
            if (isServiceAlive()) {
                runCatching {
                    val service = requireNotNull(automatorService)
                    if (!service.deleteRecord(packageName)) {
                        RecordMutationResult(false, service.skippingCount, null)
                    } else {
                        val currentRecords = service.records
                        RecordMutationResult(
                            true,
                            service.skippingCount,
                            currentRecords.maxByOrNull { it.latestTimestamp }
                        )
                    }
                }.onFailure {
                    error.postValue(it)
                }.getOrElse { RecordMutationResult(false, skippingTimes.value ?: 0, null) }
            } else {
                mutateLocalRecords { records ->
                    records.removeAll { it.pkgName == packageName }
                }
            }
        }
        if (result.successful) {
            skippingTimes.value = result.count
            latestAction.value = result.latest
        }
        return result.successful
    }

    suspend fun clearRecords(): Boolean {
        val result = withContext(Dispatchers.IO) {
            if (isServiceAlive()) {
                runCatching {
                    requireNotNull(automatorService).clearRecords()
                    RecordMutationResult(true, 0, null)
                }.onFailure {
                    error.postValue(it)
                }.getOrElse { RecordMutationResult(false, skippingTimes.value ?: 0, null) }
            } else {
                mutateLocalRecords { records ->
                    val changed = records.isNotEmpty()
                    records.clear()
                    changed
                }
            }
        }
        if (result.successful) {
            skippingTimes.value = 0
            latestAction.value = null
        }
        return result.successful
    }

    private fun mutateLocalRecords(
        mutation: (MutableList<Record>) -> Boolean
    ): RecordMutationResult {
        return runCatching {
            val records = readLocalRecords()
            if (!mutation(records)) {
                return@runCatching RecordMutationResult(
                    false,
                    records.sumOf { it.count },
                    records.maxByOrNull { it.latestTimestamp }
                )
            }
            persistLocalRecordState(records)
            RecordMutationResult(
                true,
                records.sumOf { it.count },
                records.maxByOrNull { it.latestTimestamp }
            )
        }.onFailure {
            error.postValue(it)
        }.getOrElse { RecordMutationResult(false, skippingTimes.value ?: 0, null) }
    }

    private fun readLocalRecords(): MutableList<Record> {
        val file = app.getFileStreamPath(RECORD_FILE_NAME)
        if (!file.exists()) return mutableListOf()
        return app.openFileInput(RECORD_FILE_NAME).use {
            Records(it.fd).parse().asList()
        }
    }

    private fun persistLocalRecordState(records: List<Record>) {
        app.openFileOutput(RECORD_FILE_NAME, 0).bufferedWriter().use { writer ->
            records.forEach { record ->
                writer.write(record.toString())
                writer.newLine()
            }
        }
        app.openFileOutput(COUNT_FILE_NAME, 0).bufferedWriter().use { writer ->
            writer.write(records.sumOf { it.count }.toString())
        }
    }


    fun updateGranted() {
        if (Shizuku.pingBinder()) {
            isGranted.value = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }
    }

    fun getRecordListFromRemote(): MutableList<Record>? {
        whenServiceIsAlive {
            return records
        }
        return null
    }
}
