package com.v2plus.app.ui

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v2plus.app.R
import com.v2plus.app.dto.ServersCache
import com.v2plus.app.handler.MmkvManager

class BridgeActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var switchBridge: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchAutoBridge: com.google.android.material.materialswitch.MaterialSwitch
    private var adapter: BridgeAdapter? = null
    
    private val PREF_BRIDGE_ENABLED = "pref_bridge_enabled"
    private val PREF_GLOBAL_EXIT_NODE = "pref_global_exit_node"

    private var allServers: List<ServersCache> = emptyList()
    private var filteredServers: MutableList<ServersCache> = mutableListOf()
    private var currentKeyword = ""
    private var currentGroupId = "all"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_bridge)

        val toolbar = findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.drawer_bridge)

        recyclerView = findViewById(R.id.recycler_view)
        switchBridge = findViewById(R.id.switch_bridge)
        switchAutoBridge = findViewById(R.id.switch_auto_bridge)

        val isEnabled = MmkvManager.decodeSettingsBool(PREF_BRIDGE_ENABLED, false)
        switchBridge.isChecked = isEnabled
        switchBridge.setOnCheckedChangeListener { _, isChecked ->
            MmkvManager.encodeSettings(PREF_BRIDGE_ENABLED, isChecked)
        }

        val globalNode = MmkvManager.decodeSettingsString(PREF_GLOBAL_EXIT_NODE, "") ?: ""
        val isAuto = globalNode.startsWith("auto:")
        switchAutoBridge.isChecked = isAuto

        switchAutoBridge.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                MmkvManager.encodeSettings(PREF_GLOBAL_EXIT_NODE, "auto:$currentGroupId")
                recyclerView.alpha = 0.3f
                recyclerView.isEnabled = false
            } else {
                val currentSelection = adapter?.selectedGuid ?: ""
                MmkvManager.encodeSettings(PREF_GLOBAL_EXIT_NODE, currentSelection)
                recyclerView.alpha = 1.0f
                recyclerView.isEnabled = true
            }
        }

        try {
            allServers = MmkvManager.decodeAllServerList().mapNotNull { guid ->
                val profile = MmkvManager.decodeServerConfig(guid) ?: return@mapNotNull null
                ServersCache(guid, profile)
            }
            filteredServers.addAll(allServers)
        } catch (e: Exception) {
            e.printStackTrace()
            allServers = emptyList()
        }

        adapter = BridgeAdapter(filteredServers, if (isAuto) "" else globalNode) { guid ->
            if (!switchAutoBridge.isChecked) {
                MmkvManager.encodeSettings(PREF_GLOBAL_EXIT_NODE, guid)
            }
        }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        if (isAuto) {
            recyclerView.alpha = 0.3f
            recyclerView.isEnabled = false
        }
        
        setupFilters()
    }
    
    private fun setupFilters() {
        val spinner = findViewById<Spinner>(R.id.spinner_subscription)
        val etSearch = findViewById<EditText>(R.id.et_search)

        try {
            val subscriptions = MmkvManager.decodeSubscriptions()
            val groupNames = mutableListOf(getString(R.string.filter_config_all), "default")
            val groupIds = mutableListOf("all", "")
            
            val groupedSubscriptions = subscriptions.groupBy { it.subscription?.group ?: "" }
            groupedSubscriptions.forEach { (groupName, subs) ->
                if (groupName.isNotEmpty()) {
                    groupNames.add(groupName)
                    groupIds.add("group:$groupName")
                }
                subs.forEach { sub ->
                    groupNames.add(" - ${sub.subscription?.remarks ?: ""}")
                    groupIds.add(sub.guid)
                }
            }

            spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, groupNames)
            spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    currentGroupId = groupIds[position]
                    applyFilters()
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }

            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    currentKeyword = s?.toString() ?: ""
                    applyFilters()
                }
            })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun applyFilters() {
        try {
            filteredServers.clear()
            
            val filteredByGroup = if (currentGroupId == "all") {
                allServers
            } else if (currentGroupId == "") {
                allServers.filter { it.profile.subscriptionId.isEmpty() }
            } else if (currentGroupId.startsWith("group:")) {
                val gName = currentGroupId.substring(6)
                val subIds = MmkvManager.decodeSubscriptions().filter { it.subscription?.group == gName }.map { it.guid }
                allServers.filter { subIds.contains(it.profile.subscriptionId) }
            } else {
                allServers.filter { it.profile.subscriptionId == currentGroupId }
            }

            if (currentKeyword.isBlank()) {
                filteredServers.addAll(filteredByGroup)
            } else {
                val keyword = currentKeyword.lowercase()
                filteredServers.addAll(filteredByGroup.filter {
                    it.profile.remarks.lowercase().contains(keyword) || 
                    (it.profile.server?.lowercase()?.contains(keyword) == true)
                })
            }
            
            if (switchAutoBridge.isChecked) {
                MmkvManager.encodeSettings(PREF_GLOBAL_EXIT_NODE, "auto:$currentGroupId")
            }
            
            adapter?.notifyDataSetChanged()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    private inner class BridgeAdapter(
        private val items: List<ServersCache>,
        var selectedGuid: String,
        private val onSelect: (String) -> Unit
    ) : RecyclerView.Adapter<BridgeAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val radio: RadioButton = view.findViewById(R.id.radio_selected)
            val name: TextView = view.findViewById(R.id.tv_name)
            val address: TextView = view.findViewById(R.id.tv_address)
            
            init {
                view.setOnClickListener {
                    val prevPos = items.indexOfFirst { it.guid == selectedGuid }
                    selectedGuid = items[adapterPosition].guid
                    onSelect(selectedGuid)
                    if (prevPos >= 0) notifyItemChanged(prevPos)
                    notifyItemChanged(adapterPosition)
                }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_bridge_server, parent, false)
            return ViewHolder(view)
        }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.radio.isChecked = item.guid == selectedGuid
        holder.name.text = item.profile.remarks ?: ""
        holder.address.text = item.profile.server ?: ""
    }

        override fun getItemCount() = items.size
    }
}
