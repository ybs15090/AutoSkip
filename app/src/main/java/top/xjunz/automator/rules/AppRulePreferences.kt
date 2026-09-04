package top.xjunz.automator.rules

import android.content.Context
import top.xjunz.automator.app.AutomatorApp

data class AppRuleSnapshot(
    val enabled: Boolean,
    val strictMode: Boolean,
    val whitelist: Set<String>,
    val blacklist: Set<String>,
    val singleClickLimitEnabled: Boolean
)

data class PackageRuleState(
    val rulesEnabled: Boolean,
    val whitelisted: Boolean,
    val blacklisted: Boolean
)

object AppRulePreferences {
    private const val PREFERENCES_NAME = "application_rules"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_STRICT_MODE = "strict_mode"
    private const val KEY_WHITELIST = "whitelist"
    private const val KEY_BLACKLIST = "blacklist"
    private const val KEY_SINGLE_CLICK_LIMIT = "single_click_limit"

    private val preferences by lazy {
        AutomatorApp.appContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    }

    fun snapshot() = AppRuleSnapshot(
        enabled = preferences.getBoolean(KEY_ENABLED, true),
        strictMode = preferences.getBoolean(KEY_STRICT_MODE, false),
        whitelist = preferences.getStringSet(KEY_WHITELIST, emptySet()).orEmpty().toSet(),
        blacklist = preferences.getStringSet(KEY_BLACKLIST, emptySet()).orEmpty().toSet(),
        singleClickLimitEnabled = preferences.getBoolean(KEY_SINGLE_CLICK_LIMIT, false)
    )

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setStrictMode(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_STRICT_MODE, enabled).apply()
    }

    fun setSingleClickLimitEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_SINGLE_CLICK_LIMIT, enabled).apply()
    }

    fun isPackageEnabled(packageName: String): Boolean {
        val snapshot = snapshot()
        return ApplicationRuleMatcher.isPackageEnabled(
            snapshot.enabled,
            snapshot.strictMode,
            snapshot.whitelist,
            snapshot.blacklist,
            packageName
        )
    }

    fun isPackageSelected(packageName: String): Boolean {
        val snapshot = snapshot()
        return ApplicationRuleMatcher.isPackageSelected(
            snapshot.strictMode,
            snapshot.whitelist,
            snapshot.blacklist,
            packageName
        )
    }

    fun setPackageEnabled(packageName: String, enabled: Boolean) {
        val whitelist = snapshot().whitelist.toMutableSet()
        val blacklist = snapshot().blacklist.toMutableSet()
        if (enabled) {
            whitelist.add(packageName)
            blacklist.remove(packageName)
        } else {
            whitelist.remove(packageName)
            blacklist.add(packageName)
        }
        preferences.edit()
            .putStringSet(KEY_WHITELIST, whitelist)
            .putStringSet(KEY_BLACKLIST, blacklist)
            .apply()
    }

    fun getPackageRuleState(packageName: String): PackageRuleState {
        val snapshot = snapshot()
        return PackageRuleState(
            rulesEnabled = snapshot.enabled,
            whitelisted = packageName in snapshot.whitelist,
            blacklisted = packageName in snapshot.blacklist
        )
    }

    fun blacklistPackage(packageName: String) {
        setPackageEnabled(packageName, false)
    }

    fun removeFromBlacklist(packageName: String) {
        val blacklist = snapshot().blacklist.toMutableSet()
        if (!blacklist.remove(packageName)) return
        preferences.edit()
            .putStringSet(KEY_BLACKLIST, blacklist)
            .apply()
    }

    fun restorePackageRule(packageName: String, state: PackageRuleState) {
        val whitelist = snapshot().whitelist.toMutableSet()
        val blacklist = snapshot().blacklist.toMutableSet()
        if (state.whitelisted) whitelist.add(packageName) else whitelist.remove(packageName)
        if (state.blacklisted) blacklist.add(packageName) else blacklist.remove(packageName)
        preferences.edit()
            .putBoolean(KEY_ENABLED, state.rulesEnabled)
            .putStringSet(KEY_WHITELIST, whitelist)
            .putStringSet(KEY_BLACKLIST, blacklist)
            .apply()
    }
}
