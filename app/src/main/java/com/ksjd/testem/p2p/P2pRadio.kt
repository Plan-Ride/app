package com.ksjd.testem.p2p

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.AdvertiseSettings.Builder
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.UUID
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * 2.19.0: the radios for [P2pPack]. A phone offers the signed packets it has (fetched with data, or heard and passed on
 * without) over Bluetooth LE (two readable values any phone may read) and Google Nearby (sent to whoever connects). A
 * phone without data also listens: it reads what phones around it offer and keeps a packet only when emHUB's signature
 * fits. A listener sends nothing of its own. Both radios run, so phones without Google services (Huawei) take part over
 * plain Bluetooth LE. Every step goes to [P2pLog] for bug reports.
 *
 * ponytail: one listener connection at a time and each phone read at most once a minute; enough for a stop or a bus.
 */
@SuppressLint("MissingPermission") // the permissions are checked before anything starts
object P2pRadio {
    val SERVICE: UUID = UUID.fromString("5a7d0c3e-2b19-4f60-9c1a-6e8b3d2f0190")
    private val BUSES: UUID = UUID.fromString("5a7d0c3e-2b19-4f60-9c1a-6e8b3d2f0191")
    private val BOARD: UUID = UUID.fromString("5a7d0c3e-2b19-4f60-9c1a-6e8b3d2f0192")
    /** 2.19.0 questions and answers: three readable answers (older phones read them), and the broadcasts' own ids. */
    private val ANSWERS = (0 until 3).map { UUID.fromString("5a7d0c3e-2b19-4f60-9c1a-6e8b3d2f019${3 + it}") }
    private val QUESTION: UUID = UUID.fromString("5a7d0c3e-2b19-4f60-9c1a-6e8b3d2f01a0")
    private val ANSWER: UUID = UUID.fromString("5a7d0c3e-2b19-4f60-9c1a-6e8b3d2f01a1")
    private const val NEARBY_ID = "com.ksjd.testem.p2p"
    private const val READ_AGAIN_MS = 60_000L

    /** What this phone offers now (null: nothing). */
    @Volatile var buses: ByteArray? = null
        private set
    @Volatile var board: ByteArray? = null
        private set

    /** Answers this phone broadcasts now (boards for asked stops, fetched or passed on). */
    @Volatile var answers: List<ByteArray> = emptyList()
        private set
    private var asking: AdvertiseCallback? = null
    private var questionScan: ScanCallback? = null
    /** One-way answer broadcasts running (AdvertisingSetCallback, Android 8.0+; kept untyped so Android 7 never loads it). */
    private val answerSets = mutableListOf<Any>()
    private var gattServer: BluetoothGattServer? = null
    private var advertising: AdvertiseCallback? = null
    private var scanning: ScanCallback? = null
    private var nearbyOffering = false
    private var nearbyListening = false
    private val lastRead = mutableMapOf<String, Long>()
    @Volatile private var reading = false
    /** The last thing said about a problem, so it goes to the log once, not every round. */
    private var lastProblem = ""

    private fun problem(text: String) {
        if (text != lastProblem) P2pLog.add(text)
        lastProblem = text
    }

    /** For the bug report: which radios run now. */
    fun state(): String = "BLE advertising ${advertising != null}, BLE server ${gattServer != null}, BLE scanning ${scanning != null}, " +
        "Nearby offering $nearbyOffering, Nearby listening $nearbyListening, reading $reading, asking ${asking != null}, " +
        "listening for questions ${questionScan != null}, answers ${answers.size} (${answerSets.size} as broadcasts)"

    /** The permissions the radios need on this Android version. */
    fun permissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.NEARBY_WIFI_DEVICES
        )
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_ADVERTISE,
            android.Manifest.permission.BLUETOOTH_CONNECT, android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        else -> arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun missing(context: Context) = permissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    /** Bluetooth's own permissions (Nearby's Wi-Fi one is extra: without it Nearby is skipped, Bluetooth LE still runs). */
    private fun bluetoothAllowed(context: Context): Boolean {
        val missing = missing(context).filter { it != android.Manifest.permission.NEARBY_WIFI_DEVICES }
        if (missing.isNotEmpty()) problem("no permission: " + missing.joinToString { it.substringAfterLast('.') })
        return missing.isEmpty()
    }

    private fun nearbyAllowed(context: Context): Boolean {
        if (missing(context).isNotEmpty()) return false
        val services = runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        }.getOrDefault(-1)
        if (services != com.google.android.gms.common.ConnectionResult.SUCCESS) problem("Nearby: no Google Play services ($services), Bluetooth LE only")
        return services == com.google.android.gms.common.ConnectionResult.SUCCESS
    }

    private fun adapter(context: Context) = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }
        .also { if (it == null) problem("Bluetooth is off") }

    private fun advertiseError(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "data too large"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "too many advertisers"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "already started"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "internal error"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "not supported on this phone"
        else -> "error $code"
    }

    private fun scanError(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "already started"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "registration failed"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "internal error"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "not supported on this phone"
        5 -> "out of hardware resources"
        6 -> "scanning too often"
        else -> "error $code"
    }

    // ------------------------------------------------------------ offering (fetched with data, or passed on)

    fun offer(context: Context, buses: ByteArray?, board: ByteArray?) {
        if (buses == null && board == null) return stopOffering(context)
        if (!buses.contentEquals(this.buses) || !board.contentEquals(this.board)) {
            P2pLog.add("offering: buses ${buses?.size ?: 0} B, board ${board?.size ?: 0} B")
        }
        this.buses = buses
        this.board = board
        if (bluetoothAllowed(context)) offerBle(context)
        if (nearbyAllowed(context) && !nearbyOffering) {
            nearbyOffering = true
            Nearby.getConnectionsClient(context).startAdvertising("p", NEARBY_ID, sender(context),
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { P2pLog.add("Nearby: offering started") }
                .addOnFailureListener { nearbyOffering = false; P2pLog.add("Nearby: offering failed: ${it.message}") }
        }
    }

    private fun offerBle(context: Context) {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val bluetooth = adapter(context) ?: return
        if (gattServer == null) {
            gattServer = manager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
                    P2pLog.add("BLE server: ${P2pLog.who(device.address)} " +
                        (if (newState == BluetoothProfile.STATE_CONNECTED) "connected" else "disconnected") + if (status != 0) " (status $status)" else "")
                }

                override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                    val value = when (characteristic.uuid) {
                        BOARD -> board
                        BUSES -> buses
                        else -> answers.getOrNull(ANSWERS.indexOf(characteristic.uuid))
                    } ?: ByteArray(0)
                    if (value.isNotEmpty()) P2pStore.lastOfferedMs.value = System.currentTimeMillis()
                    val part = if (offset <= value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                    val sent = gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part) == true
                    P2pLog.add("BLE server: ${P2pLog.who(device.address)} read ${when (characteristic.uuid) { BOARD -> "board"; BUSES -> "buses"; else -> "answer" }}" +
                        (if (offset > 0) " from $offset" else "") + ", ${part.size} B" + if (!sent) ", answer FAILED" else "")
                }
            })?.also { server ->
                server.addService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                    (listOf(BUSES, BOARD) + ANSWERS).forEach {
                        addCharacteristic(BluetoothGattCharacteristic(it, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                    }
                })
                P2pLog.add("BLE server: open")
            }
            if (gattServer == null) problem("BLE server: could not open")
        }
        if (advertising == null) {
            val advertiser = bluetooth.bluetoothLeAdvertiser ?: return problem("BLE: this phone can't advertise")
            advertising = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { P2pLog.add("BLE: advertising") }
                override fun onStartFailure(errorCode: Int) { P2pLog.add("BLE: advertising failed: ${advertiseError(errorCode)}"); advertising = null }
            }
            advertiser.startAdvertising(
                Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
                // Only the service: no name, nothing about the phone or its rider.
                AdvertiseData.Builder().setIncludeDeviceName(false).addServiceUuid(ParcelUuid(SERVICE)).build(),
                advertising
            )
        }
    }

    /** Nearby: whoever connects gets the packets, then the connection closes. */
    private fun sender(context: Context) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            P2pLog.add("Nearby: ${P2pLog.who(endpointId)} asks")
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) = Unit
                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                    if (update.status == PayloadTransferUpdate.Status.FAILURE) P2pLog.add("Nearby: sending to ${P2pLog.who(endpointId)} FAILED")
                }
            }).addOnFailureListener { P2pLog.add("Nearby: accepting ${P2pLog.who(endpointId)} failed: ${it.message}") }
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val client = Nearby.getConnectionsClient(context)
            val packets = listOfNotNull(buses, board) + answers
            if (!result.status.isSuccess || packets.isEmpty()) {
                P2pLog.add("Nearby: ${P2pLog.who(endpointId)} not connected (status ${result.status.statusCode}${if (packets.isEmpty()) ", nothing to send" else ""})")
                return client.disconnectFromEndpoint(endpointId)
            }
            packets.forEach { client.sendPayload(endpointId, Payload.fromBytes(it)) }
            P2pStore.lastOfferedMs.value = System.currentTimeMillis()
            P2pLog.add("Nearby: sent ${packets.size} packets to ${P2pLog.who(endpointId)} (${packets.sumOf { it.size }} B)")
            // The listener closes once it has them; this is in case it doesn't.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ client.disconnectFromEndpoint(endpointId) }, 10_000L)
        }

        override fun onDisconnected(endpointId: String) = P2pLog.add("Nearby: ${P2pLog.who(endpointId)} disconnected")
    }

    fun stopOffering(context: Context) {
        if (buses != null || board != null || advertising != null || gattServer != null || nearbyOffering) P2pLog.add("offering stopped")
        buses = null
        board = null
        runCatching { advertising?.let { adapter(context)?.bluetoothLeAdvertiser?.stopAdvertising(it) } }
        advertising = null
        runCatching { gattServer?.close() }
        gattServer = null
        if (nearbyOffering) runCatching { Nearby.getConnectionsClient(context).stopAdvertising() }
        nearbyOffering = false
    }

    // ------------------------------------------------------------ listening (this phone has no data)

    /** [onPacket]: a packet was taken (emHUB's and fresh), so it can be passed on. */
    fun listen(context: Context, onPacket: () -> Unit) {
        if (bluetoothAllowed(context) && scanning == null) {
            val scanner = adapter(context)?.bluetoothLeScanner
            if (scanner == null) problem("BLE: can't scan (Bluetooth off?)")
            scanner?.let {
                scanning = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) {
                        // An answer broadcast one-way (no connection): take it as it is.
                        val broadcast = result.scanRecord?.getServiceData(ParcelUuid(ANSWER))
                        if (broadcast != null) {
                            piece(broadcast)?.let { (id, whole) ->
                                if (fresh("answer:$id")) take(whole, "broadcast ${P2pLog.who(result.device.address)}", onPacket, asAnswer = true)
                            }
                        } else if (result.scanRecord?.serviceUuids?.contains(ParcelUuid(SERVICE)) == true) {
                            readBle(context, result.device, result.rssi, onPacket)
                        }
                    }
                    override fun onScanFailed(errorCode: Int) { P2pLog.add("BLE: listening failed: ${scanError(errorCode)}"); scanning = null }
                }
                it.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build(),
                        ScanFilter.Builder().setServiceData(ParcelUuid(ANSWER), ByteArray(0)).build()),
                    scanSettings(),
                    scanning
                )
                P2pLog.add("BLE: listening (one-way broadcasts too: ${Build.VERSION.SDK_INT >= 26})")
            }
        }
        if (nearbyAllowed(context) && !nearbyListening) {
            nearbyListening = true
            val client = Nearby.getConnectionsClient(context)
            client.startDiscovery(NEARBY_ID, object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (!fresh("nearby:$endpointId")) return
                    P2pLog.add("Nearby: found ${P2pLog.who(endpointId)}, asking")
                    client.requestConnection("p", endpointId, receiver(context, onPacket))
                        .addOnFailureListener { P2pLog.add("Nearby: asking ${P2pLog.who(endpointId)} failed: ${it.message}") }
                }
                override fun onEndpointLost(endpointId: String) = P2pLog.add("Nearby: lost ${P2pLog.who(endpointId)}")
            }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnSuccessListener { P2pLog.add("Nearby: listening") }
                .addOnFailureListener { nearbyListening = false; P2pLog.add("Nearby: listening failed: ${it.message}") }
        }
    }

    /** A phone not read in the last minute: mark it read now. */
    private fun fresh(key: String): Boolean = synchronized(lastRead) {
        val now = System.currentTimeMillis()
        if (now - (lastRead[key] ?: 0L) < READ_AGAIN_MS) return false
        lastRead[key] = now
        lastRead.entries.removeAll { now - it.value > 10 * READ_AGAIN_MS }
        true
    }

    private fun take(packet: ByteArray?, from: String, onPacket: () -> Unit, asAnswer: Boolean = false) {
        val taken = packet?.isNotEmpty() == true && P2pStore.take(packet, asAnswer = asAnswer)
        P2pLog.add("packet from $from (${packet?.size ?: 0} B): " + when {
            taken -> "taken"
            packet == null || packet.isEmpty() -> "empty (that phone has nothing to offer)"
            else -> "refused: ${P2pPack.lastRefusal}"
        })
        if (taken) onPacket()
    }

    /** Reads both values (buses, then the stop), one after the other, then closes. */
    private fun readBle(context: Context, device: BluetoothDevice, rssi: Int, onPacket: () -> Unit) {
        if (reading || !fresh("ble:${device.address}")) return
        reading = true
        val who = P2pLog.who(device.address)
        P2pLog.add("BLE: found $who ($rssi dBm), reading")
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            private val queue = ArrayDeque<BluetoothGattCharacteristic>()
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> done(gatt, "connection failed (status $status)")
                    newState == BluetoothProfile.STATE_CONNECTED -> if (!gatt.requestMtu(517)) gatt.discoverServices()
                    newState == BluetoothProfile.STATE_DISCONNECTED -> done(gatt, "disconnected")
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) P2pLog.add("BLE: $who MTU $mtu (status $status)")
                if (!gatt.discoverServices()) done(gatt, "couldn't look for its services")
            }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                val service = gatt.getService(SERVICE) ?: return done(gatt, "no Plan&Ride service (status $status)")
                (listOf(BUSES, BOARD) + ANSWERS).mapNotNullTo(queue) { service.getCharacteristic(it) }
                next(gatt)
            }
            private fun next(gatt: BluetoothGatt) {
                val c = queue.removeFirstOrNull() ?: return done(gatt, null)
                if (!gatt.readCharacteristic(c)) done(gatt, "couldn't read")
            }
            private fun read(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray?, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) P2pLog.add("BLE: $who read failed (status $status)")
                take(value.takeIf { status == BluetoothGatt.GATT_SUCCESS }, "BLE $who", onPacket, asAnswer = characteristic.uuid in ANSWERS)
                next(gatt)
            }
            @Deprecated("Android 12 and older")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) read(gatt, characteristic, characteristic.value, status)
            }
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                read(gatt, characteristic, value, status)
            private fun done(gatt: BluetoothGatt, problem: String?) {
                if (!reading) return
                if (problem != null) P2pLog.add("BLE: $who $problem")
                runCatching { gatt.close() }
                reading = false
            }
        }, BluetoothDevice.TRANSPORT_LE).also { if (it == null) { P2pLog.add("BLE: couldn't connect to $who"); reading = false } }
    }

    /** Nearby: accept the phone we asked, take its packets, close after a moment. Nothing is sent from here. */
    private fun receiver(context: Context, onPacket: () -> Unit) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) = take(payload.asBytes(), "Nearby ${P2pLog.who(endpointId)}", onPacket)
                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
                    if (update.status == PayloadTransferUpdate.Status.FAILURE) P2pLog.add("Nearby: receiving from ${P2pLog.who(endpointId)} FAILED")
                }
            }).addOnFailureListener { P2pLog.add("Nearby: accepting ${P2pLog.who(endpointId)} failed: ${it.message}") }
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Nearby.getConnectionsClient(context).disconnectFromEndpoint(endpointId)
            }, 5_000L)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (!result.status.isSuccess) P2pLog.add("Nearby: ${P2pLog.who(endpointId)} not connected (status ${result.status.statusCode})")
        }
        override fun onDisconnected(endpointId: String) = Unit
    }

    /** Extended advertising (Bluetooth 5) is heard too where Android can (8.0 and newer). */
    private fun scanSettings(): ScanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED)
        .apply { if (Build.VERSION.SDK_INT >= 26) setLegacy(false) }.build()

    // ------------------------------------------------------------ questions and answers (only while the app is open)

    /**
     * Without data: broadcasts the question, three stop codes (the asked stop and two cover stops) as one advertisement
     * nobody connects to. No name, no id, nothing else: the phone's Bluetooth address is random and changes.
     */
    fun ask(context: Context, codes: List<Int>) {
        stopAsking(context)
        if (!bluetoothAllowed(context)) return
        val advertiser = adapter(context)?.bluetoothLeAdvertiser ?: return problem("BLE: can't ask (this phone can't advertise)")
        val data = ByteArray(1 + 3 * codes.size)
        data[0] = 1 // kind: a stop's departures
        codes.forEachIndexed { i, c -> data[1 + 3 * i] = (c shr 16).toByte(); data[2 + 3 * i] = (c shr 8).toByte(); data[3 + 3 * i] = c.toByte() }
        asking = object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { P2pLog.add("BLE: question broadcast (${codes.size} stops)") }
            override fun onStartFailure(errorCode: Int) { P2pLog.add("BLE: question failed: ${advertiseError(errorCode)}"); asking = null }
        }
        advertiser.startAdvertising(
            Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(false).build(),
            AdvertiseData.Builder().setIncludeDeviceName(false).setIncludeTxPowerLevel(false).addServiceData(ParcelUuid(QUESTION), data).build(),
            asking
        )
    }

    fun stopAsking(context: Context) {
        val was = asking ?: return
        runCatching { adapter(context)?.bluetoothLeAdvertiser?.stopAdvertising(was) }
        asking = null
    }

    /** With data and the app open: hears questions; [onQuestion] gets each one's stop codes. */
    fun listenForQuestions(context: Context, onQuestion: (List<Int>) -> Unit) {
        if (questionScan != null || !bluetoothAllowed(context)) return
        val scanner = adapter(context)?.bluetoothLeScanner ?: return problem("BLE: can't listen for questions (Bluetooth off?)")
        questionScan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val data = result.scanRecord?.getServiceData(ParcelUuid(QUESTION)) ?: return
                if (data.isEmpty() || data[0].toInt() != 1 || (data.size - 1) % 3 != 0) return
                if (!fresh("question:" + data.contentHashCode())) return
                val codes = (0 until (data.size - 1) / 3).map { i ->
                    ((data[1 + 3 * i].toInt() and 0xFF) shl 16) or ((data[2 + 3 * i].toInt() and 0xFF) shl 8) or (data[3 + 3 * i].toInt() and 0xFF)
                }
                P2pLog.add("BLE: question from ${P2pLog.who(result.device.address)} (${result.rssi} dBm): ${codes.joinToString { "%06x".format(it) }}")
                onQuestion(codes)
            }
            override fun onScanFailed(errorCode: Int) { P2pLog.add("BLE: listening for questions failed: ${scanError(errorCode)}"); questionScan = null }
        }
        scanner.startScan(listOf(ScanFilter.Builder().setServiceData(ParcelUuid(QUESTION), ByteArray(0)).build()), scanSettings(), questionScan)
        P2pLog.add("BLE: listening for questions")
    }

    fun stopQuestions(context: Context) {
        val was = questionScan ?: return
        runCatching { adapter(context)?.bluetoothLeScanner?.stopScan(was) }
        questionScan = null
        P2pLog.add("BLE: stopped listening for questions")
    }

    /**
     * Broadcasts answers (signed boards for asked stops) to everyone around: one-way (Bluetooth 5 extended advertising,
     * nobody connects) where the phone can, and as readable values for older phones. Replaces the answers before.
     */
    fun answer(context: Context, wires: List<ByteArray>) {
        val next = wires.take(ANSWERS.size)
        if (next.size == answers.size && next.zip(answers).all { (a, b) -> a.contentEquals(b) }) return
        answers = next
        val bluetooth = adapter(context) ?: return
        if (Build.VERSION.SDK_INT >= 26) OneWay.stop(bluetooth, answerSets)
        answerSets.clear()
        if (next.isEmpty()) return
        if (bluetoothAllowed(context)) offerBle(context) // the readable values (older phones)
        val oneWay = Build.VERSION.SDK_INT >= 26 && OneWay.supported(bluetooth)
        val room = if (Build.VERSION.SDK_INT >= 26) OneWay.room(bluetooth) else 31
        P2pLog.add("answering ${next.size} stops (${next.sumOf { it.size }} B); one-way broadcast: " +
            if (oneWay) "yes, in pieces of $PIECE B (room $room B)" else "no (this phone can't), readable only")
        if (!oneWay || !bluetoothAllowed(context) || Build.VERSION.SDK_INT < 26) return
        if (PIECE + 24 > room) return P2pLog.add("one-way broadcast: room $room B too small, readable only")
        OneWay.start(bluetooth, ANSWER, pieces(next))?.let { answerSets += it }
    }

    /**
     * EM-0136: one broadcast holds ~250 B on Samsung phones (a whole 500 B answer failed, "data too large"): each answer
     * goes as pieces [answer id (2 B), piece number, pieces] + up to [PIECE] B, which listeners put back together.
     */
    private const val PIECE = 200

    internal fun pieces(wires: List<ByteArray>): List<ByteArray> = wires.flatMap { wire ->
        val id = wire.contentHashCode() and 0xFFFF
        val count = (wire.size + PIECE - 1) / PIECE
        (0 until count).map { k ->
            byteArrayOf((id shr 8).toByte(), id.toByte(), k.toByte(), count.toByte()) + wire.copyOfRange(k * PIECE, minOf(wire.size, (k + 1) * PIECE))
        }
    }

    /** Pieces heard, by answer id, until each answer is whole. */
    private val heardPieces = mutableMapOf<Int, Array<ByteArray?>>()

    /** A broadcast piece of an answer; returns the whole answer once all its pieces are in. */
    internal fun piece(data: ByteArray): Pair<Int, ByteArray>? = synchronized(heardPieces) {
        if (data.size < 5) return null
        val id = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
        val k = data[2].toInt() and 0xFF
        val count = data[3].toInt() and 0xFF
        if (count == 0 || k >= count || count > 16) return null
        val parts = heardPieces.getOrPut(id) { arrayOfNulls(count) }.takeIf { it.size == count } ?: arrayOfNulls<ByteArray>(count).also { heardPieces[id] = it }
        parts[k] = data.copyOfRange(4, data.size)
        if (heardPieces.size > 20) heardPieces.keys.filter { it != id }.take(heardPieces.size - 20).forEach { heardPieces.remove(it) }
        if (parts.any { it == null }) return null
        heardPieces.remove(id)
        id to parts.fold(ByteArray(0)) { all, p -> all + p!! }
    }

    /** Bluetooth 5 extended advertising (Android 8.0+): an answer broadcast nobody connects to. */
    @androidx.annotation.RequiresApi(26)
    private object OneWay {
        fun supported(bluetooth: android.bluetooth.BluetoothAdapter) = bluetooth.isLeExtendedAdvertisingSupported
        fun room(bluetooth: android.bluetooth.BluetoothAdapter) = bluetooth.leMaximumAdvertisingDataLength

        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private val cycles = mutableMapOf<Any, Runnable>()

        /** One broadcast cycling through [pieces] (a new one every 300 ms) for 2 minutes. */
        fun start(bluetooth: android.bluetooth.BluetoothAdapter, uuid: UUID, pieces: List<ByteArray>): Any? {
            val advertiser = bluetooth.bluetoothLeAdvertiser ?: return null
            if (pieces.isEmpty()) return null
            fun data(p: ByteArray) = AdvertiseData.Builder().setIncludeDeviceName(false).addServiceData(ParcelUuid(uuid), p).build()
            val cb = object : android.bluetooth.le.AdvertisingSetCallback() {
                override fun onAdvertisingSetStarted(set: android.bluetooth.le.AdvertisingSet?, txPower: Int, status: Int) {
                    if (status != 0 || set == null) return P2pLog.add("BLE: answer broadcast failed (status $status)")
                    P2pLog.add("BLE: answer broadcast (${pieces.size} pieces, ${pieces.sumOf { it.size }} B)")
                    if (pieces.size < 2) return
                    var k = 0
                    val next = object : Runnable {
                        override fun run() {
                            k = (k + 1) % pieces.size
                            runCatching { set.setAdvertisingData(data(pieces[k])) }
                            main.postDelayed(this, 300L)
                        }
                    }
                    synchronized(cycles) { cycles[this] = next }
                    main.postDelayed(next, 300L)
                }
                override fun onAdvertisingDataSet(set: android.bluetooth.le.AdvertisingSet?, status: Int) {
                    if (status != 0) P2pLog.add("BLE: answer piece failed (status $status)")
                }
                override fun onAdvertisingSetStopped(set: android.bluetooth.le.AdvertisingSet?) {
                    synchronized(cycles) { cycles.remove(this) }?.let(main::removeCallbacks)
                }
            }
            return runCatching {
                advertiser.startAdvertisingSet(
                    android.bluetooth.le.AdvertisingSetParameters.Builder().setLegacyMode(false).setConnectable(false).setScannable(false)
                        .setInterval(android.bluetooth.le.AdvertisingSetParameters.INTERVAL_LOW)
                        .setTxPowerLevel(android.bluetooth.le.AdvertisingSetParameters.TX_POWER_MEDIUM).build(),
                    data(pieces[0]), null, null, null, 12_000, 0, cb // 2 minutes, then it ends by itself
                )
                cb
            }.onFailure { P2pLog.add("BLE: answer broadcast failed: ${it.message}") }.getOrNull()
        }

        fun stop(bluetooth: android.bluetooth.BluetoothAdapter, sets: List<Any>) {
            sets.forEach { cb ->
                synchronized(cycles) { cycles.remove(cb) }?.let(main::removeCallbacks)
                runCatching { bluetooth.bluetoothLeAdvertiser?.stopAdvertisingSet(cb as android.bluetooth.le.AdvertisingSetCallback) }
            }
        }
    }

    fun stopListening(context: Context) {
        if (scanning != null || nearbyListening) P2pLog.add("listening stopped")
        runCatching { scanning?.let { adapter(context)?.bluetoothLeScanner?.stopScan(it) } }
        scanning = null
        if (nearbyListening) runCatching { Nearby.getConnectionsClient(context).stopDiscovery() }
        nearbyListening = false
    }

    fun stop(context: Context) {
        stopOffering(context)
        stopListening(context)
        stopQuestions(context)
        stopAsking(context)
        answer(context, emptyList())
    }
}

/**
 * When to fetch, offer and listen, asked by the follow service and the open app, with the setting on. With a working
 * connection: the packets for where the phone is (fetched once a minute) are offered. Without: it listens, and passes on
 * the newest packets it heard, so one phone with data reaches the phones beyond its own range.
 */
object P2p {
    private var fetchedAt = 0L
    private val turn = kotlinx.coroutines.sync.Mutex()
    private var mode = ""

    private fun mode(text: String) {
        if (text != mode) P2pLog.add("mode: $text")
        mode = text
    }

    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    /** [foreground]: the app is open (questions are heard and asked only then, to spare the battery). */
    suspend fun tick(context: Context, at: Pair<Double, Double>?, foreground: Boolean = false) = turn.withLock {
        if (at != null) lastAt = at
        if (foreground) openUntil = System.currentTimeMillis() + 45_000L
        round(context, at)
    }

    /** The app went to the background: no more questions (following, if on, goes on as before). */
    fun background(context: Context) {
        openUntil = 0L
        P2pRadio.stopQuestions(context)
        P2pAsk.stop(context)
    }

    @Volatile private var lastAt: Pair<Double, Double>? = null
    @Volatile private var openUntil = 0L
    private val asked = mutableMapOf<Int, Long>()
    private val io = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    /** A question heard: the boards for its stops from emHUB (each stop at most once a minute), broadcast to everyone. */
    private fun onQuestion(context: Context, codes: List<Int>) {
        val now = System.currentTimeMillis()
        val fresh = synchronized(asked) {
            asked.entries.removeAll { now - it.value > 60_000L }
            codes.filter { it !in asked }.onEach { asked[it] = now }
        }
        val at = lastAt ?: return P2pLog.add("question heard, but no location yet to ask emHUB")
        if (fresh.isEmpty()) return P2pLog.add("question heard: answered in the last minute already")
        io.launch {
            val boards = runCatching { com.ksjd.testem.hub.HubClient.p2pBoards(fresh, at.first, at.second) }
                .onFailure { P2pLog.add("fetching asked boards failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}") }.getOrNull()
                ?: return@launch
            P2pLog.add("fetched ${boards.size} asked boards (${boards.sumOf { it.size }} B)")
            P2pRadio.answer(context, boards)
        }
    }

    private suspend fun round(context: Context, at: Pair<Double, Double>?) {
        if (!com.ksjd.testem.CredentialsManager(context).getP2pNearby() || !com.ksjd.testem.hub.HubClient.isConfigured) {
            if (mode.isNotEmpty() && mode != "off") mode("off (setting)")
            return stop(context)
        }
        val open = System.currentTimeMillis() < openUntil
        if (!open) P2pRadio.stopQuestions(context)
        if (!online(context)) {
            mode("no data: listening, passing on what is heard")
            fetchedAt = 0L
            P2pRadio.stopQuestions(context)
            val passOn = {
                P2pRadio.offer(context, P2pStore.busesWire?.second, P2pStore.boardWire?.second)
                // Answers heard are broadcast again too, newest first.
                P2pRadio.answer(context, P2pStore.answerWires.values.sortedByDescending { it.first }.map { it.second })
            }
            P2pRadio.listen(context, passOn)
            return passOn()
        }
        mode("data: offering emHUB's packets" + if (open) ", answering questions" else "")
        P2pRadio.stopListening(context)
        P2pStore.clearWires()
        if (open) P2pRadio.listenForQuestions(context) { codes -> onQuestion(context, codes) }
        val now = System.currentTimeMillis()
        if (at == null) return P2pLog.add("no location yet: nothing to fetch")
        if (now - fetchedAt < 60_000L) return
        fetchedAt = now
        val packets = runCatching { com.ksjd.testem.hub.HubClient.p2pPack(at.first, at.second) }
            .onFailure { P2pLog.add("fetching packets failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(120)}") }.getOrNull()
        if (packets != null) P2pLog.add("fetched: buses ${packets.first.size} B, board ${packets.second?.size ?: 0} B")
        P2pRadio.offer(context, packets?.first, packets?.second)
    }

    fun stop(context: Context) {
        fetchedAt = 0L
        if (mode.isNotEmpty() && !mode.startsWith("off")) mode("off")
        P2pRadio.stop(context)
    }
}
