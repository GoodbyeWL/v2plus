package com.v2plus.app.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.dto.SubscriptionCache
import com.v2plus.app.dto.SubscriptionItem
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.SettingsChangeManager
import com.v2plus.app.handler.SettingsManager
import com.v2plus.app.util.MessageUtil
import java.util.Collections

class SubscriptionsViewModel(application: Application) : AndroidViewModel(application) {
    private val subscriptions: MutableList<SubscriptionCache> = mutableListOf()

    init {
        reload()
    }

    fun getAll(): List<SubscriptionCache> = subscriptions.toList()

    /** True if the first row is the synthetic "ungrouped profiles" entry. */
    fun hasUnassignedHeaderAtTop(): Boolean =
        subscriptions.firstOrNull()?.guid == AppConfig.UNASSIGNED_SUBSCRIPTION_LIST_ID

    fun reload() {
        subscriptions.clear()
        val unassignedCount = MmkvManager.decodeServerList("").size
        if (unassignedCount > 0) {
            MmkvManager.encodeSettings(AppConfig.PREF_SUB_SETTING_SHOW_UNASSIGNED_ROW, true)
        }
        val showUnassignedRow =
            unassignedCount > 0 || MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_SETTING_SHOW_UNASSIGNED_ROW, true)
        if (showUnassignedRow) {
            val title = getApplication<Application>().getString(
                R.string.sub_unassigned_row_title,
                unassignedCount
            )
            subscriptions.add(
                SubscriptionCache(
                    AppConfig.UNASSIGNED_SUBSCRIPTION_LIST_ID,
                    SubscriptionItem(
                        remarks = title,
                        url = "",
                        enabled = false,
                        autoUpdate = false
                    )
                )
            )
        }
        subscriptions.addAll(MmkvManager.decodeSubscriptions())
    }

    fun remove(subId: String): Boolean {
        if (subId == AppConfig.UNASSIGNED_SUBSCRIPTION_LIST_ID) {
            val n = MmkvManager.decodeServerList("").size
            if (n > 0) {
                MmkvManager.removeServerViaSubid("")
            } else {
                MmkvManager.encodeSettings(AppConfig.PREF_SUB_SETTING_SHOW_UNASSIGNED_ROW, false)
            }
            reload()
            SettingsChangeManager.makeSetupGroupTab()
            MessageUtil.sendMsg2UI(getApplication(), AppConfig.MSG_RELOAD_SERVER_LIST, "")
            return true
        }
        val changed = subscriptions.removeAll { it.guid == subId }
        if (changed) {
            SettingsManager.removeSubscriptionWithDefault(subId)
            SettingsChangeManager.makeSetupGroupTab()
        }
        return changed
    }

    fun update(subId: String, item: SubscriptionItem) {
        if (subId == AppConfig.UNASSIGNED_SUBSCRIPTION_LIST_ID) return
        val idx = subscriptions.indexOfFirst { it.guid == subId }
        if (idx >= 0) {
            subscriptions[idx] = SubscriptionCache(subId, item)
            MmkvManager.encodeSubscription(subId, item)
        }
    }

    fun swap(fromPosition: Int, toPosition: Int) {
        if (fromPosition !in subscriptions.indices || toPosition !in subscriptions.indices) return
        val offset = if (hasUnassignedHeaderAtTop()) 1 else 0
        if (offset == 1 && (fromPosition == 0 || toPosition == 0)) return

        val realFrom = fromPosition - offset
        val realTo = toPosition - offset
        val realCount = subscriptions.size - offset
        if (realFrom !in 0 until realCount || realTo !in 0 until realCount) return

        Collections.swap(subscriptions, fromPosition, toPosition)
        SettingsManager.swapSubscriptions(realFrom, realTo)
        SettingsChangeManager.makeSetupGroupTab()
    }
}
