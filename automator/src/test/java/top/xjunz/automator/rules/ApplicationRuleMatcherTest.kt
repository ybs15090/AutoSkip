package top.xjunz.automator.rules

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationRuleMatcherTest {

    private val selectedPackage = "com.example.selected"
    private val blockedPackage = "com.example.blocked"
    private val otherPackage = "com.example.other"

    @Test
    fun disabledRulesAllowEveryPackageWithoutDiscardingSelections() {
        assertTrue(
            ApplicationRuleMatcher.isPackageEnabled(
                rulesEnabled = false,
                strictMode = true,
                whitelist = setOf(selectedPackage),
                blacklist = setOf(blockedPackage),
                packageName = blockedPackage
            )
        )
        assertFalse(
            ApplicationRuleMatcher.isPackageSelected(
                strictMode = true,
                whitelist = setOf(selectedPackage),
                blacklist = setOf(blockedPackage),
                packageName = blockedPackage
            )
        )
    }

    @Test
    fun normalModeAllowsPackagesExceptBlacklist() {
        assertTrue(
            ApplicationRuleMatcher.isPackageEnabled(
                rulesEnabled = true,
                strictMode = false,
                whitelist = emptySet(),
                blacklist = setOf(blockedPackage),
                packageName = otherPackage
            )
        )
        assertFalse(
            ApplicationRuleMatcher.isPackageEnabled(
                rulesEnabled = true,
                strictMode = false,
                whitelist = emptySet(),
                blacklist = setOf(blockedPackage),
                packageName = blockedPackage
            )
        )
    }

    @Test
    fun strictModeAllowsOnlyWhitelistAndBlacklistWins() {
        assertTrue(
            ApplicationRuleMatcher.isPackageEnabled(
                rulesEnabled = true,
                strictMode = true,
                whitelist = setOf(selectedPackage),
                blacklist = emptySet(),
                packageName = selectedPackage
            )
        )
        assertFalse(
            ApplicationRuleMatcher.isPackageEnabled(
                rulesEnabled = true,
                strictMode = true,
                whitelist = setOf(selectedPackage),
                blacklist = emptySet(),
                packageName = otherPackage
            )
        )
        assertFalse(
            ApplicationRuleMatcher.isPackageEnabled(
                rulesEnabled = true,
                strictMode = true,
                whitelist = setOf(selectedPackage),
                blacklist = setOf(selectedPackage),
                packageName = selectedPackage
            )
        )
    }
}
