package top.xjunz.automator.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import top.xjunz.automator.recognition.RecognitionConfiguration
import top.xjunz.automator.recognition.RecognitionRule
import top.xjunz.automator.recognition.RuleClickMode
import top.xjunz.automator.recognition.RuleFeature
import top.xjunz.automator.recognition.RuleMatchMode
import top.xjunz.automator.recognition.RuleRegion
import top.xjunz.automator.rules.AppRuleSnapshot

data class BackupMetadata(
    val exportedAt: Long,
    val appVersionName: String,
    val appVersionCode: Int
)

data class BackupStatistic(
    val packageName: String,
    val count: Int,
    val firstTimestamp: Long,
    val latestTimestamp: Long
)

data class AutoSkipBackup(
    val metadata: BackupMetadata,
    val applicationScope: AppRuleSnapshot,
    val recognitionRules: RecognitionConfiguration,
    /** Null means that the optional statistics section was not exported. */
    val statistics: List<BackupStatistic>?
)

data class BackupSummary(
    val whitelistCount: Int,
    val blacklistCount: Int,
    val globalRuleCount: Int,
    val applicationCount: Int,
    val applicationRuleCount: Int,
    val statisticCount: Int?
)

class BackupFormatException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

object AutoSkipBackupCodec {
    private const val FORMAT = "autoskip-backup"
    private const val SCHEMA_VERSION = 1
    private const val MAX_PACKAGE_NAME_LENGTH = 255
    private const val MAX_RULE_ID_LENGTH = 200
    private const val MAX_PATTERN_LENGTH = 2_000
    private const val MAX_RULES = 10_000
    private val PACKAGE_NAME_PATTERN = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")

    fun encode(backup: AutoSkipBackup): String {
        val metadata = JSONObject()
            .put("format", FORMAT)
            .put("schemaVersion", SCHEMA_VERSION)
            .put("exportedAt", backup.metadata.exportedAt)
            .put("appVersionName", backup.metadata.appVersionName)
            .put("appVersionCode", backup.metadata.appVersionCode)
        val scope = JSONObject()
            .put("enabled", backup.applicationScope.enabled)
            .put("strictMode", backup.applicationScope.strictMode)
            .put("singleClickLimitEnabled", backup.applicationScope.singleClickLimitEnabled)
            .put("whitelist", stringArray(backup.applicationScope.whitelist.sorted()))
            .put("blacklist", stringArray(backup.applicationScope.blacklist.sorted()))
        val root = JSONObject()
            .put("metadata", metadata)
            .put("applicationScope", scope)
            .put("recognitionRules", encodeRecognitionRules(backup.recognitionRules))
        backup.statistics?.let { statistics ->
            val records = JSONArray()
            statistics.sortedBy(BackupStatistic::packageName).forEach { record ->
                records.put(
                    JSONObject()
                        .put("packageName", record.packageName)
                        .put("count", record.count)
                        .put("firstTimestamp", record.firstTimestamp)
                        .put("latestTimestamp", record.latestTimestamp)
                )
            }
            root.put("statistics", JSONObject().put("records", records))
        }
        return root.toString(2)
    }

    fun decode(json: String): AutoSkipBackup {
        try {
            val root = JSONObject(json)
            val metadataObject = root.requiredObject("metadata")
            if (metadataObject.requiredString("format") != FORMAT) {
                throw BackupFormatException("不是 AutoSkip 备份文件")
            }
            val schemaVersion = metadataObject.requiredInt("schemaVersion")
            if (schemaVersion != SCHEMA_VERSION) {
                throw BackupFormatException("不支持的备份格式版本：$schemaVersion")
            }
            val metadata = BackupMetadata(
                exportedAt = metadataObject.requiredLong("exportedAt").requireNonNegative("导出时间"),
                appVersionName = metadataObject.requiredString("appVersionName"),
                appVersionCode = metadataObject.requiredInt("appVersionCode").requireNonNegative("应用版本号")
            )
            val scopeObject = root.requiredObject("applicationScope")
            val blacklist = scopeObject.requiredStringSet("blacklist")
            val scope = AppRuleSnapshot(
                enabled = scopeObject.requiredBoolean("enabled"),
                strictMode = scopeObject.requiredBoolean("strictMode"),
                whitelist = scopeObject.requiredStringSet("whitelist") - blacklist,
                blacklist = blacklist,
                singleClickLimitEnabled = scopeObject.requiredBoolean("singleClickLimitEnabled")
            )
            val rules = decodeRecognitionRules(root.requiredObject("recognitionRules"))
            val statistics = if (root.has("statistics") && !root.isNull("statistics")) {
                decodeStatistics(root.requiredObject("statistics"))
            } else {
                null
            }
            return AutoSkipBackup(metadata, scope, rules, statistics)
        } catch (e: BackupFormatException) {
            throw e
        } catch (e: JSONException) {
            throw BackupFormatException("备份文件结构不完整或字段类型错误", e)
        } catch (e: IllegalArgumentException) {
            throw BackupFormatException(e.message ?: "备份文件包含无效数据", e)
        }
    }

    fun summary(backup: AutoSkipBackup): BackupSummary {
        return BackupSummary(
            whitelistCount = backup.applicationScope.whitelist.size,
            blacklistCount = backup.applicationScope.blacklist.size,
            globalRuleCount = backup.recognitionRules.globalRules.size,
            applicationCount = backup.recognitionRules.applicationRules.size,
            applicationRuleCount = backup.recognitionRules.applicationRules.values.sumOf { it.size },
            statisticCount = backup.statistics?.size
        )
    }

    private fun encodeRecognitionRules(configuration: RecognitionConfiguration): JSONObject {
        val applications = JSONObject()
        configuration.applicationRules.toSortedMap().forEach { (packageName, rules) ->
            applications.put(packageName, encodeRules(rules))
        }
        return JSONObject()
            .put("globalRules", encodeRules(configuration.globalRules))
            .put("applicationRules", applications)
            .put(
                "disabledApplicationRulePackages",
                stringArray(configuration.disabledApplicationRulePackages.sorted())
            )
    }

    private fun encodeRules(rules: List<RecognitionRule>): JSONArray {
        val array = JSONArray()
        rules.forEach { rule ->
            array.put(
                JSONObject()
                    .put("id", rule.id)
                    .put("enabled", rule.enabled)
                    .put("feature", rule.feature.name)
                    .put("matchMode", rule.matchMode.name)
                    .put("pattern", rule.pattern)
                    .put("ignoreCase", rule.ignoreCase)
                    .put("region", rule.region.name)
                    .put("delayMillis", rule.delayMillis)
                    .put("clickMode", rule.clickMode.name)
            )
        }
        return array
    }

    private fun decodeRecognitionRules(root: JSONObject): RecognitionConfiguration {
        var totalRules = 0
        val globalRules = decodeRules(root.requiredArray("globalRules")).also { totalRules += it.size }
        val applicationsObject = root.requiredObject("applicationRules")
        val applicationRules = linkedMapOf<String, List<RecognitionRule>>()
        val keys = applicationsObject.keys().asSequence().toList().sorted()
        keys.forEach { packageName ->
            validatePackageName(packageName)
            val rules = decodeRules(applicationsObject.requiredArray(packageName))
            totalRules += rules.size
            if (totalRules > MAX_RULES) throw BackupFormatException("识别规则数量过多")
            if (rules.isNotEmpty()) applicationRules[packageName] = rules
        }
        val disabledPackages = root.requiredStringSet("disabledApplicationRulePackages")
            .filter(applicationRules::containsKey)
            .toSet()
        return RecognitionConfiguration(globalRules, applicationRules, disabledPackages)
    }

    private fun decodeRules(array: JSONArray): List<RecognitionRule> {
        if (array.length() > MAX_RULES) throw BackupFormatException("识别规则数量过多")
        val rules = ArrayList<RecognitionRule>(array.length())
        val ids = hashSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val id = item.requiredString("id").trim()
            val pattern = item.requiredString("pattern").trim()
            if (id.isEmpty() || id.length > MAX_RULE_ID_LENGTH || !ids.add(id)) {
                throw BackupFormatException("识别规则 ID 为空、过长或重复")
            }
            if (pattern.isEmpty() || pattern.length > MAX_PATTERN_LENGTH) {
                throw BackupFormatException("识别规则内容为空或过长")
            }
            val matchMode = item.requiredEnum<RuleMatchMode>("matchMode")
            val ignoreCase = item.requiredBoolean("ignoreCase")
            if (matchMode == RuleMatchMode.REGEX) {
                runCatching {
                    val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                    Regex(pattern, options)
                }.getOrElse { throw BackupFormatException("识别规则包含无效正则表达式") }
            }
            val delayMillis = item.requiredLong("delayMillis")
            if (delayMillis < 0 || delayMillis > RecognitionRule.MAX_DELAY_MILLIS) {
                throw BackupFormatException("识别规则延迟超出允许范围")
            }
            rules.add(
                RecognitionRule(
                    id = id,
                    enabled = item.requiredBoolean("enabled"),
                    feature = item.requiredEnum("feature"),
                    matchMode = matchMode,
                    pattern = pattern,
                    ignoreCase = ignoreCase,
                    region = item.requiredEnum("region"),
                    delayMillis = delayMillis,
                    clickMode = item.requiredEnum("clickMode")
                )
            )
        }
        return rules
    }

    private fun decodeStatistics(root: JSONObject): List<BackupStatistic> {
        val records = root.requiredArray("records")
        val packageNames = hashSetOf<String>()
        var totalCount = 0L
        return List(records.length()) { index ->
            val item = records.getJSONObject(index)
            val packageName = item.requiredString("packageName").also(::validatePackageName)
            if (!packageNames.add(packageName)) throw BackupFormatException("统计记录包含重复包名")
            val count = item.requiredInt("count")
            val firstTimestamp = item.requiredLong("firstTimestamp")
            val latestTimestamp = item.requiredLong("latestTimestamp")
            if (count <= 0 || firstTimestamp < 0 || latestTimestamp < 0 ||
                (firstTimestamp > 0 && latestTimestamp > 0 && firstTimestamp > latestTimestamp)
            ) {
                throw BackupFormatException("统计记录包含无效次数或时间")
            }
            totalCount += count
            if (totalCount > Int.MAX_VALUE) throw BackupFormatException("统计总次数超出允许范围")
            BackupStatistic(packageName, count, firstTimestamp, latestTimestamp)
        }
    }

    private fun stringArray(values: Collection<String>): JSONArray {
        return JSONArray().also { array -> values.forEach { array.put(it) } }
    }

    private fun JSONObject.requiredObject(key: String): JSONObject = getJSONObject(key)
    private fun JSONObject.requiredArray(key: String): JSONArray = getJSONArray(key)

    private fun JSONObject.requiredString(key: String): String {
        if (get(key) !is String) throw BackupFormatException("字段 $key 类型错误")
        return getString(key)
    }

    private fun JSONObject.requiredBoolean(key: String): Boolean {
        if (get(key) !is Boolean) throw BackupFormatException("字段 $key 类型错误")
        return getBoolean(key)
    }

    private fun JSONObject.requiredLong(key: String): Long {
        val value = get(key)
        if (value !is Number) throw BackupFormatException("字段 $key 类型错误")
        val doubleValue = value.toDouble()
        val longValue = value.toLong()
        if (!doubleValue.isFinite() || doubleValue != longValue.toDouble()) {
            throw BackupFormatException("字段 $key 必须是整数")
        }
        return longValue
    }

    private fun JSONObject.requiredInt(key: String): Int {
        val value = requiredLong(key)
        if (value < Int.MIN_VALUE.toLong() || value > Int.MAX_VALUE.toLong()) {
            throw BackupFormatException("字段 $key 超出范围")
        }
        return value.toInt()
    }

    private inline fun <reified T : Enum<T>> JSONObject.requiredEnum(key: String): T {
        val value = requiredString(key)
        return enumValues<T>().firstOrNull { it.name == value }
            ?: throw BackupFormatException("字段 $key 的值不受支持")
    }

    private fun JSONObject.requiredStringSet(key: String): Set<String> {
        val array = requiredArray(key)
        val result = linkedSetOf<String>()
        for (index in 0 until array.length()) {
            val value = array.get(index)
            if (value !is String) throw BackupFormatException("字段 $key 类型错误")
            validatePackageName(value)
            result.add(value)
        }
        return result
    }

    private fun validatePackageName(packageName: String) {
        if (packageName.isBlank() || packageName.length > MAX_PACKAGE_NAME_LENGTH ||
            !PACKAGE_NAME_PATTERN.matches(packageName)
        ) {
            throw BackupFormatException("备份中包含无效应用包名")
        }
    }

    private fun Long.requireNonNegative(label: String): Long {
        if (this < 0) throw BackupFormatException("$label 无效")
        return this
    }

    private fun Int.requireNonNegative(label: String): Int {
        if (this < 0) throw BackupFormatException("$label 无效")
        return this
    }
}
