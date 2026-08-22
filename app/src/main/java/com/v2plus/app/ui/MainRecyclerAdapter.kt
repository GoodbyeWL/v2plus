package com.v2plus.app.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.contracts.MainAdapterListener
import com.v2plus.app.databinding.ItemRecyclerFooterBinding
import com.v2plus.app.databinding.ItemRecyclerMainBinding
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.dto.ServersCache
import com.v2plus.app.extension.nullIfBlank
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.CustomizationManager
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.V2RayServiceManager
import com.v2plus.app.helper.ItemTouchHelperAdapter
import com.v2plus.app.helper.ItemTouchHelperViewHolder
import com.v2plus.app.viewmodel.MainViewModel
import java.util.Collections

class MainRecyclerAdapter(
    private val mainViewModel: MainViewModel,
    private val adapterListener: MainAdapterListener?
) : RecyclerView.Adapter<MainRecyclerAdapter.BaseViewHolder>(), ItemTouchHelperAdapter {
    companion object {
        private const val VIEW_TYPE_ITEM = 1
        private const val VIEW_TYPE_FOOTER = 2
    }

    private val doubleColumnDisplay = MmkvManager.decodeSettingsBool(AppConfig.PREF_DOUBLE_COLUMN_DISPLAY, false)
    private var data: MutableList<ServersCache> = mutableListOf()

    @SuppressLint("NotifyDataSetChanged")
    fun setData(newData: MutableList<ServersCache>?, position: Int = -1) {
        val incomingData = newData?.toList() ?: emptyList()
        val oldData = data.toList()

        if (position >= 0) {
            data = incomingData.toMutableList()
            if (position in data.indices) {
                notifyItemChanged(position)
            }
        } else if (oldData.isEmpty() || incomingData.isEmpty() || Math.abs(oldData.size - incomingData.size) > 100 || incomingData.size > 500) {
            // If the list is very large (e.g. 3000 items) or difference is huge, DiffUtil might be too slow and still causes scroll issues.
            // So we fallback to notifyDataSetChanged() but we let the Fragment handle the scroll state restoration if needed.
            data = incomingData.toMutableList()
            notifyDataSetChanged()
        } else {
            // Use DiffUtil to gracefully animate sorting and preserve scroll state
            val diffCallback = object : DiffUtil.Callback() {
                override fun getOldListSize() = oldData.size + 1
                override fun getNewListSize() = incomingData.size + 1
                
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    if (oldItemPosition == oldData.size || newItemPosition == incomingData.size) {
                        return oldItemPosition == oldData.size && newItemPosition == incomingData.size
                    }
                    return oldData[oldItemPosition].guid == incomingData[newItemPosition].guid
                }
                
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    if (oldItemPosition == oldData.size || newItemPosition == incomingData.size) {
                        return true
                    }
                    val oldGuid = oldData[oldItemPosition].guid
                    val newGuid = incomingData[newItemPosition].guid
                    if (oldGuid != newGuid) return false
                    
                    val oldProfile = oldData[oldItemPosition].profile
                    val newProfile = incomingData[newItemPosition].profile
                    if (oldProfile.remarks != newProfile.remarks || oldProfile.server != newProfile.server) {
                        return false
                    }
                    
                    val oldAff = MmkvManager.decodeServerAffiliationInfo(oldGuid)
                    val newAff = MmkvManager.decodeServerAffiliationInfo(newGuid)
                    return oldAff?.testDelayMillis == newAff?.testDelayMillis
                }
            }
            val diffResult = DiffUtil.calculateDiff(diffCallback)
            data = incomingData.toMutableList()
            diffResult.dispatchUpdatesTo(this)
        }
    }

    override fun getItemCount() = data.size + 1

    override fun onBindViewHolder(holder: BaseViewHolder, position: Int) {
        if (holder is MainViewHolder) {
            val context = holder.itemMainBinding.root.context
            val guid = data[position].guid
            val profile = data[position].profile

            holder.itemView.setBackgroundColor(Color.TRANSPARENT)

            //Name address
            holder.itemMainBinding.tvName.text = profile.remarks
            holder.itemMainBinding.tvStatistics.text = getAddress(profile)
            holder.itemMainBinding.tvType.text = profile.configType.name

            //TestResult
            val aff = MmkvManager.decodeServerAffiliationInfo(guid)
            val delay = aff?.testDelayMillis ?: 0L
            holder.itemMainBinding.tvTestResult.text = if (delay < 0L) {
                context.getString(R.string.server_unavailable_short)
            } else {
                aff?.getTestDelayString().orEmpty()
            }
            if (delay < 0L) {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPingRed))
            } else {
                holder.itemMainBinding.tvTestResult.setTextColor(ContextCompat.getColor(context, R.color.colorPing))
            }

            //layoutIndicator
            if (guid == MmkvManager.getSelectServer()) {
                holder.itemMainBinding.layoutIndicator.setBackgroundColor(CustomizationManager.getAccentOpaque())
            } else {
                holder.itemMainBinding.layoutIndicator.setBackgroundResource(0)
            }

            holder.itemMainBinding.tvType.setTextColor(CustomizationManager.getAccentOpaque())

            val isSelected = guid == MmkvManager.getSelectServer()
            val isSelectedAndRunning = isSelected && V2RayServiceManager.isRunning()
            when {
                isSelectedAndRunning -> holder.itemMainBinding.infoContainer.background = CustomizationManager.createServerPanelActiveDrawable()
                isSelected -> holder.itemMainBinding.infoContainer.background = CustomizationManager.createServerPanelSelectedDrawable()
                else -> holder.itemMainBinding.infoContainer.background = CustomizationManager.createServerPanelDrawable()
            }

            //subscription remarks
            val subRemarks = getSubscriptionRemarks(profile)
            holder.itemMainBinding.tvSubscription.text = subRemarks
            holder.itemMainBinding.layoutSubscription.visibility = if (subRemarks.isEmpty()) View.GONE else View.VISIBLE

            //layout
            if (doubleColumnDisplay) {
                holder.itemMainBinding.layoutShare.visibility = View.GONE
                holder.itemMainBinding.layoutEdit.visibility = View.GONE
                holder.itemMainBinding.layoutRemove.visibility = View.GONE
                holder.itemMainBinding.layoutMore.visibility = View.VISIBLE

                holder.itemMainBinding.layoutMore.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, true)
                }
            } else {
                holder.itemMainBinding.layoutShare.visibility = View.VISIBLE
                holder.itemMainBinding.layoutEdit.visibility = View.VISIBLE
                holder.itemMainBinding.layoutRemove.visibility = View.VISIBLE
                holder.itemMainBinding.layoutMore.visibility = View.GONE

                holder.itemMainBinding.layoutShare.setOnClickListener {
                    adapterListener?.onShare(guid, profile, position, false)
                }

                holder.itemMainBinding.layoutEdit.setOnClickListener {
                    adapterListener?.onEdit(guid, position, profile)
                }
                holder.itemMainBinding.layoutRemove.setOnClickListener {
                    adapterListener?.onRemove(guid, position)
                }
            }

            holder.itemMainBinding.infoContainer.setOnClickListener {
                adapterListener?.onSelectServer(guid)
            }

            CustomizationManager.applyTypography(holder.itemMainBinding.root)
        } else if (holder is FooterViewHolder) {
            CustomizationManager.applyTypography(holder.itemFooterBinding.root)
        }
    }

    /**
     * Gets the server address information
     * Hides part of IP or domain information for privacy protection
     * @param profile The server configuration
     * @return Formatted address string
     */
    private fun getAddress(profile: ProfileItem): String {
        return profile.description.nullIfBlank() ?: AngConfigManager.generateDescription(profile)
    }

    /**
     * Gets the subscription remarks information
     * @param profile The server configuration
     * @return Subscription remarks string, or empty string if none
     */
    private fun getSubscriptionRemarks(profile: ProfileItem): String {
        val subRemarks =
            if (mainViewModel.subscriptionId.isEmpty())
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks?.firstOrNull()
            else
                null
        return subRemarks?.toString() ?: ""
    }

    fun removeServerSub(guid: String, position: Int) {
        val idx = data.indexOfFirst { it.guid == guid }
        if (idx >= 0) {
            data.removeAt(idx)
            notifyItemRemoved(idx)
            notifyItemRangeChanged(idx, data.size - idx)
        }
    }

    fun setSelectServer(fromPosition: Int, toPosition: Int) {
        notifyItemChanged(fromPosition)
        notifyItemChanged(toPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BaseViewHolder {
        return when (viewType) {
            VIEW_TYPE_ITEM ->
                MainViewHolder(ItemRecyclerMainBinding.inflate(LayoutInflater.from(parent.context), parent, false))

            else ->
                FooterViewHolder(ItemRecyclerFooterBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == data.size) {
            VIEW_TYPE_FOOTER
        } else {
            VIEW_TYPE_ITEM
        }
    }

    open class BaseViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        fun onItemSelected() {
            itemView.setBackgroundColor(Color.TRANSPARENT)
        }

        fun onItemClear() {
            itemView.setBackgroundColor(Color.TRANSPARENT)
        }
    }

    class MainViewHolder(val itemMainBinding: ItemRecyclerMainBinding) :
        BaseViewHolder(itemMainBinding.root), ItemTouchHelperViewHolder

    class FooterViewHolder(val itemFooterBinding: ItemRecyclerFooterBinding) :
        BaseViewHolder(itemFooterBinding.root)

    override fun onItemMove(fromPosition: Int, toPosition: Int): Boolean {
        mainViewModel.swapServer(fromPosition, toPosition)
        if (fromPosition < data.size && toPosition < data.size) {
            Collections.swap(data, fromPosition, toPosition)
        }
        notifyItemMoved(fromPosition, toPosition)
        return true
    }

    override fun onItemMoveCompleted() {
        // do nothing
    }

    override fun onItemDismiss(position: Int) {
    }
}
