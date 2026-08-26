package top.xjunz.automator.rules

import android.content.Context
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorApp
import top.xjunz.automator.recognition.RecognitionConfiguration
import top.xjunz.automator.recognition.RecognitionRule
import top.xjunz.automator.recognition.RecognitionRuleCodec

object RecognitionRulePreferences {
    private const val PREFERENCES_NAME = "recognition_rules"
    private const val KEY_CONFIGURATION = "configuration"

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
            preferences.edit().putString(KEY_CONFIGURATION, defaultConfigurationJson).apply()
        }
        return preferences.getString(KEY_CONFIGURATION, defaultConfigurationJson)
            ?: defaultConfigurationJson
    }

    fun snapshot(): RecognitionConfiguration {
        return RecognitionRuleCodec.decode(configurationJson())
    }

    fun rulesForEditor(packageName: String?): List<RecognitionRule> {
        val configuration = snapshot()
        return if (packageName == null) {
            configuration.globalRules
        } else {
            configuration.applicationRules[packageName].orEmpty()
        }
    }

    fun hasApplicationOverride(packageName: String): Boolean {
        return snapshot().applicationRules[packageName]?.isNotEmpty() == true
    }

    fun saveRules(packageName: String?, rules: List<RecognitionRule>) {
        val current = snapshot()
        val next = if (packageName == null) {
            current.copy(globalRules = rules.map(RecognitionRule::normalized))
        } else {
            val applicationRules = current.applicationRules.toMutableMap()
            if (rules.isEmpty()) {
                applicationRules.remove(packageName)
            } else {
                applicationRules[packageName] = rules.map(RecognitionRule::normalized)
            }
            current.copy(applicationRules = applicationRules)
        }
        persist(next)
    }

    fun restoreDefaultGlobalRules() {
        val current = snapshot()
        val defaults = RecognitionRuleCodec.decode(defaultConfigurationJson)
        persist(current.copy(globalRules = defaults.globalRules))
    }

    fun removeApplicationOverride(packageName: String) {
        saveRules(packageName, emptyList())
    }

    private fun persist(configuration: RecognitionConfiguration) {
        preferences.edit()
            .putString(KEY_CONFIGURATION, RecognitionRuleCodec.encode(configuration))
            .apply()
    }
}
