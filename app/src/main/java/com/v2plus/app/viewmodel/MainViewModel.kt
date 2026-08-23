package com.v2plus.app.viewmodel

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.AssetManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.v2plus.app.AngApplication
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.dto.GroupMapItem
import com.v2plus.app.dto.ServersCache
import com.v2plus.app.dto.SubscriptionCache
import com.v2plus.app.dto.SubscriptionUpdateResult
import com.v2plus.app.dto.TestServiceMessage
import com.v2plus.app.extension.matchesPattern
import com.v2plus.app.extension.serializable
import com.v2plus.app.extension.toastError
import com.v2plus.app.extension.toastSuccess
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.SettingsManager
import com.v2plus.app.handler.SpeedtestManager
import com.v2plus.app.util.MessageUtil
import com.v2plus.app.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.regex.PatternSyntaxException

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private var serverList = mutableListOf<String>() // MmkvManager.decodeServerList()
    var subscriptionId: String = MmkvManager.decodeSettingsString(AppConfig.CACHE_SUBSCRIPTION_ID, "").orEmpty()
    var keywordFilter = ""
    val serversCache = mutableListOf<ServersCache>()
    val isRunning by lazy { MutableLiveData<Boolean>() }
    val isPinging by lazy { MutableLiveData<Boolean>() }
    val updateListAction by lazy { MutableLiveData<Int>() }
    val setupGroupTabAction by lazy { MutableLiveData<Unit>() }
    val updateTestResultAction by lazy { MutableLiveData<String>() }
    private val tcpingTestScope by lazy { CoroutineScope(Dispatchers.IO) }

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     */
    private var lastUpdateListTime = 0L

    fun startListenBroadcast() {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_ACTIVITY)
        ContextCompat.registerReceiver(getApplication(), mMsgReceiver, mFilter, Utils.receiverFlags())
        queryRunningState()
    }

    /**
     * Ask the daemon process for the real VPN state. If nobody answers (service is dead),
     * the UI stays disconnected — same as v2rayNG [startListenBroadcast].
     */
    fun queryRunningState() {
        isRunning.value = false
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_REGISTER_CLIENT, "")
    }

    fun markDisconnected() {
        isRunning.value = false
    }

    /**
     * Called when the ViewModel is cleared.
     */
    override fun onCleared() {
        getApplication<AngApplication>().unregisterReceiver(mMsgReceiver)
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        Log.i(AppConfig.TAG, "Main ViewModel is cleared")
        super.onCleared()
    }

    /**
     * Reloads the server list based on current subscription filter.
     */
    fun reloadServerList() {
        serverList = if (subscriptionId == "all") {
            MmkvManager.decodeAllServerList()
        } else {
            MmkvManager.decodeServerList(subscriptionId)
        }

        updateCache()
        updateListAction.value = -1
    }

    fun fullReloadServerList() {
        reloadServerList()
        setupGroupTabAction.value = Unit
    }

    /**
     * Removes a server by its GUID.
     * @param guid The GUID of the server to remove.
     */
    fun removeServer(guid: String) {
        serverList.remove(guid)
        MmkvManager.removeServer(guid)
        val index = getPosition(guid)
        if (index >= 0) {
            serversCache.removeAt(index)
        }
    }

    /**
     * Swaps the positions of two servers.
     * @param fromPosition The initial position of the server.
     * @param toPosition The target position of the server.
     */
    fun swapServer(fromPosition: Int, toPosition: Int) {
        if (subscriptionId == "all") {
            return
        }

        Collections.swap(serverList, fromPosition, toPosition)
        Collections.swap(serversCache, fromPosition, toPosition)

        MmkvManager.encodeServerList(serverList, subscriptionId)
    }

    /**
     * Updates the cache of servers.
     */
    @Synchronized
    fun updateCache() {
        serversCache.clear()
        val kw = keywordFilter.trim()
        val searchRegex = try {
            if (kw.isNotEmpty()) Regex(kw, setOf(RegexOption.IGNORE_CASE)) else null
        } catch (e: PatternSyntaxException) {
            null // Fallback to literal search if regex is invalid
        }
        for (guid in serverList) {
            val profile = MmkvManager.decodeServerConfig(guid) ?: continue
            if (kw.isEmpty()) {
                serversCache.add(ServersCache(guid, profile))
                continue
            }

            val remarks = profile.remarks
            val description = profile.description.orEmpty()
            val server = profile.server.orEmpty()
            val protocol = profile.configType.name
            
            // Find subscription name for filtering
            val subName = if (profile.subscriptionId.isNotEmpty()) {
                MmkvManager.decodeSubscription(profile.subscriptionId)?.remarks ?: ""
            } else {
                "default"
            }

            if (remarks.matchesPattern(searchRegex, kw)
                || description.matchesPattern(searchRegex, kw)
                || server.matchesPattern(searchRegex, kw)
                || protocol.matchesPattern(searchRegex, kw)
                || subName.matchesPattern(searchRegex, kw)
            ) {
                serversCache.add(ServersCache(guid, profile))
            }
        }
    }

    /**
     * Updates the configuration via subscription for all servers.
     * @return Detailed result of the subscription update operation.
     */
    fun updateConfigViaSubAll(): SubscriptionUpdateResult {
        if (subscriptionId == "all") {
            return AngConfigManager.updateConfigViaSubAll()
        } else if (subscriptionId == "") {
            return SubscriptionUpdateResult()
        } else {
            val subItem = MmkvManager.decodeSubscription(subscriptionId) ?: return SubscriptionUpdateResult()
            return AngConfigManager.updateConfigViaSub(SubscriptionCache(subscriptionId, subItem))
        }
    }

    /**
     * Exports all servers.
     * @return The number of exported servers.
     */
    fun exportAllServer(): Int {
        val serverListCopy =
            if (subscriptionId == "all" && keywordFilter.isEmpty()) {
                serverList
            } else {
                serversCache.map { it.guid }.toList()
            }

        val ret = AngConfigManager.shareNonCustomConfigsToClipboard(
            getApplication<AngApplication>(),
            serverListCopy
        )
        return ret
    }

    /**
     * Tests the TCP ping for all servers.
     */
    fun testAllTcping() {
        tcpingTestScope.coroutineContext[Job]?.cancelChildren()
        SpeedtestManager.closeAllTcpSockets()
        MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())

        val serversCopy = serversCache.toList()
        for (item in serversCopy) {
            item.profile.let { outbound ->
                val serverAddress = outbound.server
                val serverPort = outbound.serverPort
                if (serverAddress != null && serverPort != null) {
                    tcpingTestScope.launch {
                        val testResult = SpeedtestManager.tcping(serverAddress, serverPort.toInt())
                        launch(Dispatchers.Main) {
                            MmkvManager.encodeServerTestDelayMillis(item.guid, testResult)
                            updateListAction.value = getPosition(item.guid)
                        }
                    }
                }
            }
        }
    }

    /**
     * Tests the real ping for all servers.
     */
    fun testAllRealPing() {
        MessageUtil.sendMsg2TestService(
            getApplication(),
            TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL)
        )
        MmkvManager.clearAllTestDelayResults(serversCache.map { it.guid }.toList())
        updateListAction.value = -1
        isPinging.value = true

        viewModelScope.launch(Dispatchers.Default) {
            if (serversCache.isEmpty()) {
                isPinging.postValue(false)
                return@launch
            }
            MessageUtil.sendMsg2TestService(
                getApplication(),
                TestServiceMessage(
                    key = AppConfig.MSG_MEASURE_CONFIG,
                    subscriptionId = subscriptionId,
                    serverGuids = if (keywordFilter.isNotEmpty()) serversCache.map { it.guid } else emptyList()
                )
            )
        }
    }

    /**
     * Tests the real ping for the current server.
     */
    fun testCurrentServerRealPing() {
        MessageUtil.sendMsg2Service(getApplication(), AppConfig.MSG_MEASURE_DELAY, "")
    }

    /**
     * Changes the subscription ID.
     * @param id The new subscription ID.
     */
    fun subscriptionIdChanged(id: String) {
        if (subscriptionId == id) {
            return
        }
        subscriptionId = id
        MmkvManager.encodeSettings(AppConfig.CACHE_SUBSCRIPTION_ID, subscriptionId)
        reloadServerList()
        setupGroupTabAction.value = Unit
    }

    /**
     * Gets the subscriptions.
     * @param context The context.
     * @return A pair of lists containing the subscription IDs and remarks.
     */
    fun getSubscriptions(context: Context): List<GroupMapItem> {
        val subscriptions = MmkvManager.decodeSubscriptions()

        val unassignedCount = MmkvManager.decodeServerList("").size
        if (unassignedCount > 0) {
            MmkvManager.encodeSettings(AppConfig.PREF_SUB_SETTING_SHOW_UNASSIGNED_ROW, true)
        }
        val showUnassignedTab =
            unassignedCount > 0 || MmkvManager.decodeSettingsBool(AppConfig.PREF_SUB_SETTING_SHOW_UNASSIGNED_ROW, true)

        if (!showUnassignedTab && subscriptionId.isEmpty()) {
            val newId = when {
                MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY) -> "all"
                subscriptions.isNotEmpty() -> subscriptions.first().guid
                else -> subscriptionId
            }
            if (newId.isNotEmpty() && newId != subscriptionId) {
                subscriptionIdChanged(newId)
            }
        }

        if (subscriptionId.isNotEmpty() && subscriptionId != "all"
            && !subscriptions.map { it.guid }.contains(subscriptionId)
        ) {
            subscriptionIdChanged("")
        }

        val groups = mutableListOf<GroupMapItem>()
        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_GROUP_ALL_DISPLAY)) {
            groups.add(
                GroupMapItem(
                    id = "all",
                    remarks = context.getString(R.string.filter_config_all)
                )
            )
        }

        if (showUnassignedTab) {
            groups.add(
                GroupMapItem(
                    id = "",
                    remarks = context.getString(R.string.group_tab_unassigned)
                )
            )
        }
        
        // Group subscriptions by their group field
        val groupedSubscriptions = subscriptions.groupBy { it.subscription.group ?: "" }
        
        // Add groups and their subscriptions
        groupedSubscriptions.forEach { (groupName, subs) ->
            if (groupName.isNotEmpty()) {
                // Add group header
                groups.add(
                    GroupMapItem(
                        id = "group_$groupName",
                        remarks = "📁 $groupName",
                        isGroup = true
                    )
                )
            }
            
            // Add subscriptions in this group
            subs.forEach { sub ->
                groups.add(
                    GroupMapItem(
                        id = sub.guid,
                        remarks = sub.subscription.remarks,
                        group = groupName
                    )
                )
            }
        }
        
        return groups
    }

    /**
     * Gets the position of a server by its GUID.
     * @param guid The GUID of the server.
     * @return The position of the server.
     */
    fun getPosition(guid: String): Int {
        serversCache.forEachIndexed { index, it ->
            if (it.guid == guid)
                return index
        }
        return -1
    }

    /**
     * Removes duplicate servers.
     * @return The number of removed servers.
     */
    fun removeDuplicateServer(): Int {
        val serversCacheCopy = serversCache.toList().toMutableList()
        val deleteServer = mutableListOf<String>()
        serversCacheCopy.forEachIndexed { index, sc ->
            val profile = sc.profile
            serversCacheCopy.forEachIndexed { index2, sc2 ->
                if (index2 > index) {
                    val profile2 = sc2.profile
                    if (profile == profile2 && !deleteServer.contains(sc2.guid)) {
                        deleteServer.add(sc2.guid)
                    }
                }
            }
        }
        for (it in deleteServer) {
            MmkvManager.removeServer(it)
        }

        return deleteServer.count()
    }

    /**
     * Removes all servers.
     * @return The number of removed servers.
     */
    fun removeAllServer(): Int {
        val count =
            if (subscriptionId == "all" && keywordFilter.isEmpty()) {
                MmkvManager.removeAllServer()
            } else {
                val serversCopy = serversCache.toList()
                for (item in serversCopy) {
                    MmkvManager.removeServer(item.guid)
                }
                serversCache.toList().count()
            }
        return count
    }

    /**
     * Removes invalid servers.
     * @return The number of removed servers.
     */
    fun removeInvalidServer(): Int {
        var count = 0
        if (subscriptionId == "all" && keywordFilter.isEmpty()) {
            count += MmkvManager.removeInvalidServer("")
        } else {
            val serversCopy = serversCache.toList()
            for (item in serversCopy) {
                count += MmkvManager.removeInvalidServer(item.guid)
            }
        }
        return count
    }

    /**
     * Sorts servers by their test results.
     */
    fun sortByTestResults() {
        if (subscriptionId == "all") {
            MmkvManager.decodeSubsList().forEach { guid ->
                sortByTestResultsForSub(guid)
            }
            sortByTestResultsForSub("")
            // Rebuild the combined list from sorted individual subscriptions
            serverList = MmkvManager.decodeAllServerList()
        } else {
            sortByTestResultsForSub(subscriptionId)
        }
    }

    /**
     * Sorts servers by their test results for a specific subscription.
     * @param subId The subscription ID to sort servers for.
     */
    private fun sortByTestResultsForSub(subId: String) {
        data class ServerDelay(var guid: String, var testDelayMillis: Long)

        val serverDelays = mutableListOf<ServerDelay>()
        val serverListToSort = MmkvManager.decodeServerList(subId)

        serverListToSort.forEach { key ->
            val delay = MmkvManager.decodeServerAffiliationInfo(key)?.testDelayMillis ?: 0L
            serverDelays.add(ServerDelay(key, if (delay <= 0L) 999999 else delay))
        }
        serverDelays.sortBy { it.testDelayMillis }

        val sortedServerList = serverDelays.map { it.guid }.toMutableList()

        // Save the sorted list for this subscription
        MmkvManager.encodeServerList(sortedServerList, subId)
    }


    /**
     * Initializes assets.
     * @param assets The asset manager.
     */
    fun initAssets(assets: AssetManager) {
        viewModelScope.launch(Dispatchers.Default) {
            SettingsManager.initAssets(getApplication<AngApplication>(), assets)
        }
    }

    /**
     * Filters the configuration by a keyword.
     * @param keyword The keyword to filter by.
     */
    fun filterConfig(keyword: String) {
        if (keyword == keywordFilter) {
            return
        }
        keywordFilter = keyword
        reloadServerList()
    }

    fun findSubscriptionIdBySelect(): String? {
        // Get the selected server GUID
        val selectedGuid = MmkvManager.getSelectServer()
        if (selectedGuid.isNullOrEmpty()) {
            return null
        }

        val config = MmkvManager.decodeServerConfig(selectedGuid)
        return config?.subscriptionId
    }

    fun onTestsFinished() {
        viewModelScope.launch(Dispatchers.Default) {
            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_REMOVE_INVALID_AFTER_TEST)) {
                removeInvalidServer()
            }

            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_SORT_AFTER_TEST)) {
                sortByTestResults()
            }

            withContext(Dispatchers.Main) {
                reloadServerList()
                // Force UI refresh for all items to show updated delay values
                updateListAction.value = -1
            }
        }
    }

    private val mMsgReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_STATE_RUNNING -> {
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_NOT_RUNNING -> {
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_START_SUCCESS -> {
                    getApplication<AngApplication>().toastSuccess(R.string.toast_services_success)
                    isRunning.value = true
                }

                AppConfig.MSG_STATE_START_FAILURE -> {
                    getApplication<AngApplication>().toastError(R.string.toast_services_failure)
                    isRunning.value = false
                }

                AppConfig.MSG_STATE_STOP_SUCCESS -> {
                    isRunning.value = false
                }

                AppConfig.MSG_MEASURE_DELAY_SUCCESS -> {
                    updateTestResultAction.value = intent.getStringExtra("content")
                }

                AppConfig.MSG_MEASURE_CONFIG_SUCCESS -> {
                    val resultPair = intent.serializable<Pair<String, Long>>("content") ?: return
                    MmkvManager.encodeServerTestDelayMillis(resultPair.first, resultPair.second)
                    
                    val isCompatibility = MmkvManager.decodeSettingsBool(AppConfig.PREF_COMPATIBILITY_MODE, false)
                    val now = System.currentTimeMillis()
                    
                    if (isCompatibility && (now - lastUpdateListTime < 1000)) {
                        return
                    }
                    lastUpdateListTime = now

                    if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LIVE_SORT_DURING_TEST, true)) {
                        sortByTestResults()
                        reloadServerList()
                    } else {
                        updateListAction.value = getPosition(resultPair.first)
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_NOTIFY -> {
                    val content = intent.getStringExtra("content")
                    val parts = content?.split("|") ?: return
                    if (parts.size == 2) {
                        val left = parts[0].toIntOrNull() ?: return
                        val total = parts[1].toIntOrNull() ?: return
                        updateTestResultAction.value =
                            getApplication<AngApplication>().getString(R.string.connection_runing_task_left, left, total)
                    }
                }

                AppConfig.MSG_MEASURE_CONFIG_FINISH -> {
                    val content = intent.getStringExtra("content")
                    if (content == "0") {
                        if (MmkvManager.decodeSettingsBool(AppConfig.PREF_COMPATIBILITY_MODE, false)) {
                            // Ensure final state is updated
                            if (MmkvManager.decodeSettingsBool(AppConfig.PREF_LIVE_SORT_DURING_TEST, true)) {
                                sortByTestResults()
                                reloadServerList()
                            }
                        }
                        onTestsFinished()
                    }
                    isPinging.value = false
                }

                AppConfig.MSG_RELOAD_SERVER_LIST -> {
                    fullReloadServerList()
                }


            }
        }
    }
}
