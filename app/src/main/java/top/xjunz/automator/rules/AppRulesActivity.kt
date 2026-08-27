package top.xjunz.automator.rules

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.databinding.ActivityAppRulesBinding
import top.xjunz.automator.databinding.ItemAppRuleBinding
import java.text.Collator

class AppRulesActivity : AppCompatActivity() {
    private lateinit var binding: ActivityAppRulesBinding
    private val viewModel by lazy { AutomatorViewModel.get() }
    private val appAdapter = AppRuleAdapter(
        onRuleChanged = { packageName, enabled ->
            AppRulePreferences.setPackageEnabled(packageName, enabled)
            viewModel.syncRuleConfiguration()
            refreshRuleSummary()
        },
        onRecognitionRules = { packageName, label ->
            startActivity(RecognitionRulesActivity.createIntent(this, packageName, label))
        }
    )
    private var installedApps = emptyList<AppEntry>()
    private var recognitionRuleCounts = emptyMap<String, Int>()
    private var disabledRecognitionRulePackages = emptySet<String>()
    private var updatingSwitches = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finishAfterTransition() }
        binding.rvApps.adapter = appAdapter
        binding.btnGlobalRecognitionRules.setOnClickListener {
            startActivity(RecognitionRulesActivity.createIntent(this, null, null))
        }
        initRuleControls()
        initSearch()
        loadApplications()
    }

    override fun onResume() {
        super.onResume()
        val recognitionConfiguration = RecognitionRulePreferences.snapshot()
        recognitionRuleCounts = recognitionConfiguration.applicationRules
            .mapValues { it.value.size }
        disabledRecognitionRulePackages = recognitionConfiguration.disabledApplicationRulePackages
        if (appAdapter.itemCount > 0) {
            appAdapter.notifyItemRangeChanged(0, appAdapter.itemCount)
        }
    }

    private fun initRuleControls() {
        refreshRuleSummary()
        binding.switchRulesEnabled.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            AppRulePreferences.setEnabled(checked)
            viewModel.syncRuleConfiguration()
            refreshRuleSummary()
        }
        binding.switchStrictMode.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            AppRulePreferences.setStrictMode(checked)
            viewModel.syncRuleConfiguration()
            refreshRuleSummary()
        }
        binding.switchSingleClickLimit.setOnCheckedChangeListener { _, checked ->
            if (updatingSwitches) return@setOnCheckedChangeListener
            AppRulePreferences.setSingleClickLimitEnabled(checked)
            viewModel.syncRuleConfiguration()
            refreshRuleSummary()
        }
    }

    private fun refreshRuleSummary() {
        val rules = AppRulePreferences.snapshot()
        updatingSwitches = true
        binding.switchRulesEnabled.isChecked = rules.enabled
        binding.switchStrictMode.isChecked = rules.strictMode
        binding.switchStrictMode.isEnabled = rules.enabled
        binding.switchSingleClickLimit.isChecked = rules.singleClickLimitEnabled
        updatingSwitches = false

        binding.tvModeHint.setText(
            when {
                !rules.enabled -> R.string.application_rules_disabled_hint
                rules.strictMode -> R.string.strict_mode_hint
                else -> R.string.normal_mode_hint
            }
        )
        appAdapter.controlsEnabled = rules.enabled
        if (appAdapter.itemCount > 0) {
            appAdapter.notifyItemRangeChanged(0, appAdapter.itemCount)
        }
    }

    private fun initSearch() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterApplications(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }

    private fun filterApplications(query: String) {
        val keyword = query.trim()
        appAdapter.submitItems(if (keyword.isEmpty()) {
            installedApps
        } else {
            installedApps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.info.packageName.contains(keyword, ignoreCase = true)
            }
        })
    }

    private fun loadApplications() = lifecycleScope.launch {
        binding.progressLoading.visibility = View.VISIBLE
        installedApps = withContext(Dispatchers.IO) {
            val collator = Collator.getInstance(resources.configuration.locale)
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.enabled && it.packageName != packageName }
                .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
                .map {
                    AppEntry(
                        info = it,
                        label = runCatching { it.loadLabel(packageManager).toString() }
                            .getOrDefault(it.packageName)
                    )
                }
                .sortedWith { first, second -> collator.compare(first.label, second.label) }
                .toList()
        }
        binding.progressLoading.visibility = View.GONE
        filterApplications(binding.etSearch.text?.toString().orEmpty())
    }

    private data class AppEntry(val info: ApplicationInfo, val label: String)

    private inner class AppRuleAdapter(
        private val onRuleChanged: (String, Boolean) -> Unit,
        private val onRecognitionRules: (String, String) -> Unit
    ) : RecyclerView.Adapter<AppRuleAdapter.AppRuleViewHolder>() {
        private var items: List<AppEntry> = emptyList()
        var controlsEnabled: Boolean = true

        fun submitItems(newItems: List<AppEntry>) {
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].info.packageName ==
                        newItems[newItemPosition].info.packageName
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition] == newItems[newItemPosition]
                }
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): AppRuleViewHolder {
            return AppRuleViewHolder(ItemAppRuleBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AppRuleViewHolder, position: Int) {
            val entry = items[position]
            holder.binding.apply {
                tvAppName.text = entry.label
                tvPackageName.text = entry.info.packageName
                ivAppIcon.setImageDrawable(
                    runCatching { entry.info.loadIcon(packageManager) }
                        .getOrDefault(packageManager.defaultActivityIcon)
                )
                switchAppEnabled.setOnCheckedChangeListener(null)
                switchAppEnabled.isChecked = AppRulePreferences.isPackageSelected(entry.info.packageName)
                switchAppEnabled.isEnabled = controlsEnabled
                val contentAlpha = if (controlsEnabled) 1f else 0.55f
                ivAppIcon.alpha = contentAlpha
                tvAppName.alpha = contentAlpha
                tvPackageName.alpha = contentAlpha
                switchAppEnabled.alpha = contentAlpha
                switchAppEnabled.setOnCheckedChangeListener { _, checked ->
                    onRuleChanged(entry.info.packageName, checked)
                }
                val recognitionRuleCount = recognitionRuleCounts[entry.info.packageName] ?: 0
                btnRecognitionRules.text = when {
                    recognitionRuleCount == 0 -> getString(R.string.recognition_rules)
                    entry.info.packageName in disabledRecognitionRulePackages -> getString(
                        R.string.format_disabled_recognition_rule_count,
                        recognitionRuleCount
                    )
                    else -> getString(R.string.format_recognition_rule_count, recognitionRuleCount)
                }
                btnRecognitionRules.setOnClickListener {
                    onRecognitionRules(entry.info.packageName, entry.label)
                }
                root.setOnClickListener {
                    if (controlsEnabled) switchAppEnabled.toggle()
                }
            }
        }

        override fun getItemCount() = items.size

        inner class AppRuleViewHolder(val binding: ItemAppRuleBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
