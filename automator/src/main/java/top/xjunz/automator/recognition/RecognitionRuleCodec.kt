package top.xjunz.automator.recognition

import org.json.JSONArray
import org.json.JSONObject

object RecognitionRuleCodec {
    private const val VERSION = 2

    fun encode(configuration: RecognitionConfiguration): String {
        val root = JSONObject()
            .put("version", VERSION)
            .put("globalRules", encodeRules(configuration.globalRules))
        val applications = JSONObject()
        configuration.applicationRules.toSortedMap().forEach { (packageName, rules) ->
            applications.put(packageName, encodeRules(rules))
        }
        root.put("applicationRules", applications)
        val disabledApplicationRulePackages = JSONArray()
        configuration.disabledApplicationRulePackages.sorted().forEach {
            disabledApplicationRulePackages.put(it)
        }
        root.put("disabledApplicationRulePackages", disabledApplicationRulePackages)
        return root.toString()
    }

    fun decode(json: String?): RecognitionConfiguration {
        if (json.isNullOrBlank()) return RecognitionConfiguration()
        return runCatching {
            val root = JSONObject(json)
            val globalRules = decodeRules(root.optJSONArray("globalRules"))
            val applicationRules = linkedMapOf<String, List<RecognitionRule>>()
            val applications = root.optJSONObject("applicationRules")
            applications?.keys()?.forEach { packageName ->
                applicationRules[packageName] = decodeRules(applications.optJSONArray(packageName))
            }
            val disabledApplicationRulePackages = linkedSetOf<String>()
            val disabledPackages = root.optJSONArray("disabledApplicationRulePackages")
            if (disabledPackages != null) {
                for (index in 0 until disabledPackages.length()) {
                    disabledPackages.optString(index).trim().takeIf { it.isNotEmpty() }?.let {
                        disabledApplicationRulePackages.add(it)
                    }
                }
            }
            disabledApplicationRulePackages.retainAll(
                applicationRules.filterValues { it.isNotEmpty() }.keys
            )
            RecognitionConfiguration(
                globalRules,
                applicationRules,
                disabledApplicationRulePackages
            )
        }.getOrDefault(RecognitionConfiguration())
    }

    fun encodeCandidates(candidates: Collection<LearningCandidate>): String {
        val array = JSONArray()
        candidates.forEach { candidate ->
            array.put(
                JSONObject()
                    .put("key", candidate.key)
                    .put("packageName", candidate.packageName)
                    .putNullable("text", candidate.text)
                    .putNullable("contentDescription", candidate.contentDescription)
                    .putNullable("viewId", candidate.viewId)
                    .putNullable("className", candidate.className)
                    .put("left", candidate.left)
                    .put("top", candidate.top)
                    .put("right", candidate.right)
                    .put("bottom", candidate.bottom)
                    .put("region", candidate.region.name)
                    .put("clickable", candidate.clickable)
                    .put("parentClickable", candidate.parentClickable)
                    .put("score", candidate.score)
                    .put("timestamp", candidate.timestamp)
            )
        }
        return array.toString()
    }

    fun decodeCandidates(json: String?): List<LearningCandidate> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            val candidates = mutableListOf<LearningCandidate>()
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                candidates.add(
                    LearningCandidate(
                        key = item.optString("key"),
                        packageName = item.optString("packageName"),
                        text = item.optNullableString("text"),
                        contentDescription = item.optNullableString("contentDescription"),
                        viewId = item.optNullableString("viewId"),
                        className = item.optNullableString("className"),
                        left = item.optInt("left"),
                        top = item.optInt("top"),
                        right = item.optInt("right"),
                        bottom = item.optInt("bottom"),
                        region = item.optEnum("region", RuleRegion.ANY),
                        clickable = item.optBoolean("clickable"),
                        parentClickable = item.optBoolean("parentClickable"),
                        score = item.optInt("score"),
                        timestamp = item.optLong("timestamp")
                    )
                )
            }
            candidates
        }.getOrDefault(emptyList())
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

    private fun decodeRules(array: JSONArray?): List<RecognitionRule> {
        if (array == null) return emptyList()
        val rules = mutableListOf<RecognitionRule>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val pattern = item.optString("pattern")
            if (id.isBlank() || pattern.isBlank()) continue
            rules.add(
                RecognitionRule(
                    id = id,
                    enabled = item.optBoolean("enabled", true),
                    feature = item.optEnum("feature", RuleFeature.TEXT),
                    matchMode = item.optEnum("matchMode", RuleMatchMode.CONTAINS),
                    pattern = pattern,
                    ignoreCase = item.optBoolean("ignoreCase", true),
                    region = item.optEnum("region", RuleRegion.TOP_RIGHT),
                    delayMillis = item.optLong("delayMillis").coerceIn(0, RecognitionRule.MAX_DELAY_MILLIS),
                    clickMode = item.optEnum("clickMode", RuleClickMode.AUTO)
                )
            )
        }
        return rules
    }

    private fun JSONObject.putNullable(key: String, value: String?): JSONObject {
        return put(key, value ?: JSONObject.NULL)
    }

    private fun JSONObject.optNullableString(key: String): String? {
        if (isNull(key)) return null
        return optString(key).takeIf { it.isNotBlank() }
    }

    private inline fun <reified T : Enum<T>> JSONObject.optEnum(key: String, fallback: T): T {
        return runCatching { enumValueOf<T>(optString(key)) }.getOrDefault(fallback)
    }
}
