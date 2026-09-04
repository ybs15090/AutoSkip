package top.xjunz.automator.rules

import android.content.Context
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorApp
import top.xjunz.automator.recognition.RecognitionConfiguration
import top.xjunz.automator.recognition.RecognitionRule
import top.xjunz.automator.recognition.RecognitionRuleCodec
import top.xjunz.automator.recognition.RuleRegion

object RecognitionRulePreferences {
    private const val PREFERENCES_NAME = "recognition_rules"
    private const val KEY_CONFIGURATION = "configuration"
    private const val KEY_DEFAULTS_REVISION = "defaults_revision"
    private const val CURRENT_DEFAULTS_REVISION = 2
    private val builtInRuleIds = setOf(
        "default_text_skip_zh",
        "default_text_skip_en",
        "default_description_skip_zh",
        "default_description_skip_en"
    )

    private val preferences by lazy {
        AutomatorApp.appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    private val defaultConfigurationJson by lazy {
        AutomatorApp.appContext.resources.openRawResource(R.raw.default_recognition_rules)
            .bufferedReader()
            .use { it.readText() }
    }

    fun configurationJson(): String {
        if (!preferences.contains(KEY_CONFIGURATION)) {
            preferences.edit()
                .putString(KEY_CONFIGURATION, defaultConfigurationJson)
                .putInt(KEY_DEFAULTS_REVISION, CURRENT_DEFAULTS_REVISION)
                .apply()
        } else if (preferences.getInt(KEY_DEFAULTS_REVISION, 1) < CURRENT_DEFAULTS_REVISION) {
            migrateBuiltInRulesToFullScreen()
        }
        return preferences.getString(KEY_CONFIGURATION, defaultConfigurationJson)
            ?: defaultConfigurationJson
    }

    fun initialize() {
        configurationJson()
    }

    fun snapshot(): RecognitionConfiguration {
        return RecognitionRuleCodec.decode(configurationJson())
    }

    /** Replaces global and application-specific recognition rules as one configuration. */
    fun replace(configuration: RecognitionConfiguration): Boolean {
        val normalizedApplicationRules = configuration.applicationRules
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, rules) -> rules.map(RecognitionRule::normalized) }
            .filterValues { it.isNotEmpty() }
        val normalized = configuration.copy(
            globalRules = configuration.globalRules.map(RecognitionRule::normalized),
            applicationRules = normalizedApplicationRules,
            disabledApplicationRulePackages = configuration.disabledApplicationRulePackages
                .filter(normalizedApplicationRules::containsKey)
                .toSet()
        )
        return preferences.edit()
            .putString(KEY_CONFIGURATION, RecognitionRuleCodec.encode(normalized))
            .putInt(KEY_DEFAULTS_REVISION, CURRENT_DEFAULTS_REVISION)
            .commit()
    }

    fun rulesForEditor(packageName: String?): List<RecognitionRule> {
        val configuration = snapshot()
        return if (packageName == null) {
            configuration.globalRules
        } else {
            configuration.applicationRules[packageName].orEmpty()
        }
    }

    fun hasApplicationRules(packageName: String): Boolean {
        return snapshot().hasApplicationRules(packageName)
    }

    fun isApplicationOverrideEnabled(packageName: String): Boolean {
        return snapshot().isApplicationOverrideEnabled(packageName)
    }

    fun saveRules(packageName: String?, rules: List<RecognitionRule>) {
        val current = snapshot()
        val next = if (packageName == null) {
            current.copy(globalRules = rules.map(RecognitionRule::normalized))
        } else {
            val applicationRules = current.applicationRules.toMutableMap()
            val disabledPackages = current.disabledApplicationRulePackages.toMutableSet()
            if (rules.isEmpty()) {
                applicationRules.remove(packageName)
                disabledPackages.remove(packageName)
            } else {
                if (!current.hasApplicationRules(packageName)) {
                    disabledPackages.remove(packageName)
                }
                applicationRules[packageName] = rules.map(RecognitionRule::normalized)
            }
            current.copy(
                applicationRules = applicationRules,
                disabledApplicationRulePackages = disabledPackages
            )
        }
        persist(next)
    }

    fun restoreDefaultGlobalRules() {
        val current = snapshot()
        val defaults = RecognitionRuleCodec.decode(defaultConfigurationJson)
        persist(current.copy(globalRules = defaults.globalRules))
    }

    fun setApplicationOverrideEnabled(packageName: String, enabled: Boolean) {
        val current = snapshot()
        if (!current.hasApplicationRules(packageName)) return
        val disabledPackages = current.disabledApplicationRulePackages.toMutableSet()
        if (enabled) {
            disabledPackages.remove(packageName)
        } else {
            disabledPackages.add(packageName)
        }
        persist(current.copy(disabledApplicationRulePackages = disabledPackages))
    }

    private fun persist(configuration: RecognitionConfiguration) {
        preferences.edit()
            .putString(KEY_CONFIGURATION, RecognitionRuleCodec.encode(configuration))
            .apply()
    }

    private fun migrateBuiltInRulesToFullScreen() {
        val currentJson = preferences.getString(KEY_CONFIGURATION, defaultConfigurationJson)
            ?: defaultConfigurationJson
        val current = RecognitionRuleCodec.decode(currentJson)
        val migrated = current.copy(
            globalRules = current.globalRules.map { rule ->
                if (rule.id in builtInRuleIds) {
                    rule.copy(region = RuleRegion.ANY)
                } else {
                    rule
                }
            }
        )
        preferences.edit()
            .putString(KEY_CONFIGURATION, RecognitionRuleCodec.encode(migrated))
            .putInt(KEY_DEFAULTS_REVISION, CURRENT_DEFAULTS_REVISION)
            .apply()
    }
}
