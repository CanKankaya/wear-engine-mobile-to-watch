package com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.service

import android.util.Log
import com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.managers.P2pManager
import com.huawei.wearengine.device.Device
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide holder so [WatchLinkService] can reuse the exact same
 * [P2pManager.sendMessage] path the UI uses, against the device the user
 * selected in [com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels.MainViewModel].
 *
 * Lives as long as the app process. The foreground service keeps the process
 * alive even when the Activity is gone, so a device bound here from the UI
 * remains usable from the service.
 */
object WatchMessenger {

    private const val TAG = "WatchMessenger"
    private const val MAX_LOG_ENTRIES = 100

    enum class Status { SENDING, SENT, FAILED, SKIPPED }

    data class Entry(
        val seq: Long,
        val timestampMs: Long,
        val text: String,
        val status: Status,
        val detail: String? = null,
    )

    @Volatile private var p2pManager: P2pManager? = null
    @Volatile private var device: Device? = null

    /**
     * Optional auto-bind hook the [com.hmosdemos.wearable.wearengineandroidwatchfilemessagesender.viewmodels.MainViewModel]
     * registers. Lets the foreground service ask the app to find a connected
     * watch (without the user picking one manually) every send tick.
     */
    @Volatile private var autoBinder: ((onResult: (Boolean, String) -> Unit) -> Unit)? = null

    private val _autoBindStatus = MutableStateFlow<String?>(null)
    val autoBindStatus: StateFlow<String?> = _autoBindStatus.asStateFlow()

    fun setAutoBinder(binder: (onResult: (Boolean, String) -> Unit) -> Unit) {
        autoBinder = binder
    }

    /**
     * Triggers a one-shot auto-bind attempt if no device is currently bound.
     * Safe to call from the service every tick.
     */
    fun tryAutoBind() {
        if (device != null) return
        val binder = autoBinder ?: run {
            _autoBindStatus.value = "app not ready"
            return
        }
        _autoBindStatus.value = "searching\u2026"
        binder { ok, msg ->
            _autoBindStatus.value = (if (ok) "\u2713 " else "\u2717 ") + msg
        }
    }

    private val _sentCount = MutableStateFlow(0L)
    val sentCount: StateFlow<Long> = _sentCount.asStateFlow()

    private val _failedCount = MutableStateFlow(0L)
    val failedCount: StateFlow<Long> = _failedCount.asStateFlow()

    private val _lastEntry = MutableStateFlow<Entry?>(null)
    val lastEntry: StateFlow<Entry?> = _lastEntry.asStateFlow()

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    data class ReceivedEntry(
        val seq: Long,
        val timestampMs: Long,
        val text: String,
    )

    private val _receivedCount = MutableStateFlow(0L)
    val receivedCount: StateFlow<Long> = _receivedCount.asStateFlow()

    private val _receivedEntries = MutableStateFlow<List<ReceivedEntry>>(emptyList())
    val receivedEntries: StateFlow<List<ReceivedEntry>> = _receivedEntries.asStateFlow()

    private val _lastReceived = MutableStateFlow<ReceivedEntry?>(null)
    val lastReceived: StateFlow<ReceivedEntry?> = _lastReceived.asStateFlow()

    fun recordReceived(text: String) {
        val seq = _receivedCount.value + 1
        _receivedCount.value = seq
        val entry = ReceivedEntry(seq, System.currentTimeMillis(), text)
        _lastReceived.value = entry
        _receivedEntries.update { current ->
            val next = current + entry
            if (next.size > MAX_LOG_ENTRIES) next.takeLast(MAX_LOG_ENTRIES) else next
        }
    }

    fun clearReceivedLog() {
        _receivedEntries.value = emptyList()
        _lastReceived.value = null
        _receivedCount.value = 0
    }

    fun bind(p2pManager: P2pManager, device: Device) {
        this.p2pManager = p2pManager
        this.device = device
    }

    fun clearDevice() {
        this.device = null
    }

    fun hasDevice(): Boolean = device != null

    fun clearLog() {
        _entries.value = emptyList()
        _lastEntry.value = null
        _sentCount.value = 0
        _failedCount.value = 0
    }

    /**
     * Sends [text] using the same Wear Engine path the first screen uses:
     * [P2pManager.sendMessage] -> Message.Builder().setPayload(bytes).build().
     *
     * Outcomes are recorded into [entries] so the UI mirrors the first
     * screen's behaviour (success / failure both surfaced to the user).
     */
    fun send(seq: Long, text: String) {
        val mgr = p2pManager
        val dev = device
        if (mgr == null || dev == null) {
            record(Entry(seq, System.currentTimeMillis(), text, Status.SKIPPED, "no device selected"))
            return
        }

        record(Entry(seq, System.currentTimeMillis(), text, Status.SENDING))

        try {
            mgr.sendMessage(
                dev,
                text,
                { _ ->
                    _sentCount.value = _sentCount.value + 1
                    record(Entry(seq, System.currentTimeMillis(), text, Status.SENT))
                },
                { err ->
                    Log.e(TAG, "send failed: ${err.message}", err)
                    _failedCount.value = _failedCount.value + 1
                    record(
                        Entry(
                            seq,
                            System.currentTimeMillis(),
                            text,
                            Status.FAILED,
                            err.message ?: err::class.java.simpleName
                        )
                    )
                    // Drop the bound device so the next tick re-binds via
                    // tryAutoBind(). Failures here usually mean the watch
                    // disconnected or Wear Engine lost its session.
                    onSendFailure()
                }
            )
        } catch (t: Throwable) {
            Log.e(TAG, "send threw: ${t.message}", t)
            _failedCount.value = _failedCount.value + 1
            record(
                Entry(
                    seq,
                    System.currentTimeMillis(),
                    text,
                    Status.FAILED,
                    t.message ?: t::class.java.simpleName
                )
            )
            onSendFailure()
        }
    }

    private fun onSendFailure() {
        device = null
        _autoBindStatus.value = "rebind needed (last send failed)"
    }

    private fun record(entry: Entry) {
        _lastEntry.value = entry
        _entries.update { current ->
            val next = current + entry
            if (next.size > MAX_LOG_ENTRIES) next.takeLast(MAX_LOG_ENTRIES) else next
        }
    }

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun formatTime(timestampMs: Long): String = timeFormat.format(Date(timestampMs))
}
