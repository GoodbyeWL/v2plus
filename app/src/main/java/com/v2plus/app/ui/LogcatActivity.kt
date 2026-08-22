package com.v2plus.app.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2plus.app.R
import com.v2plus.app.databinding.ActivityLogcatBinding
import com.v2plus.app.extension.toast
import com.v2plus.app.extension.toastSuccess
import com.v2plus.app.util.Utils
import com.v2plus.app.viewmodel.LogcatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogcatActivity : BaseActivity(), SwipeRefreshLayout.OnRefreshListener {
    private val binding by lazy { ActivityLogcatBinding.inflate(layoutInflater) }
    private val viewModel: LogcatViewModel by viewModels()
    private lateinit var adapter: LogcatRecyclerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_logcat))

        adapter = LogcatRecyclerAdapter(viewModel, ::onLogLongClick)

        binding.recyclerView.setHasFixedSize(true)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        binding.refreshLayout.setOnRefreshListener(this)

        toast(getString(R.string.pull_down_to_refresh))
    }

    private fun onLogLongClick(log: String): Boolean {
        val parts = log.split("):", limit = 2)
        val tag = parts.firstOrNull()?.split("(", limit = 2)?.firstOrNull()?.trim().orEmpty()
        val message = if (parts.size > 1) parts.last().trim() else ""

        val items = arrayOf(
            getString(R.string.logcat_copy_line),
            getString(R.string.logcat_copy_message)
        )
        AlertDialog.Builder(this)
            .setTitle(tag.ifBlank { getString(R.string.title_logcat) })
            .setItems(items) { _, which ->
                when (which) {
                    0 -> Utils.setClipboard(this, log)
                    1 -> Utils.setClipboard(this, message.ifBlank { log })
                }
                toastSuccess(R.string.toast_success)
            }
            .show()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_logcat, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?): Boolean = false

                override fun onQueryTextChange(newText: String?): Boolean {
                    viewModel.filter(newText)
                    refreshData()
                    return false
                }
            })
            searchView.setOnCloseListener {
                viewModel.filter("")
                refreshData()
                false
            }
        }

        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.copy_all -> {
            val all = viewModel.getAll().joinToString("\n")
            Utils.setClipboard(this, all)
            toastSuccess(R.string.toast_success)
            true
        }

        R.id.copy_filtered -> {
            val text = viewModel.getFilteredText()
            if (text.isBlank()) {
                toast(R.string.toast_none_data)
            } else {
                Utils.setClipboard(this, text)
                toastSuccess(R.string.toast_success)
            }
            true
        }

        R.id.copy_latest_crash -> {
            val crash = viewModel.getLatestCrashBlock()
            if (crash.isNullOrBlank()) {
                toast(R.string.logcat_no_crash_found)
            } else {
                Utils.setClipboard(this, crash)
                toastSuccess(R.string.toast_success)
            }
            true
        }

        R.id.copy_latest_errors -> {
            val err = viewModel.getLatestErrorsText()
            if (err.isNullOrBlank()) {
                toast(R.string.logcat_no_errors_found)
            } else {
                Utils.setClipboard(this, err)
                toastSuccess(R.string.toast_success)
            }
            true
        }

        R.id.share_filtered -> {
            val text = viewModel.getFilteredText()
            if (text.isBlank()) {
                toast(R.string.toast_none_data)
            } else {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.logcat_share_filtered)))
            }
            true
        }

        R.id.clear_all -> {
            lifecycleScope.launch(Dispatchers.IO) {
                viewModel.clearLogcat()
                withContext(Dispatchers.Main) {
                    refreshData()
                }
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    override fun onRefresh() {
        lifecycleScope.launch(Dispatchers.IO) {
            viewModel.loadLogcat()
            withContext(Dispatchers.Main) {
                binding.refreshLayout.isRefreshing = false
                refreshData()
            }
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    fun refreshData() {
        adapter.notifyDataSetChanged()
    }
}