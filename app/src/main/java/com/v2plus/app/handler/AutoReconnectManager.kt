package com.v2plus.app.handler

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.v2plus.app.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Two-layer reconnect:
 *
 * 1. **Proactive health-check** — while VPN is running, periodically probes the connection
 *    via [V2RayServiceManager.probeConnection] (measureDelay through the core).
 *    If [CONSECUTIVE_FAIL_THRESHOLD] probes fail in a row, triggers reconnect/failover
 *    even though the core is technically still "running".
 *
 * 2. **Reactive (core crash)** — [onUnexpectedStop] catches the case when the core loop
 *    itself terminates (server resets the connection, OOM, etc).
 */
object AutoReconnectManager {

    private const val HEALTH_CHECK_INTERVAL_MS = 45_000L
    private const val CONSECUTIVE_FAIL_THRESHOLD = 3
    private const val RETRY_DELAY_MS = 3_000L
    private const val MAX_RETRIES = 2
    private const val FAILOVER_MAX = 5
    private const val FAILOVER_WAIT_MS = 7_000L

    private val handler = Handler(Looper.getMainLooper())
    private var context: Context? = null
    private var active = false
    private var userStopped = false
    private var consecutiveFails = 0
    private var retryCount = 0
    private var lastKnownGuid: String? = null
    @Volatile private var failoverInProgress = false

    fun isEnabled(): Boolean =
        MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_RECONNECT, true)

    // ── lifecycle hooks ──────────────────────────────────────────────

    fun onServiceStarted(ctx: Context) {
        if (!isEnabled()) return
        context = ctx.applicationContext
        active = true
        userStopped = false
        consecutiveFails = 0
        retryCount = 0
        failoverInProgress = false
        lastKnownGuid = MmkvManager.getSelectServer()
        startHealthCheck()
        Log.i(AppConfig.TAG, "AutoReconnect: armed, health-check started for $lastKnownGuid")
    }

    fun onUserStop() {
        userStopped = true
        active = false
        failoverInProgress = false
        stopHealthCheck()
    }

    /**
     * Core loop terminated unexpectedly (not user-initiated).
     */
    fun onUnexpectedStop() {
        if (!active || userStopped || failoverInProgress) return
        if (!isEnabled()) return
        stopHealthCheck()
        Log.w(AppConfig.TAG, "AutoReconnect: core stopped unexpectedly")
        handler.postDelayed({ doReconnectOrFailover() }, RETRY_DELAY_MS)
    }

    fun reset() {
        active = false
        userStopped = false
        failoverInProgress = false
        consecutiveFails = 0
        retryCount = 0
        stopHealthCheck()
        context = null
    }

    // ── periodic health-check ────────────────────────────────────────

    private val healthCheckRunnable = object : Runnable {
        override fun run() {
            if (!active || userStopped || failoverInProgress) return
            CoroutineScope(Dispatchers.IO).launch {
                runHealthProbe()
            }
            handler.postDelayed(this, HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private fun startHealthCheck() {
        stopHealthCheck()
        handler.postDelayed(healthCheckRunnable, HEALTH_CHECK_INTERVAL_MS)
    }

    private fun stopHealthCheck() {
        handler.removeCallbacks(healthCheckRunnable)
    }

    private suspend fun runHealthProbe() {
        if (!V2RayServiceManager.isRunning()) return

        val delay = V2RayServiceManager.probeConnection()

        if (delay >= 0) {
            if (consecutiveFails > 0) {
                Log.i(AppConfig.TAG, "AutoReconnect: health OK (${delay}ms), resetting fail counter from $consecutiveFails")
            }
            consecutiveFails = 0
            retryCount = 0
            return
        }

        consecutiveFails++
        Log.w(AppConfig.TAG, "AutoReconnect: health probe failed ($consecutiveFails/$CONSECUTIVE_FAIL_THRESHOLD)")

        if (consecutiveFails >= CONSECUTIVE_FAIL_THRESHOLD) {
            consecutiveFails = 0
            withContext(Dispatchers.Main) {
                stopHealthCheck()
                doReconnectOrFailover()
            }
        }
    }

    // ── reconnect / failover ─────────────────────────────────────────

    private fun doReconnectOrFailover() {
        val ctx = context ?: return
        if (userStopped || !active) return
        if (failoverInProgress) return

        if (retryCount < MAX_RETRIES) {
            retryCount++
            Log.i(AppConfig.TAG, "AutoReconnect: retry $retryCount/$MAX_RETRIES for current server")

            V2RayServiceManager.stopVService(ctx, isUserAction = false)

            handler.postDelayed({
                if (userStopped) return@postDelayed
                V2RayServiceManager.startVService(ctx)

                handler.postDelayed({
                    if (userStopped) return@postDelayed
                    if (V2RayServiceManager.isRunning()) {
                        // Verify it actually works
                        CoroutineScope(Dispatchers.IO).launch {
                            val probe = V2RayServiceManager.probeConnection()
                            if (probe >= 0) {
                                Log.i(AppConfig.TAG, "AutoReconnect: retry succeeded (${probe}ms)")
                                retryCount = 0
                                consecutiveFails = 0
                                withContext(Dispatchers.Main) { startHealthCheck() }
                            } else {
                                Log.w(AppConfig.TAG, "AutoReconnect: retry started but probe failed")
                                withContext(Dispatchers.Main) { doReconnectOrFailover() }
                            }
                        }
                    } else {
                        doReconnectOrFailover()
                    }
                }, FAILOVER_WAIT_MS)
            }, RETRY_DELAY_MS)
        } else {
            Log.i(AppConfig.TAG, "AutoReconnect: retries exhausted, starting failover")
            failoverInProgress = true
            tryFailover()
        }
    }

    private fun tryFailover() {
        val ctx = context ?: return

        CoroutineScope(Dispatchers.IO).launch {
            val originalGuid = lastKnownGuid ?: return@launch
            val originalConfig = MmkvManager.decodeServerConfig(originalGuid)
            val subId = originalConfig?.subscriptionId ?: ""

            val serverList = MmkvManager.decodeServerList(subId).toMutableList()
            if (serverList.size <= 1) {
                serverList.clear()
                serverList.addAll(MmkvManager.decodeAllServerList())
            }
            serverList.remove(originalGuid)

            // Prefer servers with known good ping
            val sortedByPing = serverList
                .mapNotNull { guid ->
                    val aff = MmkvManager.decodeServerAffiliationInfo(guid)
                    val delay = aff?.testDelayMillis ?: 0L
                    if (delay > 0) guid to delay else null
                }
                .sortedBy { it.second }
                .map { it.first }

            val candidates = if (sortedByPing.isNotEmpty()) {
                sortedByPing
            } else {
                serverList.shuffled()
            }.take(FAILOVER_MAX)

            if (candidates.isEmpty()) {
                Log.w(AppConfig.TAG, "AutoReconnect: no failover candidates")
                failoverInProgress = false
                withContext(Dispatchers.Main) { startHealthCheck() }
                return@launch
            }

            withContext(Dispatchers.Main) {
                V2RayServiceManager.stopVService(ctx, isUserAction = false)
            }
            Thread.sleep(RETRY_DELAY_MS)

            for (guid in candidates) {
                if (userStopped) return@launch

                val config = MmkvManager.decodeServerConfig(guid) ?: continue
                Log.i(AppConfig.TAG, "AutoReconnect: failover trying '${config.remarks}'")
                MmkvManager.setSelectServer(guid)

                withContext(Dispatchers.Main) {
                    V2RayServiceManager.startVService(ctx)
                }

                Thread.sleep(FAILOVER_WAIT_MS)

                if (!V2RayServiceManager.isRunning()) continue

                val probe = V2RayServiceManager.probeConnection()
                if (probe >= 0) {
                    Log.i(AppConfig.TAG, "AutoReconnect: failover success on '${config.remarks}' (${probe}ms)")
                    lastKnownGuid = guid
                    retryCount = 0
                    consecutiveFails = 0
                    failoverInProgress = false
                    withContext(Dispatchers.Main) { startHealthCheck() }
                    return@launch
                } else {
                    Log.w(AppConfig.TAG, "AutoReconnect: '${config.remarks}' started but probe failed, next...")
                    withContext(Dispatchers.Main) {
                        V2RayServiceManager.stopVService(ctx, isUserAction = false)
                    }
                    Thread.sleep(RETRY_DELAY_MS)
                }
            }

            Log.w(AppConfig.TAG, "AutoReconnect: all failover candidates exhausted, reverting to original")
            MmkvManager.setSelectServer(originalGuid)
            failoverInProgress = false
            retryCount = 0
            consecutiveFails = 0

            // Try original one more time
            withContext(Dispatchers.Main) {
                V2RayServiceManager.startVService(ctx)
            }
            Thread.sleep(FAILOVER_WAIT_MS)
            withContext(Dispatchers.Main) { startHealthCheck() }
        }
    }
}
