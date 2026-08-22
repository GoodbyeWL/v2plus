package com.v2plus.app.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.contracts.MainAdapterListener
import com.v2plus.app.databinding.FragmentGroupServerBinding
import com.v2plus.app.databinding.ItemQrcodeBinding
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.dto.SubscriptionItem
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.extension.toast
import com.v2plus.app.extension.toastError
import com.v2plus.app.extension.toastSuccess
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.CustomizationManager
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.helper.SimpleItemTouchHelperCallback
import com.v2plus.app.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class GroupServerFragment : BaseFragment<FragmentGroupServerBinding>(),
    SwipeRefreshLayout.OnRefreshListener {
    private val ownerActivity: MainActivity
        get() = requireActivity() as MainActivity
    private val mainViewModel: MainViewModel by activityViewModels()
    private lateinit var adapter: MainRecyclerAdapter
    private var itemTouchHelper: ItemTouchHelper? = null
    private val subId: String by lazy { arguments?.getString(ARG_SUB_ID).orEmpty() }

    private val share_method: Array<out String> by lazy {
        ownerActivity.resources.getStringArray(R.array.share_method)
    }
    private val share_method_more: Array<out String> by lazy {
        ownerActivity.resources.getStringArray(R.array.share_method_more)
    }

    companion object {
        private const val ARG_SUB_ID = "subscriptionId"
        fun newInstance(subId: String) = GroupServerFragment().apply {
            arguments = Bundle().apply { putString(ARG_SUB_ID, subId) }
        }
    }

    override fun inflateBinding(inflater: LayoutInflater, container: ViewGroup?) =
        FragmentGroupServerBinding.inflate(inflater, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {

        adapter = MainRecyclerAdapter(mainViewModel, ActivityAdapterListener())
        binding.recyclerView.setHasFixedSize(true)
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)) {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 2)
        } else {
            binding.recyclerView.layoutManager = GridLayoutManager(requireContext(), 1)
        }
        addCustomDividerToRecyclerView(binding.recyclerView, R.drawable.custom_divider)
        binding.recyclerView.adapter = adapter

        itemTouchHelper = ItemTouchHelper(SimpleItemTouchHelperCallback(adapter, allowSwipe = false))
        itemTouchHelper?.attachToRecyclerView(binding.recyclerView)

        binding.refreshLayout.isEnabled = false
//        binding.refreshLayout.setOnRefreshListener(this)
//        // Set the distance to trigger sync to 160dp
//        binding.refreshLayout.setDistanceToTriggerSync((160 * resources.displayMetrics.density).toInt())

        // Init expand/collapse state
        val isExpanded = MmkvManager.decodeSettingsBool("pref_subscription_info_expanded", true)
        binding.layoutSubscriptionContent.isVisible = isExpanded
        binding.ivSubscriptionExpand.rotation = if (isExpanded) 0f else 180f

        binding.layoutSubscriptionHeader.setOnClickListener {
            val expanded = !binding.layoutSubscriptionContent.isVisible
            binding.layoutSubscriptionContent.isVisible = expanded
            binding.ivSubscriptionExpand.animate().rotation(if (expanded) 0f else 180f).setDuration(200).start()
            MmkvManager.encodeSettings("pref_subscription_info_expanded", expanded)
        }

        mainViewModel.updateListAction.observe(viewLifecycleOwner) { index ->
            // Only update if this fragment's subscription matches the current view
            // In "all" mode, all fragments with matching subId should update
            if (mainViewModel.subscriptionId != subId) {
                return@observe
            }
            
            // Save scroll state before update if it's a full reload (index < 0)
            var scrollState: android.os.Parcelable? = null
            if (index < 0) {
                scrollState = binding.recyclerView.layoutManager?.onSaveInstanceState()
            }
            
            // Log.d(TAG, "GroupServerFragment updateListAction subId=$subId")
            adapter.setData(mainViewModel.serversCache, index)
            
            // Restore scroll state if it was a full reload
            if (scrollState != null) {
                binding.recyclerView.layoutManager?.onRestoreInstanceState(scrollState)
            }
            
            // Update subscription info when server list updates
            updateSubscriptionInfo()
        }

        // Initial update
        updateSubscriptionInfo()

        // Log.d(TAG, "GroupServerFragment onViewCreated: subId=$subId")
    }

    /**
     * Shares server configuration
     * Displays a dialog with sharing options and executes the selected action
     * @param guid The server unique identifier
     * @param profile The server configuration
     * @param position The position in the list
     * @param shareOptions The list of share options
     * @param skip The number of options to skip
     */
    private fun shareServer(guid: String, profile: ProfileItem, position: Int, shareOptions: List<String>, skip: Int) {
        AlertDialog.Builder(ownerActivity).setItems(shareOptions.toTypedArray()) { _, i ->
            try {
                when (i + skip) {
                    0 -> showQRCode(guid)
                    1 -> share2Clipboard(guid)
                    2 -> shareFullContent(guid)
                    3 -> editServer(guid, profile)
                    4 -> removeServer(guid, position)
                    else -> ownerActivity.toast("else")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Error when sharing server", e)
            }
        }.show()
    }

    /**
     * Displays QR code for the server configuration
     * @param guid The server unique identifier
     */
    private fun showQRCode(guid: String) {
        val ivBinding = ItemQrcodeBinding.inflate(LayoutInflater.from(ownerActivity))
        ivBinding.ivQcode.setImageBitmap(AngConfigManager.share2QRCode(guid))
        if (share_method.isNotEmpty()) {
            ivBinding.ivQcode.contentDescription = share_method[0]
        } else {
            ivBinding.ivQcode.contentDescription = "QR Code"
        }
        AlertDialog.Builder(ownerActivity).setView(ivBinding.root).show()
    }

    /**
     * Shares server configuration to clipboard
     * @param guid The server unique identifier
     */
    private fun share2Clipboard(guid: String) {
        if (AngConfigManager.share2Clipboard(ownerActivity, guid) == 0) {
            ownerActivity.toastSuccess(R.string.toast_success)
        } else {
            ownerActivity.toastError(R.string.toast_failure)
        }
    }

    /**
     * Shares full server configuration content to clipboard
     * @param guid The server unique identifier
     */
    private fun shareFullContent(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(ownerActivity, guid)
            launch(Dispatchers.Main) {
                if (result == 0) {
                    ownerActivity.toastSuccess(R.string.toast_success)
                } else {
                    ownerActivity.toastError(R.string.toast_failure)
                }
            }
        }
    }

    /**
     * Edits server configuration
     * Opens appropriate editing interface based on configuration type
     * @param guid The server unique identifier
     * @param profile The server configuration
     */
    private fun editServer(guid: String, profile: ProfileItem) {
        val intent = Intent().putExtra("guid", guid)
            .putExtra("isRunning", mainViewModel.isRunning.value)
            .putExtra("createConfigType", profile.configType.value)
            .putExtra("subscriptionId", subId)
        when (profile.configType) {
            EConfigType.CUSTOM -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerCustomConfigActivity::class.java))
            }

            EConfigType.POLICYGROUP -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerGroupActivity::class.java))
            }

            else -> {
                ownerActivity.startActivity(intent.setClass(ownerActivity, ServerActivity::class.java))
            }
        }
    }

    /**
     * Removes server configuration
     * Handles confirmation dialog and related checks
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServer(guid: String, position: Int) {
        if (guid == MmkvManager.getSelectServer()) {
            ownerActivity.toast(R.string.toast_action_not_allowed)
            return
        }

        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_CONFIRM_REMOVE)) {
            AlertDialog.Builder(ownerActivity).setMessage(R.string.del_config_comfirm)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    removeServerSub(guid, position)
                }
                .setNegativeButton(android.R.string.cancel) { _, _ ->
                    //do noting
                }
                .show()
        } else {
            removeServerSub(guid, position)
        }
    }

    /**
     * Executes the actual server removal process
     * @param guid The server unique identifier
     * @param position The position in the list
     */
    private fun removeServerSub(guid: String, position: Int) {
        mainViewModel.removeServer(guid)
        adapter.removeServerSub(guid, position)
    }

    /**
     * Sets the selected server
     * Updates UI and restarts service if needed
     * @param guid The server unique identifier to select
     */
    private fun setSelectServer(guid: String) {
        val selected = MmkvManager.getSelectServer()
        if (guid != selected) {
            MmkvManager.setSelectServer(guid)
            val fromPosition = mainViewModel.getPosition(selected.orEmpty())
            val toPosition = mainViewModel.getPosition(guid)
            adapter.setSelectServer(fromPosition, toPosition)

            if (mainViewModel.isRunning.value == true) {
                ownerActivity.restartV2Ray()
            }
        }
    }

    private inner class ActivityAdapterListener : MainAdapterListener {
        override fun onEdit(guid: String, position: Int) {
        }

        override fun onShare(url: String) {
        }

        override fun onRefreshData() {
        }

        override fun onRemove(guid: String, position: Int) {
            removeServer(guid, position)
        }

        override fun onEdit(guid: String, position: Int, profile: ProfileItem) {
            editServer(guid, profile)
        }

        override fun onSelectServer(guid: String) {
            setSelectServer(guid)
        }

        override fun onShare(guid: String, profile: ProfileItem, position: Int, more: Boolean) {
            val isCustom = profile.configType == EConfigType.CUSTOM || profile.configType == EConfigType.POLICYGROUP

            val (shareOptions, skip) = if (more) {
                val options = if (isCustom) share_method_more.asList().takeLast(3) else share_method_more.asList()
                options to if (isCustom) 2 else 0
            } else {
                val options = if (isCustom) share_method.asList().takeLast(1) else share_method.asList()
                options to if (isCustom) 2 else 0
            }

            shareServer(guid, profile, position, shareOptions, skip)
        }
    }

    override fun onRefresh() {
        ownerActivity.importConfigViaSub()
        //binding.refreshLayout.isRefreshing = false
    }

    private fun updateSubscriptionInfo() {
        val subscriptionId = mainViewModel.subscriptionId
        if (subscriptionId.isEmpty()) {
            binding.subscriptionInfoCard.visibility = View.GONE
            return
        }
        
        val subscription = MmkvManager.decodeSubscription(subscriptionId)
        if (subscription == null) {
            binding.subscriptionInfoCard.visibility = View.GONE
            return
        }
        
        // Show subscription name
        binding.tvSubscriptionName.text = subscription.remarks
        
        // Update expiry info
        updateExpiryInfo(subscription)
        
        // Update data usage info
        updateDataUsageInfo(subscription)
        
        // Update server count
        val serverCount = MmkvManager.decodeServerList(subscriptionId).size
        binding.tvServerCount.text = getString(R.string.subscription_servers_count, serverCount)
        
        // Update last updated time
        if (subscription.lastUpdated > 0) {
            val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
            val lastUpdated = java.util.Date(subscription.lastUpdated)
            binding.tvLastUpdated.text = getString(R.string.subscription_last_updated, dateFormat.format(lastUpdated))
            binding.tvLastUpdated.visibility = View.VISIBLE
        } else {
            binding.tvLastUpdated.visibility = View.GONE
        }
        
        // Apply theme style to subscription card
        CustomizationManager.applySubscriptionBannerStyle(binding.subscriptionInfoCard)
        
        // Show the card
        binding.subscriptionInfoCard.visibility = View.VISIBLE
    }
    
    private fun updateExpiryInfo(subscription: SubscriptionItem) {
        val expire = subscription.expire ?: 0
        if (expire > 0) {
            val expireDate = java.util.Date(expire * 1000)
            val dateFormat = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
            val daysLeft = (expire * 1000 - System.currentTimeMillis()) / (1000 * 60 * 60 * 24)
            
            binding.tvExpiryInfo.text = getString(R.string.subscription_expire_date, dateFormat.format(expireDate))
            
            if (daysLeft > 0) {
                binding.tvDaysLeft.text = getString(R.string.subscription_days_left, daysLeft)
                binding.tvDaysLeft.visibility = View.VISIBLE
                
                // Calculate progress (days used out of 30 days as reference)
                val daysUsed = 30 - daysLeft
                val progress = ((daysUsed * 100) / 30).toInt().coerceIn(0, 100)
                binding.progressExpiry.progress = progress
            } else {
                binding.tvDaysLeft.text = getString(R.string.subscription_expired)
                binding.tvDaysLeft.setTextColor(resources.getColor(R.color.md_theme_error, null))
                binding.progressExpiry.progress = 100
            }
        } else {
            binding.tvExpiryInfo.text = getString(R.string.subscription_expire_date, "∞")
            binding.tvDaysLeft.visibility = View.GONE
            binding.progressExpiry.progress = 0
        }
    }
    
    private fun updateDataUsageInfo(subscription: SubscriptionItem) {
        val upload = subscription.upload ?: 0
        val download = subscription.download ?: 0
        val total = subscription.total ?: 0
        
        if (total > 0) {
            val used = upload + download
            val usedPercent = (used * 100 / total).toInt()
            val usedText = formatBytes(used)
            val totalText = formatBytes(total)
            
            binding.tvDataUsage.text = getString(R.string.subscription_data_usage, usedText, totalText, usedPercent)
            binding.tvDataPercent.text = "$usedPercent%"
            binding.tvDataPercent.visibility = View.VISIBLE
            
            // Update progress bar
            binding.progressDataUsage.progress = usedPercent
        } else {
            val used = upload + download
            val usedText = formatBytes(used)
            binding.tvDataUsage.text = getString(R.string.subscription_data_usage_unlimited, usedText)
            binding.tvDataPercent.visibility = View.GONE
            binding.progressDataUsage.progress = 0
        }
    }
    
    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(java.util.Locale.US, "%.1f %s", value, units[index])
    }

    /**
     * Scrolls to the currently selected server in the RecyclerView
     */
    fun scrollToSelectedServer() {
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            ownerActivity.toast(R.string.title_file_chooser)
            return
        }

        // Find the position of the selected server
        val serversCache = mainViewModel.serversCache
        val position = serversCache.indexOfFirst { it.guid == selectedGuid }
        val recyclerView = binding.recyclerView

        if (position >= 0) {
            // Get the layout manager
            val layoutManager = recyclerView.layoutManager as? GridLayoutManager

            if (layoutManager != null) {
                // Scroll to position with offset to center it on screen
                // First scroll to position, then adjust to center
                recyclerView.post {
                    layoutManager.scrollToPositionWithOffset(position, recyclerView.height / 3)
                }
            } else {
                // Fallback to smooth scroll if layout manager is not GridLayoutManager
                recyclerView.smoothScrollToPosition(position)
            }
        } else {
            ownerActivity.toast(R.string.toast_server_not_found_in_group)
        }
    }
}