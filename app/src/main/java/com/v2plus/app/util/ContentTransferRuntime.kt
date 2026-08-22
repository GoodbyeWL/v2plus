package com.v2plus.app.util

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.v2plus.app.handler.MmkvManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID

object ContentTransferRuntime {
    private const val TAG = "ContentTransfer"
    private const val SPP_UUID = "00001101-0000-1000-8000-00805F9B34FB"

    data class BlueSenderSession(
        val markerPayload: String,
        val serverSocket: BluetoothServerSocket,
        val job: Job
    )

    private fun hasBluetoothPermissions(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val connect = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            val scan = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            return connect && scan
        }
        return true
    }

    suspend fun startBlueSender(
        context: Context,
        scope: CoroutineScope,
        selectedGuids: List<String>,
        onStatus: (String) -> Unit = {},
        onError: (String) -> Unit = {}
    ): BlueSenderSession? = withContext(Dispatchers.IO) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: run {
            onError("Bluetooth не поддерживается")
            return@withContext null
        }
        if (!adapter.isEnabled) {
            onError("Bluetooth выключен")
            return@withContext null
        }
        if (!hasBluetoothPermissions(context)) {
            onError("Нет разрешений для работы с Bluetooth")
            return@withContext null
        }
        val uuid = UUID.fromString(SPP_UUID)
        val serverSocket: BluetoothServerSocket
        try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("V2plusTransfer", uuid)
        } catch (e: Exception) {
            onError(e.message ?: "Не удалось создать Bluetooth-сервер")
            return@withContext null
        }
        val name = adapter.name ?: "Android"
        val marker = ContentTransferUtil.buildBlueBridgeMarker(SPP_UUID, adapter.address, name)
        val job = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val socket = serverSocket.accept() ?: break
                    handleBlueSenderConnection(socket, selectedGuids, context)
                }
            } catch (_: Exception) {
            } finally {
                runCatching { serverSocket.close() }
            }
        }
        BlueSenderSession(marker, serverSocket, job)
    }

    private suspend fun handleBlueSenderConnection(
        socket: BluetoothSocket,
        selectedGuids: List<String>,
        context: Context
    ) {
        try {
            val output = socket.outputStream
            val input = socket.inputStream
            val configs = mutableListOf<String>()
            for (guid in selectedGuids) {
                val config = MmkvManager.decodeServerConfig(guid) ?: continue
                val json = JsonUtil.toJson(config)
                configs.add(json)
            }
            val payload = configs.joinToString("\n")
            val data = payload.toByteArray(Charsets.UTF_8)
            val lenBytes = ByteBuffer.allocate(4).putInt(data.size).array()
            output.write(lenBytes)
            output.write(data)
            output.flush()
            val ack = ByteArray(1)
            input.read(ack)
        } catch (e: Exception) {
            Log.e(TAG, "Blue sender connection error", e)
        } finally {
            runCatching { socket.close() }
        }
    }

    suspend fun stopBlueSender(session: BlueSenderSession?) {
        if (session == null) return
        runCatching { session.serverSocket.close() }
        session.job.cancel()
        withTimeoutOrNull(1500) { session.job.join() }
    }

    suspend fun receiveBlueBridge(
        context: Context,
        marker: ContentTransferUtil.BlueBridgeMarker,
        onStatus: (String) -> Unit,
        onError: (String) -> Unit = {}
    ): ContentTransferUtil.DecodeResult? = withContext(Dispatchers.IO) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: run {
            onError("Bluetooth не поддерживается")
            return@withContext null
        }
        if (!adapter.isEnabled) {
            onError("Bluetooth выключен")
            return@withContext null
        }
        if (!hasBluetoothPermissions(context)) {
            onError("Нет разрешений для работы с Bluetooth")
            return@withContext null
        }
        val uuid = runCatching { UUID.fromString(marker.uuid) }.getOrNull() ?: run {
            onError("Неверный идентификатор сессии")
            return@withContext null
        }
        val macRegex = Regex("^[0-9A-F]{2}(:[0-9A-F]{2}){5}$")
        val markerAddr = marker.addr.uppercase(Locale.US)
        val addressLooksValid = markerAddr.matches(macRegex) && markerAddr != "02:00:00:00:00:00"

        onStatus("Синий мост: поиск устройства…")

        val discoveredDevices = mutableListOf<BluetoothDevice>()
        val discoveryLatch = kotlinx.coroutines.channels.Channel<Unit>(1)
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val action = intent.action
                if (BluetoothDevice.ACTION_FOUND == action) {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice
                    } ?: return
                    synchronized(discoveredDevices) {
                        if (discoveredDevices.none { it.address == device.address }) {
                            discoveredDevices.add(device)
                        }
                    }
                } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED == action) {
                    runCatching { discoveryLatch.trySend(Unit) }
                }
            }
        }
        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        context.registerReceiver(receiver, filter)
        runCatching { adapter.cancelDiscovery() }
        kotlinx.coroutines.delay(500)
        runCatching { adapter.startDiscovery() }

        var connectedSocket: BluetoothSocket? = null
        var lastError = "Не удалось установить Bluetooth-соединение"
        val triedAddresses = mutableSetOf<String>()
        val deadline = System.currentTimeMillis() + 45_000

        try {
            while (System.currentTimeMillis() < deadline && connectedSocket == null && isActive) {
                val candidates = mutableListOf<BluetoothDevice>()
                runCatching { adapter.bondedDevices }.getOrNull().orEmpty()
                    .filter { runCatching { it.name }.getOrNull() == marker.name }
                    .forEach { candidates.add(it) }
                synchronized(discoveredDevices) {
                    discoveredDevices.forEach { d ->
                        if (candidates.none { it.address == d.address } && runCatching { d.name }.getOrNull() == marker.name) {
                            candidates.add(d)
                        }
                    }
                }

                for (device in candidates) {
                    if (!isActive || connectedSocket != null) break
                    val addr = device.address
                    if (addr in triedAddresses) continue
                    triedAddresses.add(addr)
                    val deviceName = runCatching { device.name }.getOrNull() ?: addr
                    onStatus("Синий мост: попытка подключения к $deviceName…")
                    val socket = connectToBlueBridgeDevice(device, uuid)
                    if (socket != null) {
                        connectedSocket = socket
                        break
                    }
                }

                if (connectedSocket == null) {
                    onStatus("Синий мост: поиск устройства… (${((deadline - System.currentTimeMillis()) / 1000).toInt()}с)")
                    kotlinx.coroutines.delay(2000)
                }
            }
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
            runCatching { adapter.cancelDiscovery() }
        }

        if (connectedSocket == null) {
            lastError = "Устройство не найдено. Убедитесь, что отправитель нажал 'Отправить' и Bluetooth включён на обоих устройствах."
            onError(lastError)
            return@withContext null
        }

        val socket = connectedSocket
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val lenBytes = readNBytes(input, 4)
            val len = ByteBuffer.wrap(lenBytes).int
            val data = readNBytes(input, len)
            val payload = String(data, Charsets.UTF_8)
            output.write(1)
            output.flush()
            val lines = payload.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .toList()
            val result = if (lines.isNotEmpty()) ContentTransferUtil.DecodeResult(lines, lines.size) else null
            if (result != null) {
                onStatus("Синий мост: данные получены")
            }
            result
        } catch (e: Exception) {
            onError(e.message ?: "Ошибка при получении данных")
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun connectToBlueBridgeDevice(device: BluetoothDevice, uuid: UUID): BluetoothSocket? {
        try {
            val socket = device.createInsecureRfcommSocketToServiceRecord(uuid)
            socket.connect()
            return socket
        } catch (_: Exception) {
        }
        try {
            val socket = device.createRfcommSocketToServiceRecord(uuid)
            socket.connect()
            return socket
        } catch (_: Exception) {
        }
        try {
            val method = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val socket = method.invoke(device, 1) as BluetoothSocket
            socket.connect()
            return socket
        } catch (_: Exception) {
        }
        return null
    }

    private fun readNBytes(input: java.io.InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val read = input.read(buf, off, count - off)
            if (read < 0) break
            off += read
        }
        return buf
    }
}