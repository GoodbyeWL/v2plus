package com.v2plus.app.ui

import android.app.Activity
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.res.ColorStateList
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.v2plus.app.AppConfig
import com.v2plus.app.BuildConfig
import com.v2plus.app.handler.CustomizationManager
import com.v2plus.app.R
import com.v2plus.app.databinding.ActivityContentTransferBinding
import com.v2plus.app.extension.toastError
import com.v2plus.app.extension.toastSuccess
import com.v2plus.app.handler.AngConfigManager
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.dto.SubscriptionItem
import com.v2plus.app.dto.ProfileItem
import com.v2plus.app.util.ContentTransferRuntime
import com.v2plus.app.util.ContentTransferUtil
import com.v2plus.app.util.JsonUtil
import com.v2plus.app.util.MessageUtil
import com.v2plus.app.util.QRCodeDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class ContentTransferActivity : BaseActivity() {
    private val binding by lazy { ActivityContentTransferBinding.inflate(layoutInflater) }
    private var blueSenderSession: ContentTransferRuntime.BlueSenderSession? = null
    private var pendingAction: (() -> Unit)? = null
    @Volatile
    private var stopRequested = false
    private val logLines = java.util.LinkedList<String>()

    private fun appendLog(msg: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        logLines.addLast("[$ts] $msg")
        while (logLines.size > 50) logLines.removeFirst()
        runOnUiThread {
            binding.layoutTransferLog.visibility = android.view.View.VISIBLE
            binding.tvTransferLog.text = logLines.joinToString("\n")
            binding.tvTransferLog.post {
                (binding.tvTransferLog.parent as? android.view.View)?.let { parent ->
                    (binding.root as? android.widget.ScrollView)?.smoothScrollTo(0, parent.bottom)
                }
            }
        }
    }

    private val scannerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val payload = result.data?.getStringExtra("SCAN_RESULT").orEmpty()
            if (payload.isBlank()) return@registerForActivityResult
            handleScannedPayload(payload)
        }

    private val btPermissionLauncher = registerForActivityResult(RequestMultiplePermissions()) { map ->
        val granted = map.values.all { it }
        val action = pendingAction
        pendingAction = null
        if (granted) {
            ensureBluetoothEnabled(action)
        } else {
            showBluetoothPermissionDialog()
        }
        refreshReadinessState()
    }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val action = pendingAction
            pendingAction = null
            if (result.resultCode == Activity.RESULT_OK) {
                action?.invoke()
            } else {
                toastError(R.string.content_transfer_bt_enable_required)
            }
            refreshReadinessState()
        }

    private val discoverableLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) {
                toastError(R.string.content_transfer_bt_enable_required)
                return@registerForActivityResult
            }
            val action = pendingAction
            pendingAction = null
            action?.invoke()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentViewWithToolbar(
            binding.root,
            showHomeAsUp = true,
            title = getString(R.string.content_transfer_title)
        )

        applyCustomization()

        binding.btnStealthGenerate.setOnClickListener { showServerPickDialogAndGenerate() }
        binding.btnStealthReceive.setOnClickListener { openScannerForReceive() }
        binding.btnBlueSend.setOnClickListener {
            ensureBluetoothPermissions {
                showServerPickDialogAndStartBlueSender()
            }
        }
        binding.btnBlueReceive.setOnClickListener {
            ensureBluetoothPermissions {
                openScannerForReceive()
            }
        }
        binding.btnStopSessions.setOnClickListener {
            stopAllSessions()
        }
        binding.btnFixRequirements.setOnClickListener {
            assistRequirements()
        }
        binding.ivQrPreview.isVisible = false
        binding.tvQrInfo.text = getString(R.string.content_transfer_idle_info)
        binding.tvSessionStatus.text = getString(R.string.content_transfer_status_idle)
        updateBusyState(false)
        refreshReadinessState()
    }

    private fun applyCustomization() {
        val root = binding.root
        CustomizationManager.applyBackgroundGradient(root)
        CustomizationManager.applyTypography(root)
        
        // Style Cards
        val cards = listOf(
            binding.layoutTransferReadiness,
            binding.root.findViewById<android.view.View>(R.id.layout_transfer_stealth),
            binding.root.findViewById<android.view.View>(R.id.layout_transfer_blue),
            binding.ivQrPreview,
            binding.layoutTransferStatus,
            binding.layoutTransferLog
        )
        cards.forEach { card ->
            card?.let { CustomizationManager.applyCardStyle(it) }
        }

        // Style Buttons
        CustomizationManager.applyButtonStyle(binding.btnStealthGenerate, true)
        CustomizationManager.applyButtonStyle(binding.btnStealthReceive, false)
        CustomizationManager.applyButtonStyle(binding.btnBlueSend, true)
        CustomizationManager.applyButtonStyle(binding.btnBlueReceive, false)
        CustomizationManager.applyButtonStyle(binding.btnFixRequirements, true)
        
        val stopButtonTint = android.content.res.ColorStateList.valueOf(CustomizationManager.opaque(CustomizationManager.DEFAULT_ERROR_COLOR))
        binding.btnStopSessions.backgroundTintList = stopButtonTint
        binding.btnStopSessions.setTextColor(CustomizationManager.opaque(CustomizationManager.getBgStart()))
        binding.btnStopSessions.iconTint = android.content.res.ColorStateList.valueOf(CustomizationManager.opaque(CustomizationManager.getBgStart()))

        // Progress bar
        binding.progressTransfer.indeterminateTintList = android.content.res.ColorStateList.valueOf(CustomizationManager.opaque(CustomizationManager.getAccent()))
    }

    override fun onResume() {
        super.onResume()
        refreshReadinessState()
    }

    private fun openScannerForReceive() {
        scannerLauncher.launch(Intent(this, ScannerActivity::class.java))
    }

    private fun showServerPickDialogAndGenerate() {
        val allGuids = MmkvManager.decodeAllServerList()
        if (allGuids.isEmpty()) {
            toastError(R.string.toast_none_data)
            return
        }
        val items = allGuids.map { guid ->
            val cfg = MmkvManager.decodeServerConfig(guid)
            cfg?.remarks?.ifBlank { cfg.server ?: guid } ?: guid
        }.toTypedArray()
        val checked = BooleanArray(items.size)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.content_transfer_pick_servers))
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
                val selectedCount = checked.count { it }
                if (selectedCount > 10) {
                    checked[which] = false
                    toastError(R.string.content_transfer_pick_limit)
                }
            }
            .setPositiveButton(R.string.content_transfer_start) { _, _ ->
                val selectedGuids = allGuids.filterIndexed { index, _ -> checked[index] }
                if (selectedGuids.isEmpty()) {
                    toastError(R.string.content_transfer_pick_required)
                    return@setPositiveButton
                }
                val result = ContentTransferUtil.buildStealthQrPayload(selectedGuids)
                if (result == null) {
                    toastError(R.string.toast_failure)
                    return@setPositiveButton
                }
                val (payload, count) = result
                val qr = QRCodeDecoder.createCompactQRCode(payload)
                if (qr == null) {
                    toastError(R.string.content_transfer_qr_too_dense)
                    return@setPositiveButton
                }
                binding.ivQrPreview.isVisible = true
                binding.ivQrPreview.setImageBitmap(qr)
                binding.tvQrInfo.text = getString(R.string.content_transfer_qr_info, count, payload.length)
                binding.tvSessionStatus.text = getString(R.string.content_transfer_status_qr_ready)
                toastSuccess(R.string.toast_success)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importFromStealthPayload(payload: String) {
        val decoded = runCatching { ContentTransferUtil.decodeStealthQrPayload(payload) }.getOrNull()
        if (decoded == null) {
            toastError(R.string.content_transfer_invalid_payload)
            return
        }
        val (countCfg, countSub, subName) = importToNewSubscription(decoded.configs)
        if (countCfg > 0 || countSub > 0) {
            toastSuccess(getString(R.string.content_transfer_import_ok, countCfg, countSub))
            toastSuccess(getString(R.string.content_transfer_import_sub_created, subName))
        } else {
            toastError(R.string.content_transfer_import_fail)
        }
    }

    private fun handleScannedPayload(payload: String) {
        when (ContentTransferUtil.decodeMethod(payload)) {
            "stealth_qr" -> importFromStealthPayload(payload)
            "blue_bridge" -> receiveByBlueBridge(payload)
            else -> toastError(R.string.content_transfer_invalid_payload)
        }
    }

    private fun showServerPickDialogAndStartBlueSender() {
        showServerPickDialog { selected ->
            startBlueSender(selected)
        }
    }

    private fun showServerPickDialog(onSelected: (List<String>) -> Unit) {
        val allGuids = MmkvManager.decodeAllServerList()
        if (allGuids.isEmpty()) {
            toastError(R.string.toast_none_data)
            return
        }
        val items = allGuids.map { guid ->
            val cfg = MmkvManager.decodeServerConfig(guid)
            cfg?.remarks?.ifBlank { cfg.server ?: guid } ?: guid
        }.toTypedArray()
        val checked = BooleanArray(items.size)

        val subscriptions = MmkvManager.decodeSubscriptions()
        val subNames = subscriptions.map { it.subscription.remarks.ifBlank { it.guid } }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.content_transfer_pick_servers))
            .setItems(subNames) { _, which ->
                val sub = subscriptions[which]
                val subServerGuids = MmkvManager.decodeServerList(sub.guid)
                subServerGuids.forEach { subGuid ->
                    val index = allGuids.indexOf(subGuid)
                    if (index >= 0) {
                        checked[index] = true
                    }
                }
                showServerPickDialogWithChecked(allGuids, items, checked, onSelected)
            }
            .setNeutralButton(R.string.content_transfer_pick_custom) { _, _ ->
                showServerPickDialogWithChecked(allGuids, items, checked, onSelected)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showServerPickDialogWithChecked(
        allGuids: List<String>,
        items: Array<String>,
        checked: BooleanArray,
        onSelected: (List<String>) -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.content_transfer_pick_servers))
            .setMultiChoiceItems(items, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.content_transfer_generate_qr) { _, _ ->
                val selectedGuids = allGuids.filterIndexed { index, _ -> checked[index] }
                if (selectedGuids.isEmpty()) {
                    toastError(R.string.content_transfer_pick_required)
                    return@setPositiveButton
                }
                onSelected(selectedGuids)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun startBlueSender(selectedGuids: List<String>) {
        runCatching {
            val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
            }
            startActivity(discoverableIntent)
        }
        lifecycleScope.launch {
            stopRequested = false
            updateBusyState(true)
            var startError: String? = null
            blueSenderSession?.let { ContentTransferRuntime.stopBlueSender(it) }
            val session = ContentTransferRuntime.startBlueSender(
                context = this@ContentTransferActivity,
                scope = lifecycleScope,
                selectedGuids = selectedGuids,
                onStatus = { text ->
                    runOnUiThread {
                        binding.tvQrInfo.text = text
                        binding.tvSessionStatus.text = text
                    }
                },
                onError = { err ->
                    startError = err
                }
            )
            if (session == null) {
                updateBusyState(false)
                val msg = startError?.takeIf { it.isNotBlank() }?.let {
                    "${getString(R.string.content_transfer_blue_start_fail)}: $it"
                } ?: getString(R.string.content_transfer_blue_start_fail)
                toastError(msg)
                return@launch
            }
            blueSenderSession = session
            showMarker(
                payload = session.markerPayload,
                info = getString(R.string.content_transfer_blue_marker_info)
            )
            updateBusyState(false)
        }
    }

    private fun receiveByBlueBridge(payload: String) {
        val marker = ContentTransferUtil.decodeBlueBridgeMarker(payload)
        if (marker == null) {
            toastError(R.string.content_transfer_invalid_payload)
            return
        }
        lifecycleScope.launch {
            stopRequested = false
            updateBusyState(true)
            appendLog("Начало получения через Синий мост")
            appendLog("Маркер: addr=${marker.addr}, name=${marker.name}")
            var receiveError: String? = null
            val decoded = runCatching {
                ContentTransferRuntime.receiveBlueBridge(
                    context = this@ContentTransferActivity,
                    marker = marker,
                    onStatus = { text ->
                        appendLog(text)
                        runOnUiThread {
                            binding.tvQrInfo.text = text
                            binding.tvSessionStatus.text = text
                        }
                    },
                    onError = { err ->
                        appendLog("ОШИБКА: $err")
                        receiveError = err
                    }
                )
            }.getOrNull()
            if (decoded == null) {
                updateBusyState(false)
                if (!stopRequested) {
                    val msg = receiveError?.takeIf { it.isNotBlank() }?.let {
                        "${getString(R.string.content_transfer_blue_receive_fail)}: $it"
                    } ?: getString(R.string.content_transfer_blue_receive_fail)
                    toastError(msg)
                    binding.tvSessionStatus.text = getString(R.string.content_transfer_status_idle)
                }
                return@launch
            }
            importConfigs(decoded.configs)
            updateBusyState(false)
        }
    }

    private fun importConfigs(configs: List<String>) {
        appendLog("Импорт ${configs.size} конфигов...")
        if (configs.isEmpty()) {
            appendLog("ОШИБКА: нет конфигов для импорта")
            toastError(R.string.content_transfer_import_fail)
            return
        }
        configs.forEachIndexed { i, cfg ->
            appendLog("Конфиг $i: ${cfg.take(50)}...")
        }
        val (countCfg, countSub, subName) = importToNewSubscription(configs)
        appendLog("Результат: конфигов=$countCfg, подписок=$countSub")
        if (countCfg > 0 || countSub > 0) {
            MessageUtil.sendMsg2UI(this, AppConfig.MSG_RELOAD_SERVER_LIST, "")
            toastSuccess(getString(R.string.content_transfer_import_ok, countCfg, countSub))
            toastSuccess(getString(R.string.content_transfer_import_sub_created, subName))
        } else {
            appendLog("ОШИБКА: импорт не удался")
            toastError(R.string.content_transfer_import_fail)
        }
    }

    private fun importToNewSubscription(configs: List<String>): Triple<Int, Int, String> {
        val subId = UUID.randomUUID().toString()
        val subName = buildTransferSubscriptionName()
        val subItem = SubscriptionItem(
            remarks = subName,
            url = "v2plus://content-transfer/$subId",
            enabled = true,
            autoUpdate = false
        )
        MmkvManager.encodeSubscription(subId, subItem)
        var countCfg = 0
        for (configStr in configs) {
            try {
                val config = JsonUtil.fromJson(configStr, ProfileItem::class.java)
                if (config != null) {
                    config.subscriptionId = subId
                    val guid = UUID.randomUUID().toString()
                    MmkvManager.encodeServerConfig(guid, config)
                    countCfg++
                    appendLog("Сохранен конфиг: ${config.remarks ?: guid}")
                } else {
                    appendLog("ОШИБКА: не удалось распарсить конфиг")
                }
            } catch (e: Exception) {
                appendLog("ОШИБКА: ${e.message}")
            }
        }
        return Triple(countCfg, if (countCfg > 0) 1 else 0, subName)
    }

    private fun buildTransferSubscriptionName(): String {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        return "Transfer $time"
    }

    private fun showMarker(payload: String, info: String) {
        val qr = QRCodeDecoder.createCompactQRCode(payload)
        if (qr == null) {
            toastError(R.string.content_transfer_qr_too_dense)
            return
        }
        binding.ivQrPreview.isVisible = true
        binding.ivQrPreview.setImageBitmap(qr)
        binding.tvQrInfo.text = info
    }

    private fun stopAllSessions() {
        lifecycleScope.launch {
            stopRequested = true
            updateBusyState(true)
            withTimeoutOrNull(2500) {
                ContentTransferRuntime.stopBlueSender(blueSenderSession)
            }
            blueSenderSession?.job?.cancel()
            blueSenderSession = null
            binding.tvSessionStatus.text = getString(R.string.content_transfer_status_stopped)
            updateBusyState(false)
        }
    }

    private fun updateBusyState(busy: Boolean) {
        binding.progressTransfer.isVisible = busy
        binding.btnStopSessions.isEnabled = busy || blueSenderSession != null
    }

    private fun ensureLocationEnabled(onEnabled: () -> Unit) {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
        val enabled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm?.isLocationEnabled == true
        } else {
            lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true ||
                lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true
        }
        if (enabled) {
            onEnabled()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.content_transfer_loc_req)
            .setMessage(R.string.content_transfer_loc_req_msg)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                pendingAction = onEnabled
                startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun ensureBluetoothPermissions(onGranted: () -> Unit) {
        val permissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }
        val missing = permissions.filter {
            checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            ensureLocationEnabled { ensureBluetoothEnabled(onGranted) }
            return
        }
        pendingAction = { ensureLocationEnabled { ensureBluetoothEnabled(onGranted) } }
        btPermissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureBluetoothEnabled(onGranted: (() -> Unit)?) {
        val adapter = getBluetoothAdapter()
        if (adapter == null) {
            toastError(R.string.content_transfer_bt_not_supported)
            return
        }
        if (adapter.isEnabled) {
            onGranted?.invoke()
            return
        }
        pendingAction = onGranted
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        enableBluetoothLauncher.launch(intent)
    }

    private fun showBluetoothPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.content_transfer_requirements_title)
            .setMessage(R.string.content_transfer_bt_permission_required)
            .setPositiveButton(R.string.content_transfer_open_settings) { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        return (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    }

    private fun hasBluetoothPermissionGranted(): Boolean {
        val hasLoc = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hasLoc
        } else {
            hasLoc && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasCameraPermissionGranted(): Boolean {
        return checkSelfPermission(Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun refreshReadinessState() {
        val adapter = getBluetoothAdapter()
        val btSupported = adapter != null
        val btEnabled = adapter?.isEnabled == true
        val btPerm = hasBluetoothPermissionGranted()
        val camera = hasCameraPermissionGranted()

        binding.tvReadyBluetooth.text = getString(
            if (btSupported && btEnabled) R.string.content_transfer_ready_bt_ok else R.string.content_transfer_ready_bt_missing
        )
        binding.tvReadyBtPermissions.text = getString(
            if (btPerm) R.string.content_transfer_ready_bt_perm_ok else R.string.content_transfer_ready_bt_perm_missing
        )
        binding.tvReadyNetwork.text = getString(R.string.content_transfer_ready_network_ok)
        binding.tvReadyCamera.text = getString(
            if (camera) R.string.content_transfer_ready_camera_ok else R.string.content_transfer_ready_camera_missing
        )

        setReadinessIndicator(binding.viewReadyBluetoothDot, btSupported && btEnabled)
        setReadinessIndicator(binding.viewReadyBtPermissionsDot, btPerm)
        setReadinessIndicator(binding.viewReadyNetworkDot, true)
        setReadinessIndicator(binding.viewReadyCameraDot, camera)
    }

    private fun setReadinessIndicator(view: android.view.View, ok: Boolean) {
        val color = if (ok) {
            ContextCompat.getColor(this, R.color.md_theme_tertiary)
        } else {
            ContextCompat.getColor(this, R.color.md_theme_error)
        }
        view.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun assistRequirements() {
        val adapter = getBluetoothAdapter()
        if (adapter == null) {
            toastError(R.string.content_transfer_bt_not_supported)
            return
        }
        if (!adapter.isEnabled) {
            ensureBluetoothEnabled(null)
            return
        }
        if (!hasBluetoothPermissionGranted()) {
            ensureBluetoothPermissions {}
            return
        }
        if (!hasCameraPermissionGranted()) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), 7771)
            return
        }
        toastSuccess(R.string.content_transfer_requirements_ready)
    }

    private fun installDownloadedApk(apkFile: File) {
        val uri = FileProvider.getUriForFile(
            this,
            BuildConfig.APPLICATION_ID + ".cache",
            apkFile
        )
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            data = uri
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        lifecycleScope.launch {
            ContentTransferRuntime.stopBlueSender(blueSenderSession)
        }
        super.onDestroy()
    }
}
