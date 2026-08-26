package top.xjunz.automator.rules

object ApplicationRuleMatcher {

    fun isPackageEnabled(
        rulesEnabled: Boolean,
        strictMode: Boolean,
        whitelist: Set<String>,
        blacklist: Set<String>,
        packageName: String
    ): Boolean {
        if (!rulesEnabled) return true
        if (packageName in blacklist) return false
        return !strictMode || packageName in whitelist
    }

    fun isPackageSelected(
        strictMode: Boolean,
        whitelist: Set<String>,
        blacklist: Set<String>,
        packageName: String
    ): Boolean {
        if (packageName in blacklist) return false
        return !strictMode || packageName in whitelist
    }
}
