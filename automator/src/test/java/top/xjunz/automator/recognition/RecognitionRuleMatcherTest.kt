package top.xjunz.automator.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionRuleMatcherTest {

    @Test
    fun newRulesDefaultToFullScreen() {
        assertEquals(
            RuleRegion.ANY,
            RecognitionRule(id = "default", pattern = "跳过").region
        )
    }

    @Test
    fun exactTextScoresHigherThanPartialContains() {
        val rule = rule(pattern = "跳过", mode = RuleMatchMode.CONTAINS)
        assertEquals(
            RecognitionRuleMatcher.SCORE_EXACT,
            RecognitionRuleMatcher.matchScore(rule, "跳过")
        )
        assertEquals(
            RecognitionRuleMatcher.SCORE_CONTAINS,
            RecognitionRuleMatcher.matchScore(rule, "5 秒后跳过")
        )
    }

    @Test
    fun regexSupportsCountdownAndInvalidPatternsAreIgnored() {
        assertEquals(
            RecognitionRuleMatcher.SCORE_REGEX,
            RecognitionRuleMatcher.matchScore(
                rule(pattern = "\\d+\\s*秒后跳过", mode = RuleMatchMode.REGEX),
                "5 秒后跳过"
            )
        )
        assertNull(
            RecognitionRuleMatcher.matchScore(
                rule(pattern = "[", mode = RuleMatchMode.REGEX),
                "跳过"
            )
        )
    }

    @Test
    fun matchingCanIgnoreCase() {
        val rule = rule(pattern = "Skip Ad", mode = RuleMatchMode.EXACT)
        assertEquals(
            RecognitionRuleMatcher.SCORE_EXACT,
            RecognitionRuleMatcher.matchScore(rule, "SKIP AD")
        )
        assertNull(
            RecognitionRuleMatcher.matchScore(rule.copy(ignoreCase = false), "SKIP AD")
        )
    }

    @Test
    fun regionsUseScreenHalves() {
        assertTrue(RecognitionRuleMatcher.isInRegion(RuleRegion.TOP_RIGHT, 0.9f, 0.1f))
        assertFalse(RecognitionRuleMatcher.isInRegion(RuleRegion.TOP_RIGHT, 0.1f, 0.1f))
        assertTrue(RecognitionRuleMatcher.isInRegion(RuleRegion.BOTTOM_LEFT, 0.1f, 0.9f))
        assertTrue(RecognitionRuleMatcher.isInRegion(RuleRegion.ANY, 0.5f, 0.5f))
    }

    @Test
    fun candidateScoringRewardsExactSmallClickableControls() {
        val strong = RecognitionCandidateScorer.score(
            matchScore = RecognitionRuleMatcher.SCORE_EXACT,
            feature = RuleFeature.TEXT,
            configuredRegion = RuleRegion.TOP_RIGHT,
            normalizedX = 0.9f,
            normalizedY = 0.1f,
            areaRatio = 0.01f,
            clickable = true,
            parentClickable = false
        )
        val weak = RecognitionCandidateScorer.score(
            matchScore = RecognitionRuleMatcher.SCORE_CONTAINS,
            feature = RuleFeature.TEXT,
            configuredRegion = RuleRegion.ANY,
            normalizedX = 0.5f,
            normalizedY = 0.5f,
            areaRatio = 0.4f,
            clickable = false,
            parentClickable = false
        )
        assertTrue(strong > weak)
    }

    @Test
    fun platformTextSearchUsesDistinctLiteralTextQueries() {
        val rules = listOf(
            rule(pattern = "跳过", mode = RuleMatchMode.CONTAINS),
            rule(pattern = " 跳过 ", mode = RuleMatchMode.EXACT).copy(
                feature = RuleFeature.CONTENT_DESCRIPTION
            ),
            rule(pattern = "skip", mode = RuleMatchMode.CONTAINS),
            rule(pattern = "\\d+秒后跳过", mode = RuleMatchMode.REGEX),
            rule(pattern = "skip_button", mode = RuleMatchMode.EXACT).copy(
                feature = RuleFeature.VIEW_ID
            )
        )

        assertEquals(
            listOf("跳过", "skip"),
            RecognitionSearchStrategy.platformTextQueries(rules)
        )
        assertEquals(
            "跳过",
            RecognitionSearchStrategy.platformTextQueryForCheck(rules, completedChecks = 0)
        )
        assertEquals(
            "skip",
            RecognitionSearchStrategy.platformTextQueryForCheck(rules, completedChecks = 1)
        )
        assertEquals(
            "跳过",
            RecognitionSearchStrategy.platformTextQueryForCheck(rules, completedChecks = 2)
        )
        assertNull(
            RecognitionSearchStrategy.platformTextQueryForCheck(
                listOf(rule(pattern = "\\d+秒后跳过", mode = RuleMatchMode.REGEX)),
                completedChecks = 0
            )
        )
        assertFalse(RecognitionSearchStrategy.isPlatformTextSearchComplete(rules))
        assertFalse(RecognitionSearchStrategy.isPlatformTextSearchComplete(rules.take(3)))
        assertTrue(
            RecognitionSearchStrategy.isPlatformTextSearchComplete(
                listOf(rules[0], rules[2])
            )
        )
    }

    @Test
    fun applicationOverridesCanBePausedWithoutDeletingTheirRules() {
        val global = rule(pattern = "跳过", mode = RuleMatchMode.CONTAINS)
        val application = rule(pattern = "关闭广告", mode = RuleMatchMode.EXACT)
        val configuration = RecognitionConfiguration(
            globalRules = listOf(global),
            applicationRules = mapOf(
                "com.example.custom" to listOf(application),
                "com.example.paused" to listOf(application),
                "com.example.rule-disabled" to listOf(application.copy(enabled = false))
            ),
            disabledApplicationRulePackages = setOf("com.example.paused")
        )
        assertEquals(listOf(global), configuration.rulesFor("com.example.default"))
        assertEquals(listOf(application), configuration.rulesFor("com.example.custom"))
        assertTrue(configuration.isApplicationOverrideEnabled("com.example.custom"))
        assertEquals(listOf(global), configuration.rulesFor("com.example.paused"))
        assertTrue(configuration.hasApplicationRules("com.example.paused"))
        assertFalse(configuration.isApplicationOverrideEnabled("com.example.paused"))
        assertTrue(configuration.rulesFor("com.example.rule-disabled").isEmpty())
    }

    private fun rule(pattern: String, mode: RuleMatchMode) = RecognitionRule(
        id = "test",
        pattern = pattern,
        matchMode = mode
    )
}
