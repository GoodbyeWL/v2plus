package com.v2plus.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.databinding.ActivityBypassListBinding
import com.v2plus.app.dto.AppInfo
import com.v2plus.app.extension.toast
import com.v2plus.app.extension.toastSuccess
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.util.AppManagerUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator

/**
 * Lets the user pick which apps should trigger VPN auto-pause.
 * Stores to [AppConfig.PREF_VPN_AUTO_PAUSE_APPS].
 */
class VpnPauseAppsActivity : BaseActivity() {
    private val binding by lazy { ActivityBypassListBinding.inflate(layoutInflater) }

    private var adapter: VpnPauseAppsAdapter? = null
    private var appsAll: List<AppInfo>? = null
    private val selected: MutableSet<String> = HashSet()
    private var showSystemApps = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.title_vpn_auto_pause_apps))

        addCustomDividerToRecyclerView(binding.recyclerView, this, R.drawable.custom_divider)

        selected.addAll(
            MmkvManager.decodeSettingsStringSet(AppConfig.PREF_VPN_AUTO_PAUSE_APPS) ?: emptySet()
        )

        showSystemApps = MmkvManager.decodeSettingsBool(AppConfig.PREF_SHOW_SYSTEM_APPS, false)
        binding.switchShowSystemApps.isChecked = showSystemApps

        binding.switchPerAppProxy.setText(R.string.title_pref_vpn_auto_pause)
        binding.switchPerAppProxy.isChecked = MmkvManager.decodeSettingsBool(AppConfig.PREF_VPN_AUTO_PAUSE, false)
        binding.switchPerAppProxy.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(AppConfig.PREF_VPN_AUTO_PAUSE, isChecked)
        }

        binding.containerBypassApps.visibility = android.view.View.GONE

        binding.switchShowSystemApps.setOnCheckedChangeListener { _, isChecked ->
            showSystemApps = isChecked
            MmkvManager.encodeSettings(AppConfig.PREF_SHOW_SYSTEM_APPS, isChecked)
            loadApps()
        }

        loadApps()
    }

    private fun loadApps() {
        showLoading()
        lifecycleScope.launch {
            try {
                val apps = withContext(Dispatchers.IO) {
                    val list = AppManagerUtil.loadNetworkAppList(this@VpnPauseAppsActivity)
                        .filter { showSystemApps || !it.isSystemApp }

                    list.forEach { app ->
                        app.isSelected = if (selected.contains(app.packageName)) 1 else 0
                    }
                    list.sortedWith { p1, p2 ->
                        when {
                            p1.isSelected > p2.isSelected -> -1
                            p1.isSelected < p2.isSelected -> 1
                            else -> {
                                val collator = Collator.getInstance()
                                collator.compare(p1.appName, p2.appName)
                            }
                        }
                    }
                }
                appsAll = apps
                adapter = VpnPauseAppsAdapter(apps, selected, ::onToggle)
                binding.recyclerView.adapter = adapter
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error loading apps for VPN pause", e)
            } finally {
                hideLoading()
            }
        }
    }

    private fun onToggle(packageName: String, checked: Boolean) {
        if (checked) selected.add(packageName) else selected.remove(packageName)
        save()
    }

    private fun save() {
        MmkvManager.encodeSettings(AppConfig.PREF_VPN_AUTO_PAUSE_APPS, selected)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bypass_list, menu)

        val searchItem = menu.findItem(R.id.search_view)
        if (searchItem != null) {
            val searchView = searchItem.actionView as SearchView
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String?) = false
                override fun onQueryTextChange(newText: String?): Boolean {
                    filterApps(newText.orEmpty())
                    return false
                }
            })
        }
        menu.findItem(R.id.select_all)?.isVisible = false
        menu.findItem(R.id.invert_selection)?.isVisible = false
        menu.findItem(R.id.select_proxy_app)?.isVisible = false
        menu.findItem(R.id.import_proxy_app)?.isVisible = false
        menu.findItem(R.id.export_proxy_app)?.isVisible = false
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = super.onOptionsItemSelected(item)

    @SuppressLint("NotifyDataSetChanged")
    private fun filterApps(query: String) {
        val key = query.uppercase()
        val filtered = if (key.isEmpty()) {
            appsAll ?: emptyList()
        } else {
            appsAll?.filter {
                it.appName.uppercase().contains(key) || it.packageName.uppercase().contains(key)
            } ?: emptyList()
        }
        adapter = VpnPauseAppsAdapter(filtered, selected, ::onToggle)
        binding.recyclerView.adapter = adapter
    }
}
