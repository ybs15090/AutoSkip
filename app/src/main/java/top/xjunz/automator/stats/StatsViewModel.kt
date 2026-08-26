package top.xjunz.automator.stats

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.invoke
import kotlinx.coroutines.launch
import top.xjunz.automator.app.AutomatorViewModel
import top.xjunz.automator.model.Record
import top.xjunz.automator.stats.model.RecordWrapper
import top.xjunz.automator.stats.model.SortBy
import top.xjunz.automator.util.Records
import java.io.FileDescriptor

/**
 * @author xjunz 2021/8/11
 */
class StatsViewModel : ViewModel() {
    companion object {
        @JvmStatic
        private var sortBy: SortBy = SortBy.Count
    }

    private val automatorViewModel by lazy {
        AutomatorViewModel.get()
    }
    val recordList = MutableLiveData<MutableList<RecordWrapper>>()

    fun requireRecords() = recordList.value!!

    private fun initRecords(list: MutableList<RecordWrapper>) {
        sort(list)
        recordList.value = list
    }

    private fun wrapRecords(source: MutableList<Record>?): MutableList<RecordWrapper>? {
        return source?.map { RecordWrapper(it) }?.toMutableList()
    }

    fun readRecordsWhenNecessary(fallbackFd: FileDescriptor) {
        if (recordList.value != null) {
            return
        }
        viewModelScope.launch {
            val records = wrapRecords(automatorViewModel.getRecordListFromRemote())
            if (records != null) {
                initRecords(records)
            } else {
                runCatching {
                    Dispatchers.IO.invoke {
                        Records(fallbackFd).parse()
                    }
                }.onSuccess {
                    initRecords(wrapRecords(it.asList())!!)
                }.onFailure {
                    it.printStackTrace()
                }
            }
        }
    }

    fun setSortBy(what: SortBy) {
        sortBy = what
        updateList()
    }

    fun getSortBy() = sortBy

    fun revertOrder() {
        sortBy.revertOrder()
        updateList()
    }

    private fun sort(data: MutableList<RecordWrapper>) {
        val order = sortBy.order
        when (sortBy) {
            SortBy.Label -> data.sortWith { o1, o2 -> order * o1.getComparatorLabel().compareTo(o2.getComparatorLabel()) }
            SortBy.Frequency -> data.sortWith { o1, o2 -> order * o1.getFrequencyPerDay().compareTo(o2.getFrequencyPerDay()) }
            SortBy.LatestTimestamp -> data.sortWith { o1, o2 -> order * o1.source.latestTimestamp.compareTo(o2.source.latestTimestamp) }
            SortBy.FirstTimestamp -> data.sortWith { o1, o2 -> order * o1.source.firstTimestamp.compareTo(o2.source.firstTimestamp) }
            SortBy.Count -> data.sortWith { o1, o2 -> order * o1.source.count.compareTo(o2.source.count) }
        }
    }

    private fun updateList() = viewModelScope.launch {
        val data = requireRecords()
        Dispatchers.Default.invoke {
            sort(data)
        }
        recordList.value = data
    }

    fun deleteRecord(packageName: String, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val successful = automatorViewModel.deleteRecord(packageName)
            if (successful) {
                recordList.value = requireRecords()
                    .filterNot { it.source.pkgName == packageName }
                    .toMutableList()
            }
            onComplete(successful)
        }
    }

    fun clearRecords(onComplete: (Boolean) -> Unit) {
        viewModelScope.launch {
            val successful = automatorViewModel.clearRecords()
            if (successful) {
                recordList.value = mutableListOf()
            }
            onComplete(successful)
        }
    }
}
