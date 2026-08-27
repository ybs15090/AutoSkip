package top.xjunz.automator.main

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.view.Gravity
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.Observer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider.MANAGER_APPLICATION_ID
import rikka.sui.Sui
import top.xjunz.automator.BuildConfig
import top.xjunz.automator.FEEDBACK_GROUP_URL
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.app.LOG_FILE_NAME
import top.xjunz.automator.autostart.enableShizukuAutoStart
import top.xjunz.automator.autostart.isAutoStartEnabled
import top.xjunz.automator.autostart.isShizukuAutoStartEnabled
import top.xjunz.automator.autostart.setAutoStartComponentEnable
import top.xjunz.automator.databinding.ActivityMainBinding
import top.xjunz.automator.databinding.ItemSetupStepBinding
import top.xjunz.automator.model.Record
import top.xjunz.automator.rules.AppRulePreferences
import top.xjunz.automator.rules.AppRulesActivity
import top.xjunz.automator.stats.StatsActivity
import top.xjunz.automator.test.TestActivity
import top.xjunz.automator.util.formatTime
import top.xjunz.automator.util.sendMailTo
import top.xjunz.automator.util.viewUrl

/**
 * @author xjunz 2021/6/20 21:05
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel by lazy { AutomatorViewModel.get() }
    private val setupPreferences by lazy {
        getSharedPreferences(SETUP_PREFERENCES, MODE_PRIVATE)
    }

    companion object {
        const val SHIZUKU_PERMISSION_REQUEST_CODE = 13
        private const val SETUP_PREFERENCES = "first_use"
        private const val KEY_SETUP_COMPLETED = "setup_completed"
    }

    private data class SetupStep(
        val number: String,
        val titleRes: Int,
        val completed: Boolean,
        val statusRes: Int,
        val actionRes: Int?,
        val actionEnabled: Boolean = true,
        val action: (() -> Unit)? = null
    )

    private var latestActionPackage: String? = null
    private var latestActionLabel: CharSequence? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_main)
        initViews()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.data?.host == "about") {
            AboutFragment().show(supportFragmentManager, "about")
        }
    }

    private val statusObserver by lazy {
        Observer<Boolean> { renderHomeState() }
    }

    private val popupMenu by lazy {
        PopupMenu(this, binding.ibMenu, Gravity.NO_GRAVITY).apply {
            menuInflater.inflate(R.menu.main, menu)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    R.id.item_test -> testAvailability()
                    R.id.item_dump_log -> shareLog()
                    R.id.item_auto_start -> {
                        setAutoStartEnabled(!it.isChecked)
                        it.isChecked = isAutoStartEnabled()
                    }
                    R.id.item_feedback_email -> {
                        viewModel.dumpLog()
                        if (getFileStreamPath(LOG_FILE_NAME).exists()) {
                            val uri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "top.xjunz.automator.provider.file",
                                logFile
                            )
                            sendMailTo(this@MainActivity, uri)
                        } else {
                            sendMailTo(this@MainActivity, null)
                        }
                    }
                    R.id.item_feedback_group -> viewUrl(this@MainActivity, FEEDBACK_GROUP_URL)
                    R.id.item_feedback_issues -> viewUrl(
                        this@MainActivity,
                        "https://github.com/xjunz/AutoSkip/issues"
                    )
                    R.id.item_working_principle -> showWorkingPrinciple()
                    R.id.item_about -> AboutFragment().show(supportFragmentManager, "about")
                }
                true
            }
        }
    }

    private val logFile by lazy { getFileStreamPath(LOG_FILE_NAME) }

    @SuppressLint("ClickableViewAccessibility")
    private fun initViews() {
        TopBarController(binding.topBar, binding.scrollView).init()
        binding.root.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.M) {
            binding.rlMRestriction.isVisible = true
        }
        binding.ibMenu.setOnTouchListener(popupMenu.dragToOpenListener)
        viewModel.apply {
            isInstalled.observe(this@MainActivity, statusObserver)
            isGranted.observe(this@MainActivity, statusObserver)
            isRunning.observe(this@MainActivity, statusObserver)
            isAvailable.observe(this@MainActivity, statusObserver)
            isBinding.observe(this@MainActivity, statusObserver)
            skippingTimes.observe(this@MainActivity) { renderSkippingCount(it ?: 0) }
            error.observe(this@MainActivity) {
                if (it != null) {
                    MaterialAlertDialogBuilder(this@MainActivity)
                        .setTitle(R.string.error_occurred)
                        .setPositiveButton(android.R.string.ok, null)
                        .setMessage(it.stackTraceToString())
                        .show()
                    error.value = null
                }
            }
            isAutoStarted.observe(this@MainActivity) {
                if (it == true) {
                    toast(
                        getString(
                            if (isRunning.value == true) {
                                R.string.auto_started_for_u
                            } else {
                                R.string.auto_starting_for_u
                            }
                        )
                    )
                    isAutoStarted.value = null
                }
            }
            latestAction.observe(this@MainActivity, ::renderLatestAction)
            if (!initialized) init()
        }
        binding.ibMenu.setOnClickListener { showMenu() }
        binding.btnFalsePositive.setOnClickListener { blacklistLatestAction() }
        binding.btnRun.setOnClickListener { handlePrimaryServiceAction() }
        binding.rlStats.setOnClickListener { showRecords() }
        binding.rlAppRules.setOnClickListener { showAppRules() }
        renderHomeState()
    }

    override fun onResume() {
        super.onResume()
        viewModel.syncShizukuInstallationState()
        viewModel.updateGranted()
        viewModel.updateSkippingCount()
        viewModel.syncRuleConfiguration()
        viewModel.updateLatestAction()
        renderHomeState()
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(updateDurationTask)
    }

    private fun renderHomeState() {
        if (!::binding.isInitialized) return
        val installed = viewModel.isInstalled.value == true || Sui.isSui()
        val available = viewModel.isAvailable.value == true
        val granted = viewModel.isGranted.value == true
        val running = viewModel.isRunning.value == true
        val bindingService = viewModel.isBinding.value == true
        val autoStart = isAutoStartEnabled()
        val allCompleted = installed && available && granted && running && autoStart
        if (allCompleted && !isSetupCompleted()) {
            setupPreferences.edit().putBoolean(KEY_SETUP_COMPLETED, true).apply()
        }

        val showSetup = !isSetupCompleted()
        binding.layoutSetupChecklist.isVisible = showSetup
        binding.layoutDailyHome.isVisible = !showSetup
        if (showSetup) {
            renderSetupSteps(installed, available, granted, running, bindingService, autoStart)
        }
        renderDailyServiceState(installed, available, granted, running, bindingService, autoStart)
    }

    private fun renderSetupSteps(
        installed: Boolean,
        available: Boolean,
        granted: Boolean,
        running: Boolean,
        bindingService: Boolean,
        autoStart: Boolean
    ) {
        val steps = listOf(
            SetupStep(
                "①",
                R.string.setup_shizuku_installed,
                installed,
                if (installed) R.string.setup_completed else R.string.setup_not_installed,
                if (installed) null else R.string.action_install,
                action = ::performShizukuAction
            ),
            SetupStep(
                "②",
                R.string.setup_shizuku_running,
                available,
                when {
                    available -> R.string.setup_completed
                    installed -> R.string.setup_not_running
                    else -> R.string.setup_wait_previous
                },
                if (available) null else R.string.action_start,
                installed,
                ::launchShizukuManager
            ),
            SetupStep(
                "③",
                R.string.setup_permission_granted,
                granted,
                when {
                    granted -> R.string.setup_completed
                    available -> R.string.setup_not_granted
                    else -> R.string.setup_wait_previous
                },
                if (granted) null else R.string.action_grant_permission,
                available,
                ::requestPermission
            ),
            SetupStep(
                "④",
                R.string.setup_service_running,
                running,
                when {
                    running -> R.string.setup_completed
                    bindingService -> R.string.service_connecting
                    granted -> R.string.setup_service_not_running
                    else -> R.string.setup_wait_previous
                },
                if (running) null else R.string.action_start_auto_skip,
                granted && available && !bindingService,
                { viewModel.toggleService() }
            ),
            SetupStep(
                "⑤",
                R.string.setup_boot_restore,
                autoStart,
                when {
                    autoStart -> R.string.setup_completed
                    running -> R.string.setup_auto_start_disabled
                    else -> R.string.setup_wait_previous
                },
                if (autoStart) null else R.string.action_enable,
                running,
                { setAutoStartEnabled(true) }
            )
        )
        binding.llSetupSteps.removeAllViews()
        steps.forEach { step ->
            val stepBinding = ItemSetupStepBinding.inflate(layoutInflater, binding.llSetupSteps, true)
            stepBinding.tvStepNumber.text = if (step.completed) "✓" else step.number
            stepBinding.tvStepTitle.setText(step.titleRes)
            stepBinding.tvStepStatus.setText(step.statusRes)
            stepBinding.btnStepAction.isVisible = step.actionRes != null
            step.actionRes?.let(stepBinding.btnStepAction::setText)
            stepBinding.btnStepAction.isEnabled = step.actionEnabled
            stepBinding.btnStepAction.setOnClickListener { step.action?.invoke() }
        }
    }

    private fun renderDailyServiceState(
        installed: Boolean,
        available: Boolean,
        granted: Boolean,
        running: Boolean,
        bindingService: Boolean,
        autoStart: Boolean
    ) {
        mainHandler.removeCallbacks(updateDurationTask)
        binding.btnRun.isEnabled = !bindingService
        when {
            running -> {
                binding.tvServiceStatus.setText(R.string.auto_skip_is_running)
                binding.btnRun.setText(R.string.action_stop_auto_skip)
                binding.btnRun.isActivated = true
                updateRunningDuration(autoStart)
                if (!binding.layoutSetupChecklist.isVisible) {
                    mainHandler.post(updateDurationTask)
                }
            }
            bindingService -> {
                binding.tvServiceStatus.setText(R.string.auto_skip_is_starting)
                binding.tvCaptionStatus.setText(R.string.please_wait)
                binding.btnRun.setText(R.string.action_starting)
                binding.btnRun.isActivated = false
            }
            !installed || !available || !granted -> {
                binding.tvServiceStatus.setText(R.string.runtime_needs_repair)
                binding.tvCaptionStatus.setText(
                    when {
                        !installed -> R.string.setup_not_installed
                        !available -> R.string.setup_not_running
                        else -> R.string.setup_not_granted
                    }
                )
                binding.btnRun.setText(R.string.action_repair)
                binding.btnRun.isActivated = false
            }
            else -> {
                binding.tvServiceStatus.setText(R.string.auto_skip_is_stopped)
                binding.tvCaptionStatus.setText(R.string.hint_start_auto_skip)
                binding.btnRun.setText(R.string.action_start_auto_skip)
                binding.btnRun.isActivated = false
            }
        }
    }

    private fun renderSkippingCount(count: Int) {
        if (!::binding.isInitialized) return
        binding.tvCaptionStats.text = getString(R.string.format_total_count, count)
        binding.ivStatsEnter.isVisible = count > 0
    }

    private fun renderLatestAction(record: Record?) {
        if (record == null) {
            binding.rlLatestAction.visibility = View.GONE
            latestActionPackage = null
            latestActionLabel = null
            return
        }
        latestActionPackage = record.pkgName
        val appInfo = runCatching { packageManager.getApplicationInfo(record.pkgName, 0) }.getOrNull()
        latestActionLabel = appInfo?.let {
            runCatching { it.loadLabel(packageManager) }.getOrNull()
        } ?: record.pkgName
        binding.apply {
            rlLatestAction.visibility = View.VISIBLE
            tvLatestActionApp.text = latestActionLabel
            tvLatestActionDetail.text = getString(
                R.string.format_recent_action_detail,
                formatTime(record.latestTimestamp),
                record.text ?: getString(R.string.skip)
            )
            ivLatestAction.setImageDrawable(
                appInfo?.let { runCatching { it.loadIcon(packageManager) }.getOrNull() }
                    ?: packageManager.defaultActivityIcon
            )
        }
        updateLatestActionBlacklistState()
    }

    private fun updateLatestActionBlacklistState() {
        val packageName = latestActionPackage ?: return
        val blacklisted = AppRulePreferences.getPackageRuleState(packageName).blacklisted
        binding.btnFalsePositive.isEnabled = !blacklisted
        binding.btnFalsePositive.setText(
            if (blacklisted) R.string.already_blacklisted else R.string.false_positive_blacklist
        )
    }

    private fun blacklistLatestAction() {
        val packageName = latestActionPackage ?: return
        val previousState = AppRulePreferences.getPackageRuleState(packageName)
        AppRulePreferences.blacklistPackage(packageName)
        viewModel.syncRuleConfiguration()
        updateLatestActionBlacklistState()
        Snackbar.make(
            binding.root,
            getString(R.string.format_blacklisted_app, latestActionLabel ?: packageName),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.undo) {
            AppRulePreferences.restorePackageRule(packageName, previousState)
            viewModel.syncRuleConfiguration()
            updateLatestActionBlacklistState()
        }.show()
    }

    private val mainHandler by lazy { Handler(mainLooper) }

    private val updateDurationTask = object : Runnable {
        override fun run() {
            if (viewModel.isRunning.value != true || binding.layoutSetupChecklist.isVisible) return
            updateRunningDuration(isAutoStartEnabled())
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun updateRunningDuration(autoStart: Boolean) {
        if (viewModel.serviceStartTimestamp <= 0) return
        val duration = System.currentTimeMillis() - viewModel.serviceStartTimestamp
        binding.tvCaptionStatus.text = getString(
            if (autoStart) R.string.format_running_duration else R.string.format_running_duration_no_auto_restore,
            duration / 3_600_000,
            duration / 60_000 % 60,
            duration / 1000 % 60
        )
    }

    private fun handlePrimaryServiceAction() {
        val environmentReady = (viewModel.isInstalled.value == true || Sui.isSui()) &&
                viewModel.isAvailable.value == true && viewModel.isGranted.value == true
        when {
            viewModel.isRunning.value == true -> viewModel.toggleService()
            environmentReady -> viewModel.toggleService()
            else -> {
                setupPreferences.edit().putBoolean(KEY_SETUP_COMPLETED, false).apply()
                renderHomeState()
                binding.scrollView.smoothScrollTo(0, 0)
            }
        }
    }

    private fun showMenu() {
        popupMenu.menu.findItem(R.id.item_auto_start).isChecked = isAutoStartEnabled()
        popupMenu.menu.findItem(R.id.item_test).isEnabled = viewModel.isRunning.value == true
        popupMenu.show()
    }

    private fun showWorkingPrinciple() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.working_principle)
            .setMessage(R.string.working_principle_content)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setAutoStartEnabled(enabled: Boolean) {
        if (!enabled) {
            setAutoStartComponentEnable(false)
            renderHomeState()
            return
        }
        if (Sui.isSui() || isShizukuAutoStartEnabled() || enableShizukuAutoStart()) {
            setAutoStartComponentEnable(true)
        } else {
            toast(getString(R.string.pls_turn_on_shizuku_auto_start))
            launchShizukuManager()
        }
        renderHomeState()
    }

    private val downloadUrl by lazy {
        when (resources.configuration.locale.script) {
            "Hans" -> "https://shizuku.rikka.app/zh-hans/download/"
            "Hant" -> "https://shizuku.rikka.app/zh-hant/download/"
            else -> "https://shizuku.rikka.app/download/"
        }
    }

    private fun performShizukuAction() {
        if (viewModel.isInstalled.value == true) {
            launchShizukuManager()
        } else {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)), null))
        }
    }

    private fun launchShizukuManager() {
        packageManager.getLaunchIntentForPackage(MANAGER_APPLICATION_ID)?.let(::startActivity)
    }

    private fun requestPermission() {
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            toast(getString(R.string.pls_grant_manually))
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_DENIED) {
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                viewModel.isGranted.value = requestCode == SHIZUKU_PERMISSION_REQUEST_CODE &&
                        grantResult == PackageManager.PERMISSION_GRANTED
            }
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST_CODE)
        } else {
            viewModel.isGranted.value = true
        }
    }

    private fun testAvailability() {
        if (viewModel.isServiceAlive()) {
            startActivity(Intent(this, TestActivity::class.java))
        } else {
            toast(getString(R.string.pls_start_service))
        }
    }

    private fun showRecords() {
        if ((viewModel.skippingTimes.value ?: 0) > 0) {
            startActivity(Intent(this, StatsActivity::class.java))
        } else {
            toast(getString(R.string.no_records))
        }
    }

    private fun showAppRules() {
        startActivity(Intent(this, AppRulesActivity::class.java))
    }

    private fun shareLog() {
        viewModel.dumpLog()
        if (!getFileStreamPath(LOG_FILE_NAME).exists()) {
            toast(getString(R.string.no_log))
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "top.xjunz.automator.provider.file",
            logFile
        )
        if (BuildConfig.DEBUG) {
            val intent = Intent(Intent.ACTION_VIEW, uri)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, null))
        } else {
            val intent = Intent(Intent.ACTION_SEND)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .setType("text/plain")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(intent, null))
        }
    }

    private fun isSetupCompleted() = setupPreferences.getBoolean(KEY_SETUP_COMPLETED, false)

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
