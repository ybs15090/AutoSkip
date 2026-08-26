package top.xjunz.automator.stats

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.databinding.DataBindingUtil
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch
import top.xjunz.automator.R
import top.xjunz.automator.app.RECORD_FILE_NAME
import top.xjunz.automator.databinding.ActivityStatsBinding
import top.xjunz.automator.databinding.ItemRecordBinding
import top.xjunz.automator.stats.model.RecordWrapper
import top.xjunz.automator.stats.model.SortBy
import top.xjunz.automator.util.desaturatedMyIconDrawable
import top.xjunz.automator.util.formatTime

/**
 * @author xjunz 2021/8/11
 */
class StatsActivity : AppCompatActivity() {
    private lateinit var binding: ActivityStatsBinding

    private val statsViewModel by lazy {
        ViewModelProvider(this).get(StatsViewModel::class.java)
    }

    private var adapter: RecordAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = DataBindingUtil.setContentView(this, R.layout.activity_stats)
        setSupportActionBar(binding.toolbar)
        supportActionBar!!.apply {
            setDisplayHomeAsUpEnabled(true)
        }
        binding.toolbar.setNavigationOnClickListener {
            finishAfterTransition()
        }

        statsViewModel.recordList.observe(this) { records ->
            updateList(records)
        }
        statsViewModel.readRecordsWhenNecessary(openFileInput(RECORD_FILE_NAME).fd)
    }

    private fun updateList(records: List<RecordWrapper>) {
        if (adapter == null) {
            adapter = RecordAdapter()
            binding.rvRecord.adapter = adapter
        }
        adapter!!.submitList(records)
        binding.tvEmptyRecords.isVisible = records.isEmpty()
        invalidateOptionsMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.stats, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        val sortBy = statsViewModel.getSortBy()
        menu?.findItem(R.id.item_ascending)?.isChecked = sortBy.isAscending()
        menu?.findItem(sortBy.menuItemId)?.isChecked = true
        menu?.findItem(R.id.item_clear_records)?.isEnabled =
            statsViewModel.recordList.value?.isNotEmpty() == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.item_clear_records -> {
                confirmClearRecords()
                true
            }
            R.id.item_ascending -> {
                statsViewModel.revertOrder()
                true
            }
            R.id.item_by_count -> {
                statsViewModel.setSortBy(SortBy.Count)
                true
            }
            R.id.item_by_freq -> {
                statsViewModel.setSortBy(SortBy.Frequency)
                true
            }
            R.id.item_by_latest_timestamp -> {
                statsViewModel.setSortBy(SortBy.LatestTimestamp)
                true
            }
            R.id.item_by_first_timestamp -> {
                statsViewModel.setSortBy(SortBy.FirstTimestamp)
                true
            }
            R.id.item_by_label -> {
                statsViewModel.setSortBy(SortBy.Label)
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun confirmClearRecords() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.clear_all_records_title)
            .setMessage(R.string.clear_all_records_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.clear_all_records) { _, _ ->
                statsViewModel.clearRecords { successful ->
                    if (successful) {
                        toast(getString(R.string.records_cleared))
                    } else {
                        toast(getString(R.string.record_delete_failed))
                    }
                }
            }
            .show()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }


    inner class RecordAdapter : RecyclerView.Adapter<RecordAdapter.RecordViewHolder>() {
        private var items = emptyList<RecordWrapper>()

        fun submitList(records: List<RecordWrapper>) {
            val oldItems = items
            val newItems = records.toList()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldItems.size

                override fun getNewListSize() = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].source.pkgName ==
                            newItems[newItemPosition].source.pkgName
                }

                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].source.toString() ==
                            newItems[newItemPosition].source.toString()
                }
            })
            items = newItems
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecordViewHolder {
            return RecordViewHolder(ItemRecordBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: RecordViewHolder, position: Int) {
            holder.binding.run {
                val record = items[position]
                statsViewModel.viewModelScope.launch {
                    val icon = record.loadIcon()
                    if (icon != null) {
                        ivAppIcon.setImageBitmap(icon)
                    } else {
                        ivAppIcon.setImageDrawable(desaturatedMyIconDrawable)
                    }
                    val label = record.getLabel()
                    if (label.isNullOrBlank()) {
                        tvAppName.text = getString(R.string.unknown_app)
                    } else {
                        tvAppName.text = label
                    }
                }
                tvCount.text = getString(R.string.format_count, record.source.count)
                tvTimestamp.text = formatTime(record.source.latestTimestamp)
            }
        }

        private val detailViewModel by lazy {
            ViewModelProvider(this@StatsActivity).get(DetailFragment.DetailViewModel::class.java)
        }

        inner class RecordViewHolder constructor(val binding: ItemRecordBinding) : RecyclerView.ViewHolder(binding.root) {
            init {
                binding.root.setOnClickListener {
                    if (adapterPosition == RecyclerView.NO_POSITION) return@setOnClickListener
                    detailViewModel.apply {
                        appIcon = binding.ivAppIcon.drawable
                        appName = binding.tvAppName.text
                        setRecordWrapper(items[adapterPosition])
                    }
                    DetailFragment().show(supportFragmentManager, "detail")
                }
            }
        }

        override fun getItemCount() = items.size
    }
}
