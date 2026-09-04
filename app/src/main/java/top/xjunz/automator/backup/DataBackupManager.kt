package top.xjunz.automator.backup

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import top.xjunz.automator.BuildConfig
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.model.Record
import top.xjunz.automator.rules.AppRulePreferences
import top.xjunz.automator.rules.RecognitionRulePreferences
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

class DataBackupManager(
    private val context: Context,
    private val viewModel: AutomatorViewModel
) {
    companion object {
        const val MIME_TYPE = "application/json"
        const val FILE_EXTENSION = ".autoskip.json"
        private const val MAX_BACKUP_BYTES = 4 * 1024 * 1024
    }

    suspend fun export(uri: Uri, includeStatistics: Boolean): BackupSummary {
        val statistics = if (includeStatistics) {
            viewModel.getRecordSnapshot()
                .asSequence()
                .filter { it.pkgName.isNotBlank() && it.count > 0 }
                .map {
                    BackupStatistic(
                        packageName = it.pkgName,
                        count = it.count,
                        firstTimestamp = it.firstTimestamp,
                        latestTimestamp = it.latestTimestamp
                    )
                }
                .toList()
        } else {
            null
        }
        val backup = AutoSkipBackup(
            metadata = BackupMetadata(
                exportedAt = System.currentTimeMillis(),
                appVersionName = BuildConfig.VERSION_NAME,
                appVersionCode = BuildConfig.VERSION_CODE
            ),
            applicationScope = AppRulePreferences.snapshot(),
            recognitionRules = RecognitionRulePreferences.snapshot(),
            statistics = statistics
        )
        withContext(Dispatchers.IO) {
            val json = AutoSkipBackupCodec.encode(backup)
            AutoSkipBackupCodec.decode(json)
            val bytes = json.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_BACKUP_BYTES) throw BackupFormatException("备份文件超过 4 MB")
            val stream = runCatching {
                context.contentResolver.openOutputStream(uri, "rwt")
            }.getOrNull() ?: context.contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("无法打开导出文件")
            stream.use { it.write(bytes) }
        }
        return AutoSkipBackupCodec.summary(backup)
    }

    suspend fun read(uri: Uri): AutoSkipBackup = withContext(Dispatchers.IO) {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法打开备份文件")
        val output = ByteArrayOutputStream()
        stream.use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BACKUP_BYTES) throw BackupFormatException("备份文件超过 4 MB")
                output.write(buffer, 0, read)
            }
        }
        val json = output.toString(StandardCharsets.UTF_8.name())
        AutoSkipBackupCodec.decode(json)
    }

    /**
     * Overwrites configuration, and overwrites statistics only when that optional section exists.
     * The activity checks service state before calling; this function checks again at write time.
     */
    suspend fun restore(backup: AutoSkipBackup): Unit = withContext(NonCancellable) {
        if (!isServiceStopped()) throw IllegalStateException("请先停止自动跳过服务")

        val previousScope = AppRulePreferences.snapshot()
        val previousRules = RecognitionRulePreferences.snapshot()
        val previousRecords = if (backup.statistics != null) viewModel.getRecordSnapshot() else null

        var scopeChanged = false
        var rulesChanged = false
        try {
            if (!isServiceStopped()) throw IllegalStateException("请先停止自动跳过服务")
            if (!AppRulePreferences.replace(backup.applicationScope)) {
                throw IOException("写入应用范围设置失败")
            }
            scopeChanged = true
            if (!RecognitionRulePreferences.replace(backup.recognitionRules)) {
                throw IOException("写入识别规则失败")
            }
            rulesChanged = true
            backup.statistics?.let { statistics ->
                val records = statistics.map {
                    Record(
                        pkgName = it.packageName,
                        count = it.count,
                        firstTimestamp = it.firstTimestamp,
                        latestTimestamp = it.latestTimestamp
                    )
                }
                if (!viewModel.replaceLocalRecords(records)) {
                    throw IOException("写入统计记录失败，请确认服务已经停止")
                }
            }
        } catch (failure: Throwable) {
            if (rulesChanged) RecognitionRulePreferences.replace(previousRules)
            if (scopeChanged) AppRulePreferences.replace(previousScope)
            if (previousRecords != null && isServiceStopped()) {
                viewModel.replaceLocalRecords(previousRecords)
            }
            throw failure
        }
        Unit
    }

    fun isServiceStopped(): Boolean {
        return viewModel.isBinding.value != true &&
            viewModel.isRunning.value != true &&
            !viewModel.isServiceAlive()
    }
}
