package top.xjunz.automator.backup

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.databinding.ActivityDataBackupBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataBackupActivity : AppCompatActivity() {
    companion object {
        private const val REQUEST_EXPORT = 501
        private const val REQUEST_IMPORT = 502
    }

    private lateinit var binding: ActivityDataBackupBinding
    private val viewModel by lazy { AutomatorViewModel.get() }
    private val backupManager by lazy { DataBackupManager(applicationContext, viewModel) }
    private var includeStatisticsOnExport = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDataBackupBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finishAfterTransition() }
        binding.btnExport.setOnClickListener { showExportOptions() }
        binding.btnImport.setOnClickListener { showImportReminder() }
    }

    private fun showExportOptions() {
        includeStatisticsOnExport = false
        val options = arrayOf(
            getString(R.string.backup_without_statistics),
            getString(R.string.backup_with_statistics)
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_data)
            .setSingleChoiceItems(options, 0) { _, which ->
                includeStatisticsOnExport = which == 1
            }
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.export_data) { _, _ -> launchExportPicker() }
            .show()
    }

    private fun launchExportPicker() {
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(DataBackupManager.MIME_TYPE)
            .putExtra(Intent.EXTRA_TITLE, "AutoSkip-backup-$timestamp${DataBackupManager.FILE_EXTENSION}")
        startActivityForResult(intent, REQUEST_EXPORT)
    }

    private fun showImportReminder() {
        val stopped = backupManager.isServiceStopped()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stop_service_before_import_title)
            .setMessage(
                if (stopped) R.string.stop_service_before_import_message
                else R.string.stop_service_before_import_running_message
            )
            .setNegativeButton(R.string.cancel, null)
            .apply {
                if (stopped) {
                    setPositiveButton(R.string.select_backup_file) { _, _ -> launchImportPicker() }
                } else {
                    setPositiveButton(android.R.string.ok, null)
                }
            }
            .show()
    }

    private fun launchImportPicker() {
        if (!backupManager.isServiceStopped()) {
            toast(getString(R.string.stop_service_before_import_short))
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(DataBackupManager.MIME_TYPE)
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    @Deprecated("Deprecated in Android API; retained for the project's current Activity dependencies")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_EXPORT -> exportTo(uri)
            REQUEST_IMPORT -> readImport(uri)
        }
    }

    private fun exportTo(uri: Uri) = lifecycleScope.launch {
        setBusy(true)
        runCatching { backupManager.export(uri, includeStatisticsOnExport) }
            .onSuccess { summary ->
                toast(
                    getString(
                        R.string.format_export_success,
                        summary.globalRuleCount + summary.applicationRuleCount,
                        summary.statisticCount ?: 0
                    )
                )
            }
            .onFailure { showError(R.string.export_failed, it) }
        setBusy(false)
    }

    private fun readImport(uri: Uri) = lifecycleScope.launch {
        setBusy(true)
        runCatching { backupManager.read(uri) }
            .onSuccess(::showImportPreview)
            .onFailure { showError(R.string.import_read_failed, it) }
        setBusy(false)
    }

    private fun showImportPreview(backup: AutoSkipBackup) {
        val summary = AutoSkipBackupCodec.summary(backup)
        val statisticsSummary = summary.statisticCount?.let {
            getString(R.string.format_statistics_included, it)
        } ?: getString(R.string.statistics_not_included)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.confirm_overwrite_restore)
            .setMessage(
                getString(
                    R.string.format_import_preview,
                    backup.metadata.appVersionName,
                    summary.whitelistCount,
                    summary.blacklistCount,
                    summary.globalRuleCount,
                    summary.applicationCount,
                    summary.applicationRuleCount,
                    statisticsSummary
                )
            )
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.overwrite_restore) { _, _ -> restore(backup) }
            .show()
    }

    private fun restore(backup: AutoSkipBackup) = lifecycleScope.launch {
        if (!backupManager.isServiceStopped()) {
            showError(
                R.string.import_failed,
                IllegalStateException(getString(R.string.stop_service_before_import_short))
            )
            return@launch
        }
        setBusy(true)
        runCatching { backupManager.restore(backup) }
            .onSuccess {
                toast(getString(R.string.import_success_restart_manually))
            }
            .onFailure { showError(R.string.import_failed, it) }
        setBusy(false)
    }

    private fun setBusy(busy: Boolean) {
        binding.progress.visibility = if (busy) View.VISIBLE else View.GONE
        binding.btnExport.isEnabled = !busy
        binding.btnImport.isEnabled = !busy
    }

    private fun showError(titleRes: Int, throwable: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setMessage(throwable.message ?: getString(R.string.unknown_problem))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}
