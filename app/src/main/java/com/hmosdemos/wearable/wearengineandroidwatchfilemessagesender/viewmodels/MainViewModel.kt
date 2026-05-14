package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.huawei.wearengine.device.Device
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.domain.models.UiState
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.AuthManager
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.DeviceManager
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.P2pManager
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service.WatchMessenger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.SimpleDateFormat
import kotlin.coroutines.resume

class MainViewModel(
    private val authManager: AuthManager,
    private val deviceManager: DeviceManager,
    private val p2pManager: P2pManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var currentConnectedDevice: Device? = null

    init {
        p2pManager.setPeerPkgName()
        // Let the foreground service auto-bind through us when no device is bound.
        WatchMessenger.setAutoBinder { onResult ->
            autoBindConnectedDeviceForService(onResult)
        }
        checkPermissionsAndLoadDevices()
    }

    private fun checkPermissionsAndLoadDevices() {
        authManager.checkPermissions(object : AuthManager.AuthCheckCallback {
            override fun onResult(allPermissionsGranted: Boolean) {
                if (allPermissionsGranted) {
                    loadDevices()
                    return
                }

                requestDevicePermission()
            }

            override fun onError(e: Exception?) {
                requestDevicePermission()
            }
        })
    }

    fun requestDevicePermission(onSuccess: Runnable? = null, onCancel: Runnable? = null) {
        authManager.requestPermission(onSuccess, onCancel)
    }

    fun selectDevice(device: Device) {
        _uiState.value = _uiState.value.copy(
            deviceState = _uiState.value.deviceState.copy(selectedDevice = device)
        )

        // Make the same send path available to the foreground service, so it can
        // send heartbeats to this device every 5s while running.
        WatchMessenger.bind(p2pManager, device)

        p2pManager.registerReceiver(device, object : P2pManager.MessageListener {
            override fun onMessageReceived(message: String?) {
                message?.let { msg ->
                    val currentReceivedMessageList = _uiState.value.receivedMessages.toMutableList()
                    currentReceivedMessageList.add(msg)
                    _uiState.update {
                        it.copy(receivedMessages = currentReceivedMessageList.toList())
                    }
                    // Also surface the message on the foreground-service screen.
                    WatchMessenger.recordReceived(msg)
                }
            }

            override fun onMessageSent(message: String?) {
                // TODO add log.
            }
        })

        addLogMessage("Selected device: ${device.name}")
        viewModelScope.launch {
            connectToDevice(device)
        }
    }

    fun updateMessageText(text: String) {
        _uiState.value = _uiState.value.copy(
            messageState = _uiState.value.messageState.copy(text = text)
        )
    }

    fun sendPing() {
        val selectedDevice = _uiState.value.deviceState.selectedDevice
        if (selectedDevice == null) {
            addLogMessage("No device selected for ping")
            return
        }
        viewModelScope.launch {
            val peerPackageName: String? = "com.sample.trdtse.payment.lite"

            p2pManager.pingDevice(selectedDevice, peerPackageName, { result ->
                addLogMessage("Send Ping Result: $result")
            }, { error ->
                addLogMessage("Ping failed: ${error.message}")
            })
        }
    }

    fun sendMessage(message: String) {
        val selectedDevice = _uiState.value.deviceState.selectedDevice
        if (selectedDevice == null) {
            addLogMessage("No device selected for message")
            return
        }

        _uiState.value = _uiState.value.copy(
            messageState = _uiState.value.messageState.copy(isSending = true)
        )

        viewModelScope.launch {
            addLogMessage("selectedDevice: $selectedDevice")

            p2pManager.sendMessage(selectedDevice, message, { result ->
                _uiState.value = _uiState.value.copy(
                    messageState = _uiState.value.messageState.copy(
                        isSending = false,
                        text = ""
                    )
                )
                addLogMessage("Message sent: $message")
            }, { error ->
                _uiState.value = _uiState.value.copy(
                    messageState = _uiState.value.messageState.copy(isSending = false)
                )
                Log.d(
                    "Send Message Error",
                    "sendMessage: ${error.message} ${error.hashCode()} ${error.cause}"
                )
                addLogMessage("Message failed: ${error.message}")
            })
        }
    }

    fun selectFile(uri: Uri, fileName: String?) {
        _uiState.value = _uiState.value.copy(
            fileState = _uiState.value.fileState.copy(
                selectedUri = uri,
                selectedFileName = fileName
            )
        )
        addLogMessage("File selected: ${fileName ?: "Unknown"}")
    }

    fun sendFile(context: Context) {
        val selectedDevice = _uiState.value.deviceState.selectedDevice

        if (selectedDevice == null) {
            addLogMessage("Cannot send file: device or file not selected")
            return
        }

        _uiState.value = _uiState.value.copy(
            fileState = _uiState.value.fileState.copy(isSending = true)
        )

        viewModelScope.launch {
            p2pManager.sendFile(
                context,
                selectedDevice,
                {
                    _uiState.value = _uiState.value.copy(
                        fileState = _uiState.value.fileState.copy(
                            isSending = false,
                            selectedUri = null,
                            selectedFileName = null
                        )
                    )
                },
                { error ->
                    _uiState.value = _uiState.value.copy(
                        fileState = _uiState.value.fileState.copy(isSending = false)
                    )
                    addLogMessage("File failed: ${error.message}")
                }
            )
        }
    }

    fun refreshDevices() {
        loadDevices()
    }

    /**
     * Used by the foreground-service screen so receiving works without the
     * user having to manually pick a device on the first screen.
     *
     * Loads bonded devices, picks the first one that reports `isConnected`,
     * and runs it through the existing [selectDevice] path so the same
     * receiver + WatchMessenger binding is set up.
     */
    fun autoBindConnectedDeviceForService(onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            deviceManager.getBondedDevices({ deviceList ->
                val devices = deviceList?.filterNotNull().orEmpty()
                if (devices.isEmpty()) {
                    onResult(false, "No bonded devices")
                    return@getBondedDevices
                }
                val connected = devices.firstOrNull { runCatching { it.isConnected }.getOrDefault(false) }
                if (connected == null) {
                    onResult(false, "No connected device (${devices.size} bonded)")
                    return@getBondedDevices
                }
                selectDevice(connected)
                onResult(true, "Bound to ${connected.name}")
            }, { error ->
                onResult(false, error.message ?: "Device load failed")
            })
        }
    }

    fun clearLogs() {
        _uiState.value = _uiState.value.copy(
            receivedMessages = emptyList(),
            logMessages = emptyList()
        )
    }

    private suspend fun connectToDevice(device: Device): String =
        suspendCancellableCoroutine { continuation ->
            updateConnectedDevice(device)
            continuation.resume("Connected to ${device.name}")
        }

    private fun updateConnectedDevice(device: Device?) {
        currentConnectedDevice = device
    }

    private fun loadDevices() {
        _uiState.value = _uiState.value.copy(
            deviceState = _uiState.value.deviceState.copy(isLoading = true)
        )

        viewModelScope.launch {
            deviceManager.getBondedDevices({ deviceList ->
                _uiState.value = _uiState.value.copy(
                    deviceState = _uiState.value.deviceState.copy(
                        devices = deviceList?.filterNotNull() ?: emptyList(),
                        isLoading = false,
                        permissionsGranted = true
                    )
                )
                if (deviceList?.filterNotNull().isNullOrEmpty()) {
                    addLogMessage("No bonded devices found")
                } else {
                    addLogMessage("Found ${deviceList.filterNotNull().size} bonded devices")
                }
            }, { error ->
                _uiState.value = _uiState.value.copy(
                    deviceState = _uiState.value.deviceState.copy(
                        isLoading = false,
                        permissionsGranted = false
                    )
                )
                addLogMessage("Device loading failed: ${error.message}")
            })
        }
    }

    @SuppressLint("SimpleDateFormat")
    private fun addLogMessage(message: String) {
        val timestamp = System.currentTimeMillis()
        val timestampedMessage =
            "[${SimpleDateFormat("HH:mm:ss").format(timestamp)}] $message"

        _uiState.value = _uiState.value.copy(
            logMessages = _uiState.value.logMessages + timestampedMessage
        )
    }
}