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
    private const val NEARBY_ID = "com.ksjd.testem.p2p"
    private const val READ_AGAIN_MS = 60_000L

    /** What this phone offers now (null: nothing). */
    @Volatile var buses: ByteArray? = null
        private set
    @Volatile var board: ByteArray? = null
        private set

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
        "Nearby offering $nearbyOffering, Nearby listening $nearbyListening, reading $reading"

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
                    val value = (if (characteristic.uuid == BOARD) board else buses) ?: ByteArray(0)
                    if (value.isNotEmpty()) P2pStore.lastOfferedMs.value = System.currentTimeMillis()
                    val part = if (offset <= value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                    val sent = gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part) == true
                    P2pLog.add("BLE server: ${P2pLog.who(device.address)} read ${if (characteristic.uuid == BOARD) "board" else "buses"}" +
                        (if (offset > 0) " from $offset" else "") + ", ${part.size} B" + if (!sent) ", answer FAILED" else "")
                }
            })?.also { server ->
                server.addService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                    listOf(BUSES, BOARD).forEach {
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
            val packets = listOfNotNull(buses, board)
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
                    override fun onScanResult(callbackType: Int, result: ScanResult) = readBle(context, result.device, result.rssi, onPacket)
                    override fun onScanFailed(errorCode: Int) { P2pLog.add("BLE: listening failed: ${scanError(errorCode)}"); scanning = null }
                }
                it.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                    scanning
                )
                P2pLog.add("BLE: listening")
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

    private fun take(packet: ByteArray?, from: String, onPacket: () -> Unit) {
        val taken = packet?.isNotEmpty() == true && P2pStore.take(packet)
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
                listOf(BUSES, BOARD).mapNotNullTo(queue) { service.getCharacteristic(it) }
                next(gatt)
            }
            private fun next(gatt: BluetoothGatt) {
                val c = queue.removeFirstOrNull() ?: return done(gatt, null)
                if (!gatt.readCharacteristic(c)) done(gatt, "couldn't read")
            }
            private fun read(gatt: BluetoothGatt, value: ByteArray?, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) P2pLog.add("BLE: $who read failed (status $status)")
                take(value.takeIf { status == BluetoothGatt.GATT_SUCCESS }, "BLE $who", onPacket)
                next(gatt)
            }
            @Deprecated("Android 12 and older")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) read(gatt, characteristic.value, status)
            }
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
                read(gatt, value, status)
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

    suspend fun tick(context: Context, at: Pair<Double, Double>?) = turn.withLock { round(context, at) }

    private suspend fun round(context: Context, at: Pair<Double, Double>?) {
        if (!com.ksjd.testem.CredentialsManager(context).getP2pNearby() || !com.ksjd.testem.hub.HubClient.isConfigured) {
            if (mode.isNotEmpty() && mode != "off") mode("off (setting)")
            return stop(context)
        }
        if (!online(context)) {
            mode("no data: listening, passing on what is heard")
            fetchedAt = 0L
            val passOn = { P2pRadio.offer(context, P2pStore.busesWire?.second, P2pStore.boardWire?.second) }
            P2pRadio.listen(context, passOn)
            return passOn()
        }
        mode("data: offering emHUB's packets")
        P2pRadio.stopListening(context)
        P2pStore.clearWires()
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
