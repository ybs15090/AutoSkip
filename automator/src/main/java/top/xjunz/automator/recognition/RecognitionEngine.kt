package top.xjunz.automator.recognition

import android.app.UiAutomation
import android.graphics.Rect
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import top.xjunz.automator.model.Result

class RecognitionEngine(private val uiAutomation: UiAutomation) {

    data class Match internal constructor(
        val rule: RecognitionRule,
        internal val node: AccessibilityNodeInfo,
        val packageName: String,
        val matchedValue: String,
        val bounds: Rect,
        val windowBounds: Rect,
        val windowId: Int,
        val score: Int,
        val clickable: Boolean,
        val parentClickable: Boolean,
        val areaRatio: Float
    ) {
        fun recycle() = node.recycle()
    }

    data class ExecutionOutcome(
        val successful: Boolean,
        val usedParent: Boolean = false,
        val usedCoordinate: Boolean = false,
        val effectiveBounds: Rect? = null
    )

    fun findBestMatch(
        root: AccessibilityNodeInfo,
        packageName: String,
        rules: List<RecognitionRule>,
        requiredRuleId: String? = null
    ): Match? = findBestMatchInternal(
        root,
        packageName,
        rules,
        requiredRuleId,
        platformTextOnly = false,
        platformTextQuery = null
    )

    /**
     * Performs one platform-indexed text query for foreground discovery. The caller rotates the
     * configured literal queries between checks, avoiding repeated full-tree walks while a cold
     * WebView is still publishing its virtual accessibility nodes.
     */
    fun findBestPlatformTextMatch(
        root: AccessibilityNodeInfo,
        packageName: String,
        rules: List<RecognitionRule>,
        completedChecks: Int
    ): Match? {
        val query = RecognitionSearchStrategy.platformTextQueryForCheck(rules, completedChecks)
            ?: return null
        return findBestMatchInternal(
            root,
            packageName,
            rules,
            requiredRuleId = null,
            platformTextOnly = true,
            platformTextQuery = query
        )
    }

    private fun findBestMatchInternal(
        root: AccessibilityNodeInfo,
        packageName: String,
        rules: List<RecognitionRule>,
        requiredRuleId: String?,
        platformTextOnly: Boolean,
        platformTextQuery: String?
    ): Match? {
        if (rules.isEmpty()) return null
        val preparedRules = rules.asSequence()
            .filter { requiredRuleId == null || it.id == requiredRuleId }
            .mapNotNull(::prepareRule)
            .toList()
        if (preparedRules.isEmpty()) return null
        val windowBounds = resolveWindowBounds(root) ?: return null
        var best: Match? = null
        var visited = 0

        fun consider(node: AccessibilityNodeInfo) {
            if (node.isVisibleToUser && !node.isEditable) {
                val matchingRules = preparedRules.mapNotNull { prepared ->
                    val value = valueFor(node, prepared.rule.feature)
                    val matchScore = matchScore(prepared, value) ?: return@mapNotNull null
                    PreparedMatch(prepared.rule, value?.trim().orEmpty(), matchScore)
                }
                if (matchingRules.isNotEmpty()) {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        val normalizedX = ((bounds.exactCenterX() - windowBounds.left) /
                            windowBounds.width().coerceAtLeast(1)).coerceIn(0f, 1f)
                        val normalizedY = ((bounds.exactCenterY() - windowBounds.top) /
                            windowBounds.height().coerceAtLeast(1)).coerceIn(0f, 1f)
                        val areaRatio = bounds.width().toFloat() * bounds.height() /
                            (windowBounds.width().coerceAtLeast(1).toFloat() *
                                windowBounds.height().coerceAtLeast(1))
                        val parentClickable = hasClickableAncestor(node)
                        matchingRules.forEach { preparedMatch ->
                            val rule = preparedMatch.rule
                            if (!RecognitionRuleMatcher.isInRegion(rule.region, normalizedX, normalizedY)) {
                                return@forEach
                            }
                            val score = RecognitionCandidateScorer.score(
                                preparedMatch.matchScore,
                                rule.feature,
                                rule.region,
                                normalizedX,
                                normalizedY,
                                areaRatio,
                                node.isClickable,
                                parentClickable
                            )
                            val currentBest = best
                            if (currentBest == null || isBetter(
                                    score,
                                    node.isClickable,
                                    areaRatio,
                                    normalizedX,
                                    normalizedY,
                                    currentBest
                                )
                            ) {
                                currentBest?.recycle()
                                best = Match(
                                    rule = rule,
                                    node = AccessibilityNodeInfo.obtain(node),
                                    packageName = packageName,
                                    matchedValue = preparedMatch.value,
                                    bounds = Rect(bounds),
                                    windowBounds = Rect(windowBounds),
                                    windowId = node.windowId,
                                    score = score,
                                    clickable = node.isClickable,
                                    parentClickable = parentClickable,
                                    areaRatio = areaRatio
                                )
                            }
                        }
                    }
                }
            }
        }

        val preparedRecognitionRules = preparedRules.map { it.rule }
        val platformTextQueries = if (platformTextQuery == null) {
            RecognitionSearchStrategy.platformTextQueries(preparedRecognitionRules)
        } else {
            listOf(platformTextQuery)
        }
        if (platformTextQueries.isNotEmpty()) {
            // Besides being faster than walking a deep virtual tree, the platform query makes some
            // WebViews publish their accessibility nodes while the early window events are handled.
            platformTextQueries.forEach { query ->
                val candidates = runCatching {
                    root.findAccessibilityNodeInfosByText(query)
                }.getOrNull() ?: return@forEach
                candidates.forEach { candidate ->
                    try {
                        consider(candidate)
                    } finally {
                        candidate.recycle()
                    }
                }
            }
            if (platformTextOnly) return best
            if (best != null && RecognitionSearchStrategy.isPlatformTextSearchComplete(
                    preparedRecognitionRules
                )
            ) {
                return best
            }
        }
        if (platformTextOnly) return null

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= MAX_VISITED_NODES || depth > MAX_TREE_DEPTH) return
            visited++
            consider(node)
            for (index in 0 until node.childCount) {
                if (visited >= MAX_VISITED_NODES) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, 0)
        return best
    }

    fun execute(match: Match): ExecutionOutcome {
        return when (match.rule.clickMode) {
            RuleClickMode.ACCESSIBILITY_ACTION -> performAccessibilityAction(match.node)
            RuleClickMode.COORDINATE -> ExecutionOutcome(
                successful = injectFingerClickEvent(match.bounds),
                usedCoordinate = true,
                effectiveBounds = Rect(match.bounds)
            )
            RuleClickMode.AUTO -> {
                val actionOutcome = performAccessibilityAction(match.node)
                if (actionOutcome.successful) actionOutcome else ExecutionOutcome(
                    successful = injectFingerClickEvent(match.bounds),
                    usedCoordinate = true,
                    effectiveBounds = Rect(match.bounds)
                )
            }
        }
    }

    fun writeResult(
        match: Match,
        outcome: ExecutionOutcome,
        result: Result,
        detectionOnly: Boolean = false
    ) {
        result.pkgName = match.packageName
        result.text = match.matchedValue
        result.nodeHash = match.node.hashCode()
        result.bounds = Rect(outcome.effectiveBounds ?: match.bounds)
        result.parentBounds = if (outcome.usedParent) outcome.effectiveBounds?.let(::Rect) else null
        result.portrait = match.windowBounds.height() >= match.windowBounds.width()
        result.passed = detectionOnly || outcome.successful
        if (!match.clickable || outcome.usedParent) {
            result.maskReason(Result.REASON_MASK_NOT_CLICKABLE)
        }
        if (outcome.usedCoordinate) {
            result.maskReason(Result.REASON_MASK_NOT_CLICKABLE or Result.REASON_MASK_PARENT)
        }
        if (!result.passed) {
            result.maskReason(Result.REASON_ILLEGAL_TARGET)
        }
    }

    fun discoverCandidates(
        root: AccessibilityNodeInfo,
        packageName: String,
        timestamp: Long = System.currentTimeMillis()
    ): List<LearningCandidate> {
        val windowBounds = resolveWindowBounds(root) ?: return emptyList()
        val candidates = mutableListOf<LearningCandidate>()
        var visited = 0

        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (visited >= MAX_VISITED_NODES || depth > MAX_TREE_DEPTH) return
            visited++
            if (node.isVisibleToUser && !node.isEditable) {
                val text = node.text?.toString()?.trim().limited()
                val description = node.contentDescription?.toString()?.trim().limited()
                val viewId = node.viewIdResourceName?.trim().limited()
                val className = node.className?.toString()?.trim().limited()
                if (!text.isNullOrBlank() || !description.isNullOrBlank() ||
                    !viewId.isNullOrBlank() || (node.isClickable && !className.isNullOrBlank())
                ) {
                    val parentClickable = hasClickableAncestor(node)
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.isEmpty) {
                        val normalizedX = ((bounds.exactCenterX() - windowBounds.left) /
                            windowBounds.width().coerceAtLeast(1)).coerceIn(0f, 1f)
                        val normalizedY = ((bounds.exactCenterY() - windowBounds.top) /
                            windowBounds.height().coerceAtLeast(1)).coerceIn(0f, 1f)
                        val areaRatio = bounds.width().toFloat() * bounds.height() /
                            (windowBounds.width().coerceAtLeast(1).toFloat() *
                                windowBounds.height().coerceAtLeast(1))
                        val region = quadrantFor(normalizedX, normalizedY)
                        val score = learningScore(
                            text,
                            description,
                            viewId,
                            areaRatio,
                            normalizedX,
                            normalizedY,
                            node.isClickable,
                            parentClickable
                        )
                        val rawKey = listOf(
                            packageName,
                            text,
                            description,
                            viewId,
                            className,
                            bounds.flattenToString()
                        ).joinToString("|")
                        candidates.add(
                            LearningCandidate(
                                key = rawKey.hashCode().toString(),
                                packageName = packageName,
                                text = text,
                                contentDescription = description,
                                viewId = viewId,
                                className = className,
                                left = bounds.left,
                                top = bounds.top,
                                right = bounds.right,
                                bottom = bounds.bottom,
                                region = region,
                                clickable = node.isClickable,
                                parentClickable = parentClickable,
                                score = score,
                                timestamp = timestamp
                            )
                        )
                    }
                }
            }
            for (index in 0 until node.childCount) {
                if (visited >= MAX_VISITED_NODES) break
                val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                try {
                    visit(child, depth + 1)
                } finally {
                    child.recycle()
                }
            }
        }

        visit(root, 0)
        return candidates.sortedWith(
            compareByDescending<LearningCandidate> { it.score }
                .thenByDescending { it.timestamp }
        ).take(MAX_LEARNING_CANDIDATES)
    }

    private fun performAccessibilityAction(node: AccessibilityNodeInfo): ExecutionOutcome {
        if (node.isClickable && runCatching {
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }.getOrDefault(false)
        ) {
            val bounds = Rect().also(node::getBoundsInScreen)
            return ExecutionOutcome(true, effectiveBounds = bounds)
        }
        var parent = runCatching { node.parent }.getOrNull()
        var level = 0
        while (parent != null && level < MAX_PARENT_DEPTH) {
            val current = parent
            val next = runCatching { current.parent }.getOrNull()
            try {
                if (current.isClickable && runCatching {
                        current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }.getOrDefault(false)
                ) {
                    val bounds = Rect().also(current::getBoundsInScreen)
                    next?.recycle()
                    return ExecutionOutcome(true, usedParent = true, effectiveBounds = bounds)
                }
            } finally {
                current.recycle()
            }
            parent = next
            level++
        }
        parent?.recycle()
        return ExecutionOutcome(false)
    }

    private fun injectFingerClickEvent(rect: Rect): Boolean {
        val downTime = android.os.SystemClock.uptimeMillis()
        val downAction = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            rect.exactCenterX(),
            rect.exactCenterY(),
            0
        )
        downAction.source = InputDevice.SOURCE_TOUCHSCREEN
        val upAction = MotionEvent.obtain(downAction).apply { action = MotionEvent.ACTION_UP }
        return try {
            uiAutomation.injectInputEvent(downAction, true) &&
                uiAutomation.injectInputEvent(upAction, true)
        } finally {
            upAction.recycle()
            downAction.recycle()
        }
    }

    private fun valueFor(node: AccessibilityNodeInfo, feature: RuleFeature): String? {
        return when (feature) {
            RuleFeature.TEXT -> node.text?.toString()
            RuleFeature.CONTENT_DESCRIPTION -> node.contentDescription?.toString()
            RuleFeature.VIEW_ID -> node.viewIdResourceName
            RuleFeature.CLASS_NAME -> node.className?.toString()
        }
    }

    private fun prepareRule(rule: RecognitionRule): PreparedRule? {
        val regex = if (rule.matchMode == RuleMatchMode.REGEX) {
            val options = if (rule.ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
            runCatching { Regex(rule.pattern, options) }.getOrNull() ?: return null
        } else {
            null
        }
        return PreparedRule(rule, regex)
    }

    private fun matchScore(prepared: PreparedRule, candidateValue: String?): Int? {
        return if (prepared.rule.matchMode == RuleMatchMode.REGEX) {
            val value = candidateValue?.trim().orEmpty()
            if (value.isNotEmpty() && prepared.regex?.containsMatchIn(value) == true) {
                RecognitionRuleMatcher.SCORE_REGEX
            } else {
                null
            }
        } else {
            RecognitionRuleMatcher.matchScore(prepared.rule, candidateValue)
        }
    }

    private fun resolveWindowBounds(root: AccessibilityNodeInfo): Rect? {
        val bounds = Rect()
        val window = runCatching { root.window }.getOrNull()
        if (window != null) {
            try {
                window.getBoundsInScreen(bounds)
            } finally {
                window.recycle()
            }
        }
        if (bounds.isEmpty) root.getBoundsInScreen(bounds)
        return bounds.takeUnless { it.isEmpty }
    }

    private fun hasClickableAncestor(node: AccessibilityNodeInfo): Boolean {
        var parent = runCatching { node.parent }.getOrNull()
        var level = 0
        while (parent != null && level < MAX_PARENT_DEPTH) {
            val current = parent
            val next = runCatching { current.parent }.getOrNull()
            try {
                if (current.isClickable) {
                    next?.recycle()
                    return true
                }
            } finally {
                current.recycle()
            }
            parent = next
            level++
        }
        parent?.recycle()
        return false
    }

    private fun isBetter(
        score: Int,
        clickable: Boolean,
        areaRatio: Float,
        normalizedX: Float,
        normalizedY: Float,
        current: Match
    ): Boolean {
        if (score != current.score) return score > current.score
        if (clickable != current.clickable) return clickable
        if (areaRatio != current.areaRatio) return areaRatio < current.areaRatio
        val currentX = (current.bounds.exactCenterX() - current.windowBounds.left) /
            current.windowBounds.width().coerceAtLeast(1)
        val currentY = (current.bounds.exactCenterY() - current.windowBounds.top) /
            current.windowBounds.height().coerceAtLeast(1)
        val cornerDistance = (1f - normalizedX) + normalizedY
        val currentCornerDistance = (1f - currentX) + currentY
        return cornerDistance < currentCornerDistance
    }

    private fun quadrantFor(normalizedX: Float, normalizedY: Float): RuleRegion {
        return when {
            normalizedX < 0.5f && normalizedY < 0.5f -> RuleRegion.TOP_LEFT
            normalizedX >= 0.5f && normalizedY < 0.5f -> RuleRegion.TOP_RIGHT
            normalizedX < 0.5f -> RuleRegion.BOTTOM_LEFT
            else -> RuleRegion.BOTTOM_RIGHT
        }
    }

    private fun learningScore(
        text: String?,
        description: String?,
        viewId: String?,
        areaRatio: Float,
        normalizedX: Float,
        normalizedY: Float,
        clickable: Boolean,
        parentClickable: Boolean
    ): Int {
        var score = 0
        if (!text.isNullOrBlank()) score += if (text.length <= 24) 40 else 25
        if (!description.isNullOrBlank()) score += 30
        if (!viewId.isNullOrBlank()) score += 25
        score += when {
            clickable -> 35
            parentClickable -> 18
            else -> 0
        }
        score += when {
            areaRatio <= 0.01f -> 25
            areaRatio <= 0.05f -> 18
            areaRatio > 0.35f -> -25
            else -> 5
        }
        if ((normalizedX < 0.2f || normalizedX > 0.8f) &&
            (normalizedY < 0.2f || normalizedY > 0.8f)
        ) {
            score += 18
        }
        if (normalizedX > 0.5f && normalizedY < 0.5f) score += 10
        return score
    }

    private fun String?.limited(): String? {
        return this?.take(MAX_CAPTURED_TEXT_LENGTH)?.takeIf(String::isNotBlank)
    }

    companion object {
        private const val MAX_VISITED_NODES = 400
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_PARENT_DEPTH = 4
        private const val MAX_LEARNING_CANDIDATES = 80
        private const val MAX_CAPTURED_TEXT_LENGTH = 120
    }

    private data class PreparedRule(val rule: RecognitionRule, val regex: Regex?)

    private data class PreparedMatch(
        val rule: RecognitionRule,
        val value: String,
        val matchScore: Int
    )
}
