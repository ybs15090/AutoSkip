package top.xjunz.automator.rules

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.databinding.ActivityRecognitionRulesBinding
import top.xjunz.automator.databinding.DialogEditRecognitionRuleBinding
import top.xjunz.automator.databinding.ItemRecognitionRuleBinding
import top.xjunz.automator.recognition.LearningCandidate
import top.xjunz.automator.recognition.RecognitionRule
import top.xjunz.automator.recognition.RuleClickMode
import top.xjunz.automator.recognition.RuleFeature
import top.xjunz.automator.recognition.RuleMatchMode
import top.xjunz.automator.recognition.RuleRegion
import java.util.UUID
import java.util.regex.Pattern

class RecognitionRulesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRecognitionRulesBinding
    private val viewModel by lazy { AutomatorViewModel.get() }
    private val packageNameForRules by lazy { intent.getStringExtra(EXTRA_PACKAGE_NAME) }
    private val appLabel by lazy {
        intent.getStringExtra(EXTRA_APP_LABEL) ?: packageNameForRules.orEmpty()
    }
    private val ruleAdapter = RuleAdapter(
        onEnabledChanged = ::setRuleEnabled,
        onEdit = { showRuleEditor(it) },
        onDelete = ::confirmDeleteRule
    )
    private var learningActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRecognitionRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        learningActive = savedInstanceState?.getBoolean(STATE_LEARNING_ACTIVE) == true
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finishAfterTransition() }
        binding.rvRules.adapter = ruleAdapter
        binding.btnAddRule.setOnClickListener { showRuleEditor(null) }
        configureScopeActions()
        refreshRules()
    }

    override fun onResume() {
        super.onResume()
        if (learningActive) finishLearningRound()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_LEARNING_ACTIVE, learningActive)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && learningActive) viewModel.stopRuleLearning()
        super.onDestroy()
    }

    private fun configureScopeActions() {
        if (packageNameForRules == null) {
            binding.toolbar.setTitle(R.string.global_recognition_rules)
            binding.tvScopeSummary.setText(R.string.global_rules_summary)
            binding.btnSecondaryAction.visibility = View.VISIBLE
            binding.btnSecondaryAction.setText(R.string.restore_default_rules)
            binding.btnSecondaryAction.setOnClickListener { confirmRestoreDefaults() }
        } else {
            binding.toolbar.title = appLabel
            binding.tvScopeSummary.text = getString(R.string.format_app_rules_summary, appLabel)
            binding.btnLearning.visibility = View.VISIBLE
            binding.btnLearning.setOnClickListener { explainLearningMode() }
        }
    }

    private fun refreshRules() {
        val rules = RecognitionRulePreferences.rulesForEditor(packageNameForRules)
        ruleAdapter.submitItems(rules)
        val applicationOverride = packageNameForRules?.let {
            RecognitionRulePreferences.hasApplicationOverride(it)
        } == true
        binding.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
        binding.tvEmptyRules.setText(
            if (packageNameForRules == null) R.string.no_recognition_rules
            else R.string.application_inherits_global_rules
        )
        if (packageNameForRules != null) {
            binding.btnSecondaryAction.visibility = if (applicationOverride) View.VISIBLE else View.GONE
            binding.btnSecondaryAction.setText(R.string.use_global_rules)
            binding.btnSecondaryAction.setOnClickListener { confirmUseGlobalRules() }
        }
    }

    private fun setRuleEnabled(rule: RecognitionRule, enabled: Boolean) {
        saveRule(rule.copy(enabled = enabled))
    }

    private fun saveRule(rule: RecognitionRule) {
        val rules = RecognitionRulePreferences.rulesForEditor(packageNameForRules).toMutableList()
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) rules[index] = rule else rules.add(rule)
        RecognitionRulePreferences.saveRules(packageNameForRules, rules)
        viewModel.syncRuleConfiguration()
        refreshRules()
    }

    private fun confirmDeleteRule(rule: RecognitionRule) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_rule_title)
            .setMessage(rule.pattern)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                val rules = RecognitionRulePreferences.rulesForEditor(packageNameForRules)
                    .filterNot { it.id == rule.id }
                RecognitionRulePreferences.saveRules(packageNameForRules, rules)
                viewModel.syncRuleConfiguration()
                refreshRules()
            }
            .show()
    }

    private fun confirmRestoreDefaults() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.restore_default_rules_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.restore_default_rules) { _, _ ->
                RecognitionRulePreferences.restoreDefaultGlobalRules()
                viewModel.syncRuleConfiguration()
                refreshRules()
            }
            .show()
    }

    private fun confirmUseGlobalRules() {
        val packageName = packageNameForRules ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.use_global_rules_title)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.use_global_rules) { _, _ ->
                RecognitionRulePreferences.removeApplicationOverride(packageName)
                viewModel.syncRuleConfiguration()
                refreshRules()
            }
            .show()
    }

    private fun showRuleEditor(rule: RecognitionRule?, learned: Boolean = false) {
        val initial = rule ?: RecognitionRule(
            id = UUID.randomUUID().toString(),
            pattern = "",
            feature = RuleFeature.TEXT,
            matchMode = RuleMatchMode.CONTAINS,
            region = RuleRegion.TOP_RIGHT,
            clickMode = RuleClickMode.AUTO
        )
        val editor = DialogEditRecognitionRuleBinding.inflate(layoutInflater)
        configureSpinner(editor.spinnerFeature, R.array.rule_features, initial.feature.ordinal)
        configureSpinner(editor.spinnerMatchMode, R.array.rule_match_modes, initial.matchMode.ordinal)
        configureSpinner(editor.spinnerRegion, R.array.rule_regions, initial.region.ordinal)
        configureSpinner(editor.spinnerClickMode, R.array.rule_click_modes, initial.clickMode.ordinal)
        editor.etPattern.setText(initial.pattern)
        editor.etDelay.setText(initial.delayMillis.toString())
        editor.checkIgnoreCase.isChecked = initial.ignoreCase
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                when {
                    learned -> R.string.confirm_learned_rule
                    rule == null -> R.string.add_rule
                    else -> R.string.edit_rule
                }
            )
            .setView(editor.root)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                editor.inputPattern.error = null
                editor.inputDelay.error = null
                val pattern = editor.etPattern.text?.toString()?.trim().orEmpty()
                val delay = editor.etDelay.text?.toString()?.toLongOrNull()
                val matchMode = RuleMatchMode.values()[editor.spinnerMatchMode.selectedItemPosition]
                when {
                    pattern.isEmpty() -> editor.inputPattern.setError(getString(R.string.pattern_required))
                    matchMode == RuleMatchMode.REGEX && runCatching { Regex(pattern) }.isFailure ->
                        editor.inputPattern.setError(getString(R.string.invalid_regex))
                    delay == null || delay !in 0L..RecognitionRule.MAX_DELAY_MILLIS ->
                        editor.inputDelay.setError(getString(R.string.invalid_delay))
                    else -> {
                        saveRule(
                            initial.copy(
                                feature = RuleFeature.values()[editor.spinnerFeature.selectedItemPosition],
                                matchMode = matchMode,
                                pattern = pattern,
                                ignoreCase = editor.checkIgnoreCase.isChecked,
                                region = RuleRegion.values()[editor.spinnerRegion.selectedItemPosition],
                                delayMillis = delay,
                                clickMode = RuleClickMode.values()[editor.spinnerClickMode.selectedItemPosition]
                            )
                        )
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun configureSpinner(spinner: Spinner, arrayResource: Int, selection: Int) {
        spinner.adapter = ArrayAdapter.createFromResource(
            this,
            arrayResource,
            android.R.layout.simple_spinner_item
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(selection)
    }

    private fun explainLearningMode() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rule_learning)
            .setMessage(R.string.rule_learning_intro)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.start_learning) { _, _ -> startLearningRound() }
            .show()
    }

    private fun startLearningRound() {
        val packageName = packageNameForRules ?: return
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        if (launchIntent == null) {
            toast(R.string.learning_launch_failed)
            return
        }
        if (!viewModel.startRuleLearning(packageName)) {
            toast(R.string.learning_service_required)
            return
        }
        learningActive = true
        startActivity(launchIntent)
    }

    private fun finishLearningRound() {
        learningActive = false
        val candidates = viewModel.getRuleLearningCandidates()
        viewModel.stopRuleLearning()
        if (candidates.isEmpty()) {
            toast(R.string.learning_no_candidates)
            return
        }
        val labels = candidates.map(::formatCandidate).toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_skip_candidate)
            .setNegativeButton(R.string.cancel, null)
            .setSingleChoiceItems(labels, -1) { dialog, index ->
                dialog.dismiss()
                showRuleEditor(suggestRule(candidates[index]), learned = true)
            }
            .show()
    }

    private fun formatCandidate(candidate: LearningCandidate): String {
        val features = mutableListOf<String>()
        candidate.text?.let { features.add(getString(R.string.candidate_text, it)) }
        candidate.contentDescription?.let {
            features.add(getString(R.string.candidate_description, it))
        }
        candidate.viewId?.let { features.add(getString(R.string.candidate_view_id, it)) }
        if (features.isEmpty()) {
            features.add(getString(R.string.candidate_class, candidate.className ?: "?"))
        }
        val clickability = when {
            candidate.clickable -> getString(R.string.clickable_control)
            candidate.parentClickable -> getString(R.string.parent_clickable_control)
            else -> getString(R.string.coordinate_candidate)
        }
        return getString(
            R.string.learning_candidate_summary,
            features.joinToString("\n"),
            resources.getStringArray(R.array.rule_regions)[candidate.region.ordinal],
            clickability,
            candidate.score
        )
    }

    private fun suggestRule(candidate: LearningCandidate): RecognitionRule {
        val candidateText = candidate.text
        val candidateDescription = candidate.contentDescription
        val candidateViewId = candidate.viewId
        val feature: RuleFeature
        val rawPattern: String
        when {
            !candidateText.isNullOrBlank() -> {
                feature = RuleFeature.TEXT
                rawPattern = candidateText
            }
            !candidateDescription.isNullOrBlank() -> {
                feature = RuleFeature.CONTENT_DESCRIPTION
                rawPattern = candidateDescription
            }
            !candidateViewId.isNullOrBlank() -> {
                feature = RuleFeature.VIEW_ID
                rawPattern = candidateViewId
            }
            else -> {
                feature = RuleFeature.CLASS_NAME
                rawPattern = candidate.className.orEmpty()
            }
        }
        val canGeneralizeNumbers = feature == RuleFeature.TEXT ||
            feature == RuleFeature.CONTENT_DESCRIPTION
        val generalizedPattern = if (canGeneralizeNumbers && rawPattern.any(Char::isDigit)) {
            generalizeNumbers(rawPattern)
        } else {
            rawPattern
        }
        return RecognitionRule(
            id = "learned_${UUID.randomUUID()}",
            enabled = true,
            feature = feature,
            matchMode = if (generalizedPattern != rawPattern) {
                RuleMatchMode.REGEX
            } else {
                RuleMatchMode.EXACT
            },
            pattern = generalizedPattern,
            ignoreCase = true,
            region = candidate.region,
            delayMillis = 0,
            clickMode = RuleClickMode.AUTO
        )
    }

    private fun generalizeNumbers(value: String): String {
        val digits = Regex("\\d+")
        val result = StringBuilder()
        var start = 0
        digits.findAll(value).forEach { match ->
            result.append(Pattern.quote(value.substring(start, match.range.first)))
            result.append("\\d+")
            start = match.range.last + 1
        }
        result.append(Pattern.quote(value.substring(start)))
        return result.toString()
    }

    private fun ruleSummary(rule: RecognitionRule): String {
        return listOf(
            resources.getStringArray(R.array.rule_features)[rule.feature.ordinal],
            resources.getStringArray(R.array.rule_match_modes)[rule.matchMode.ordinal],
            resources.getStringArray(R.array.rule_regions)[rule.region.ordinal],
            resources.getStringArray(R.array.rule_click_modes)[rule.clickMode.ordinal],
            getString(R.string.format_delay_millis, rule.delayMillis)
        ).joinToString(" · ")
    }

    private fun toast(messageResource: Int) {
        Toast.makeText(this, messageResource, Toast.LENGTH_LONG).show()
    }

    private inner class RuleAdapter(
        private val onEnabledChanged: (RecognitionRule, Boolean) -> Unit,
        private val onEdit: (RecognitionRule) -> Unit,
        private val onDelete: (RecognitionRule) -> Unit
    ) : RecyclerView.Adapter<RuleAdapter.RuleViewHolder>() {
        private var items = emptyList<RecognitionRule>()

        fun submitItems(newItems: List<RecognitionRule>) {
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(oldPosition: Int, newPosition: Int) =
                    oldItems[oldPosition].id == newItems[newPosition].id
                override fun areContentsTheSame(oldPosition: Int, newPosition: Int) =
                    oldItems[oldPosition] == newItems[newPosition]
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RuleViewHolder {
            return RuleViewHolder(
                ItemRecognitionRuleBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
            val rule = items[position]
            holder.binding.apply {
                switchRuleEnabled.setOnCheckedChangeListener(null)
                switchRuleEnabled.text = rule.pattern
                switchRuleEnabled.isChecked = rule.enabled
                switchRuleEnabled.setOnCheckedChangeListener { _, checked ->
                    onEnabledChanged(rule, checked)
                }
                tvRuleSummary.text = ruleSummary(rule)
                btnEditRule.setOnClickListener { onEdit(rule) }
                btnDeleteRule.setOnClickListener { onDelete(rule) }
                root.setOnClickListener { onEdit(rule) }
            }
        }

        override fun getItemCount() = items.size

        inner class RuleViewHolder(val binding: ItemRecognitionRuleBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val EXTRA_PACKAGE_NAME = "package_name"
        private const val EXTRA_APP_LABEL = "app_label"
        private const val STATE_LEARNING_ACTIVE = "learning_active"

        fun createIntent(context: Context, packageName: String?, appLabel: String?): Intent {
            return Intent(context, RecognitionRulesActivity::class.java).apply {
                putExtra(EXTRA_PACKAGE_NAME, packageName)
                putExtra(EXTRA_APP_LABEL, appLabel)
            }
        }
    }
}
