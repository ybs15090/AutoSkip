package top.xjunz.automator.recognition

enum class RuleFeature {
    TEXT,
    CONTENT_DESCRIPTION,
    VIEW_ID,
    CLASS_NAME
}

enum class RuleMatchMode {
    EXACT,
    CONTAINS,
    REGEX
}

enum class RuleRegion {
    ANY,
    TOP,
    BOTTOM,
    LEFT,
    RIGHT,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

enum class RuleClickMode {
    AUTO,
    ACCESSIBILITY_ACTION,
    COORDINATE
}

data class RecognitionRule(
    val id: String,
    val enabled: Boolean = true,
    val feature: RuleFeature = RuleFeature.TEXT,
    val matchMode: RuleMatchMode = RuleMatchMode.CONTAINS,
    val pattern: String,
    val ignoreCase: Boolean = true,
    val region: RuleRegion = RuleRegion.ANY,
    val delayMillis: Long = 0,
    val clickMode: RuleClickMode = RuleClickMode.AUTO
) {
    fun normalized() = copy(
        pattern = pattern.trim(),
        delayMillis = delayMillis.coerceIn(0, MAX_DELAY_MILLIS)
    )

    companion object {
        const val MAX_DELAY_MILLIS = 15_000L
    }
}

data class RecognitionConfiguration(
    val globalRules: List<RecognitionRule> = emptyList(),
    val applicationRules: Map<String, List<RecognitionRule>> = emptyMap(),
    val disabledApplicationRulePackages: Set<String> = emptySet()
) {
    fun hasApplicationRules(packageName: String): Boolean {
        return applicationRules[packageName]?.isNotEmpty() == true
    }

    fun isApplicationOverrideEnabled(packageName: String): Boolean {
        return hasApplicationRules(packageName) &&
            packageName !in disabledApplicationRulePackages
    }

    fun rulesFor(packageName: String): List<RecognitionRule> {
        val specificRules = applicationRules[packageName]
        val selectedRules = if (
            specificRules.isNullOrEmpty() ||
            packageName in disabledApplicationRulePackages
        ) {
            globalRules
        } else {
            specificRules
        }
        return selectedRules.asSequence()
            .filter { it.enabled && it.pattern.isNotBlank() }
            .toList()
    }
}

data class LearningCandidate(
    val key: String,
    val packageName: String,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val region: RuleRegion,
    val clickable: Boolean,
    val parentClickable: Boolean,
    val score: Int,
    val timestamp: Long
)

object RecognitionRuleMatcher {

    const val SCORE_EXACT = 120
    const val SCORE_CONTAINS = 90
    const val SCORE_REGEX = 80

    fun matchScore(rule: RecognitionRule, candidateValue: String?): Int? {
        val value = candidateValue?.trim().orEmpty()
        val pattern = rule.pattern.trim()
        if (value.isEmpty() || pattern.isEmpty()) return null
        val exact = value.equals(pattern, ignoreCase = rule.ignoreCase)
        return when (rule.matchMode) {
            RuleMatchMode.EXACT -> if (exact) SCORE_EXACT else null
            RuleMatchMode.CONTAINS -> when {
                exact -> SCORE_EXACT
                value.contains(pattern, ignoreCase = rule.ignoreCase) -> SCORE_CONTAINS
                else -> null
            }
            RuleMatchMode.REGEX -> runCatching {
                val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                if (Regex(pattern, options).containsMatchIn(value)) SCORE_REGEX else null
            }.getOrNull()
        }
    }

    fun isInRegion(region: RuleRegion, normalizedX: Float, normalizedY: Float): Boolean {
        val left = normalizedX < 0.5f
        val top = normalizedY < 0.5f
        return when (region) {
            RuleRegion.ANY -> true
            RuleRegion.TOP -> top
            RuleRegion.BOTTOM -> !top
            RuleRegion.LEFT -> left
            RuleRegion.RIGHT -> !left
            RuleRegion.TOP_LEFT -> top && left
            RuleRegion.TOP_RIGHT -> top && !left
            RuleRegion.BOTTOM_LEFT -> !top && left
            RuleRegion.BOTTOM_RIGHT -> !top && !left
        }
    }
}

object RecognitionCandidateScorer {

    fun score(
        matchScore: Int,
        feature: RuleFeature,
        configuredRegion: RuleRegion,
        normalizedX: Float,
        normalizedY: Float,
        areaRatio: Float,
        clickable: Boolean,
        parentClickable: Boolean
    ): Int {
        var score = matchScore
        score += when (feature) {
            RuleFeature.VIEW_ID -> 20
            RuleFeature.TEXT -> 15
            RuleFeature.CONTENT_DESCRIPTION -> 12
            RuleFeature.CLASS_NAME -> 0
        }
        if (configuredRegion != RuleRegion.ANY) {
            score += 25
        } else {
            if (normalizedX >= 0.5f && normalizedY < 0.5f) score += 18
            else if (normalizedY < 0.5f) score += 8
        }
        score += when {
            clickable -> 25
            parentClickable -> 12
            else -> 0
        }
        score += when {
            areaRatio <= 0.01f -> 25
            areaRatio <= 0.05f -> 20
            areaRatio <= 0.15f -> 8
            areaRatio > 0.35f -> -30
            else -> 0
        }
        return score
    }
}

internal object RecognitionSearchStrategy {

    fun platformTextQueries(rules: List<RecognitionRule>): List<String> {
        return rules.asSequence()
            .filter { it.enabled && it.pattern.isNotBlank() }
            .filter {
                it.feature == RuleFeature.TEXT ||
                    it.feature == RuleFeature.CONTENT_DESCRIPTION
            }
            .filter { it.matchMode != RuleMatchMode.REGEX }
            .map { it.pattern.trim() }
            .distinct()
            .toList()
    }

    fun platformTextQueryForCheck(
        rules: List<RecognitionRule>,
        completedChecks: Int
    ): String? {
        val queries = platformTextQueries(rules)
        if (queries.isEmpty()) return null
        return queries[completedChecks.coerceAtLeast(0) % queries.size]
    }

    fun isPlatformTextSearchComplete(rules: List<RecognitionRule>): Boolean {
        return rules.isNotEmpty() && rules.all {
            it.enabled && it.pattern.isNotBlank() &&
                it.feature == RuleFeature.TEXT &&
                it.matchMode != RuleMatchMode.REGEX
        }
    }
}
