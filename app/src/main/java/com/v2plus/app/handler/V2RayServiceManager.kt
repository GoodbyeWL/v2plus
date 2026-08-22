package com.v2plus.app.handler

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.content.ContextCompat
import com.v2plus.app.AppConfig
import com.v2plus.app.R
import com.v2plus.app.contracts.ServiceControl
import com.v2plus.app.dto.OutboundTrafficStat
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.enums.EConfigType
import com.v2plus.app.extension.toast
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.SettingsManager
import com.v2plus.app.service.V2RayProxyOnlyService
import com.v2plus.app.service.V2RayVpnService
import com.v2plus.app.util.MessageUtil
import com.v2plus.app.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import java.lang.ref.SoftReference

object V2RayServiceManager {

    private val coreController: CoreController = V2RayNativeManager.newCoreController(CoreCallback())

    /** Whether the V2Ray core loop is currently running. */
    fun isRunning(): Boolean = coreController.isRunning

    /**
     * Stops the VPN or proxy-only foreground service.
     * Uses the same [AppConfig.MSG_STATE_STOP] broadcast path as the notification action so
     * [ServiceControl.stopService] runs in the daemon process, [stopCoreLoop] runs, and the UI
     * receives [AppConfig.MSG_STATE_STOP_SUCCESS]. A raw [Context.stopService] can destroy the
     * service without that teardown, leaving the VPN active and the main screen stuck on "connected".
     */
    /**
     * @param isUserAction true when the user explicitly taps Stop; false when called
     *                     internally by AutoReconnectManager for restart/failover.
     */
    @JvmOverloads
    fun stopVService(context: Context, isUserAction: Boolean = true) {
        if (isUserAction) {
            AutoReconnectManager.onUserStop()
        }
        try {
            MessageUtil.sendMsg2Service(context, AppConfig.MSG_STATE_STOP, "")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "stopVService", e)
        }
    }

    /**
     * Display name for VPN session / tile (selected server remarks).
     */
    fun getRunningServerName(): String {
        val guid = MmkvManager.getSelectServer() ?: return ""
        val remarks = MmkvManager.decodeServerConfig(guid)?.remarks?.trim().orEmpty()
        return remarks.ifEmpty { "v2plus" }
    }

    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            V2RayNativeManager.initCoreEnv(value?.get()?.getService())
        }

    /**
     * Starts the V2Ray service from a toggle action.
     * @param context The context from which the service is started.
     * @return True if the service was started successfully, false otherwise.
     */
    fun startVServiceFromToggle(context: Context): Boolean {
        if (MmkvManager.getSelectServer().isNullOrEmpty()) {
            context.toast(R.string.app_tile_first_use)
            return false
        }
        startVService(context)
        return true
    }

    /**
     * Starts the service using a specific server [guid] (e.g. Tasker).
     */
    fun startVService(context: Context, guid: String) {
        MmkvManager.setSelectServer(guid)
        startVService(context)
    }

    /**
     * Starts the V2Ray service.
     * @param context The context from which the service is started.
     */
    fun startVService(context: Context) {
        if (SettingsManager.isVpnMode()) {
            val intent = Intent(context, V2RayVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } else {
            val intent = Intent(context, V2RayProxyOnlyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /**
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        // Prevent starting if already running
        if (coreController.isRunning) {
            Log.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return true // Return true if already running to avoid errors
        }

        val service = getService()
        if (service == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        val guid = MmkvManager.getSelectServer()
        if (guid == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: No server selected")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "No server selected")
            return false
        }

        val config = MmkvManager.decodeServerConfig(guid)
        if (config == null) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to decode server config")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "Failed to decode server config")
            return false
        }

        Log.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = V2rayConfigManager.getV2rayConfig(service, guid)
        if (!result.status) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to get V2Ray config")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "Failed to get V2Ray config")
            return false
        }

        try {
            val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
            mFilter.addAction(Intent.ACTION_SCREEN_ON)
            mFilter.addAction(Intent.ACTION_SCREEN_OFF)
            mFilter.addAction(Intent.ACTION_USER_PRESENT)
            ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to register receiver", e)
            clearLocalProxySessionMmkv()
            return false
        }

        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        try {
            NotificationManager.showNotification(currentConfig)
            coreController.startLoop(result.content, tunFd)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to start core loop", e)
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "Failed to start core loop: ${e.message}")
            NotificationManager.cancelNotification()
            clearLocalProxySessionMmkv()
            return false
        }

        // Wait for core to start with timeout (max 10 seconds with exponential backoff)
        var coreStarted = false
        val startTime = System.currentTimeMillis()
        val timeoutMs = 10000L
        var sleepDuration = 50L // Start with 50ms
        
        while (!coreStarted && (System.currentTimeMillis() - startTime) < timeoutMs) {
            Thread.sleep(sleepDuration)
            coreStarted = coreController.isRunning
            
            // Increase sleep duration exponentially up to 200ms to reduce CPU usage
            if (sleepDuration < 200L) {
                sleepDuration = (sleepDuration * 1.5).toLong().coerceAtMost(200L)
            }
        }
        
        if (!coreStarted) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Core failed to start within timeout")
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, "Core failed to start within timeout")
            NotificationManager.cancelNotification()
            clearLocalProxySessionMmkv()
            return false
        }

        try {
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
            NotificationManager.startSpeedNotification(currentConfig)
            AutoReconnectManager.onServiceStarted(service)
            Log.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to send startup success", e)
        }
        return true
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        val service = getService() ?: return false
        
        // Only attempt to stop if core is actually running
        if (coreController.isRunning) {
            try {
                // Synchronously stop the core loop to ensure it happens before cleanup
                coreController.stopLoop()
                
                // Wait for core to stop with timeout (max 5 seconds with exponential backoff)
                val startTime = System.currentTimeMillis()
                val timeoutMs = 5000L
                var sleepDuration = 50L // Start with 50ms
                var isRunning = coreController.isRunning
                
                while (isRunning && (System.currentTimeMillis() - startTime) < timeoutMs) {
                    Thread.sleep(sleepDuration)
                    isRunning = coreController.isRunning
                    
                    // Increase sleep duration exponentially up to 200ms to reduce CPU usage
                    if (sleepDuration < 200L) {
                        sleepDuration = (sleepDuration * 1.5).toLong().coerceAtMost(200L)
                    }
                }
                
                if (coreController.isRunning) {
                    Log.w(AppConfig.TAG, "StartCore-Manager: Core did not stop within timeout, forcing cleanup")
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Error stopping V2Ray loop", e)
                // Continue with cleanup even if stopping the loop failed
            }
        } else {
            Log.d(AppConfig.TAG, "StartCore-Manager: Core already stopped, skipping stop operation")
        }

        // Always send stop success message to UI
        MessageUtil.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        
        // Cancel notifications
        NotificationManager.cancelNotification()

        clearLocalProxySessionMmkv()

        // Unregister broadcast receivers
        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, which is fine
            Log.d(AppConfig.TAG, "StartCore-Manager: Receiver not registered, skipping unregister")
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go payload format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        val payload = try {
            coreController.queryAllOutboundTrafficStats()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "Failed to query outbound traffic stats", e)
            return emptyList()
        }

        val result = ArrayList<OutboundTrafficStat>()
        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach
            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach
            val value = parts[2].toLongOrNull() ?: return@forEach
            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     */
    private fun measureV2rayDelay() {
        if (coreController.isRunning == false) {
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val service = getService() ?: return@launch
            var time = -1L
            var errorStr = ""

            try {
                time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                errorStr = e.message?.substringAfter("\":") ?: "empty message"
            }
            if (time == -1L) {
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay", e)
                    errorStr = e.message?.substringAfter("\":") ?: "empty message"
                }
            }

            val result = if (time >= 0) {
                service.getString(R.string.connection_test_available, time)
            } else {
                service.getString(R.string.connection_test_error, errorStr)
            }
            MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, result)

            // Only fetch IP info if the delay test was successful
            if (time >= 0) {
                SpeedtestManager.getRemoteIPInfo()?.let { ip ->
                    MessageUtil.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_SUCCESS, "$result\n$ip")
                }
            }
        }
    }

    /**
     * Probes whether traffic actually flows through the current server.
     * @return delay in ms (>= 0 means OK), or -1 on failure.
     */
    fun probeConnection(): Long {
        if (!coreController.isRunning) return -1L
        return try {
            val t = coreController.measureDelay(SettingsManager.getDelayTestUrl())
            if (t >= 0) t
            else coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
        } catch (e: Exception) {
            Log.w(AppConfig.TAG, "probeConnection failed", e)
            -1L
        }
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    private fun clearLocalProxySessionMmkv() {
        MmkvManager.encodeSettings(AppConfig.CACHE_LOCAL_PROXY_SESSION_USER, "")
        MmkvManager.encodeSettings(AppConfig.CACHE_LOCAL_PROXY_SESSION_PASS, "")
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            val serviceControl = serviceControl?.get() ?: return -1
            return try {
                serviceControl.stopService()
                AutoReconnectManager.onUnexpectedStop()
                0
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "StartCore-Manager: Failed to stop service", e)
                -1
            }
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            return 0
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    if (coreController.isRunning) {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                    } else {
                        MessageUtil.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    serviceControl.stopService()
                    Thread.sleep(500L)
                    startVService(serviceControl.getService())
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    measureV2rayDelay()
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification(currentConfig)
                }

                Intent.ACTION_SCREEN_ON -> {
                    Log.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification(currentConfig)
                }
            }
        }
    }
}
