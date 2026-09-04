package top.xjunz.automator.rules

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.xjunz.automator.R
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.databinding.ActivityBlacklistBinding
import top.xjunz.automator.databinding.ItemBlacklistedAppBinding
import java.text.Collator

class BlacklistActivity : AppCompatActivity() {
    private lateinit var binding: ActivityBlacklistBinding
    private val viewModel by lazy { AutomatorViewModel.get() }
    private val blacklistAdapter = BlacklistAdapter(::removeFromBlacklist)
    private var loadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlacklistBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finishAfterTransition() }
        binding.rvBlacklist.adapter = blacklistAdapter
    }

    override fun onResume() {
        super.onResume()
        loadBlacklist()
    }

    override fun onDestroy() {
        loadJob?.cancel()
        super.onDestroy()
    }

    private fun loadBlacklist() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            binding.progressLoading.visibility = View.VISIBLE
            val blacklistedPackages = AppRulePreferences.snapshot().blacklist
            val entries = withContext(Dispatchers.IO) {
                val collator = Collator.getInstance(resources.configuration.locale)
                blacklistedPackages.map { packageName ->
                    val info = runCatching {
                        packageManager.getApplicationInfo(packageName, 0)
                    }.getOrNull()
                    val label = info?.let {
                        runCatching { it.loadLabel(packageManager).toString() }.getOrNull()
                    }.orEmpty()
                    BlacklistedAppEntry(packageName, label, info)
                }.sortedWith { first, second ->
                    val firstLabel = first.label.ifBlank { first.packageName }
                    val secondLabel = second.label.ifBlank { second.packageName }
                    collator.compare(firstLabel, secondLabel)
                }
            }
            binding.progressLoading.visibility = View.GONE
            blacklistAdapter.submitItems(entries)
            binding.tvEmptyBlacklist.isVisible = entries.isEmpty()
        }
    }

    private fun removeFromBlacklist(entry: BlacklistedAppEntry) {
        val previousState = AppRulePreferences.getPackageRuleState(entry.packageName)
        AppRulePreferences.removeFromBlacklist(entry.packageName)
        viewModel.syncRuleConfiguration()
        blacklistAdapter.removePackage(entry.packageName)
        binding.tvEmptyBlacklist.isVisible = blacklistAdapter.itemCount == 0
        val displayName = entry.label.ifBlank { entry.packageName }
        Snackbar.make(
            binding.root,
            getString(R.string.format_removed_from_blacklist, displayName),
            Snackbar.LENGTH_LONG
        ).setAction(R.string.undo) {
            AppRulePreferences.restorePackageRule(entry.packageName, previousState)
            viewModel.syncRuleConfiguration()
            loadBlacklist()
        }.show()
    }

    private data class BlacklistedAppEntry(
        val packageName: String,
        val label: String,
        val info: ApplicationInfo?
    )

    private inner class BlacklistAdapter(
        private val onRemove: (BlacklistedAppEntry) -> Unit
    ) : RecyclerView.Adapter<BlacklistAdapter.BlacklistViewHolder>() {
        private var items = emptyList<BlacklistedAppEntry>()

        fun submitItems(newItems: List<BlacklistedAppEntry>) {
            val oldItems = items
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size
                override fun getNewListSize() = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].packageName ==
                        newItems[newItemPosition].packageName
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].label == newItems[newItemPosition].label &&
                        (oldItems[oldItemPosition].info != null) ==
                        (newItems[newItemPosition].info != null)
                }
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        fun removePackage(packageName: String) {
            submitItems(items.filterNot { it.packageName == packageName })
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlacklistViewHolder {
            return BlacklistViewHolder(
                ItemBlacklistedAppBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: BlacklistViewHolder, position: Int) {
            val entry = items[position]
            holder.binding.apply {
                tvAppName.text = entry.label.ifBlank { getString(R.string.uninstalled_app) }
                tvPackageName.text = entry.packageName
                ivAppIcon.setImageDrawable(
                    entry.info?.let {
                        runCatching { it.loadIcon(packageManager) }.getOrNull()
                    } ?: packageManager.defaultActivityIcon
                )
                btnRemoveFromBlacklist.setOnClickListener { onRemove(entry) }
            }
        }

        override fun getItemCount() = items.size

        inner class BlacklistViewHolder(val binding: ItemBlacklistedAppBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
