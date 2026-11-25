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
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
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
 * plain Bluetooth LE.
 *
 * ponytail: one listener connection at a time and each phone read at most once a minute; enough for a stop or a bus.
 */
@SuppressLint("MissingPermission") // the permissions are checked before anything starts
object P2pRadio {
    private const val TAG = "P2p"
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

    /** Bluetooth's own permissions (Nearby's Wi-Fi one is extra: without it Nearby is skipped, Bluetooth LE still runs). */
    private fun bluetoothAllowed(context: Context) = permissions().filter { it != android.Manifest.permission.NEARBY_WIFI_DEVICES }
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun nearbyAllowed(context: Context) = permissions().all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED } &&
        runCatching {
            com.google.android.gms.common.GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) ==
                com.google.android.gms.common.ConnectionResult.SUCCESS
        }.getOrDefault(false)

    private fun adapter(context: Context) = context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }

    // ------------------------------------------------------------ offering (fetched with data, or passed on)

    fun offer(context: Context, buses: ByteArray?, board: ByteArray?) {
        if (buses == null && board == null) return stopOffering(context)
        this.buses = buses
        this.board = board
        if (bluetoothAllowed(context)) offerBle(context)
        if (nearbyAllowed(context) && !nearbyOffering) {
            nearbyOffering = true
            Nearby.getConnectionsClient(context).startAdvertising("p", NEARBY_ID, sender(context),
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { nearbyOffering = false; Log.w(TAG, "nearby advertising", it) }
        }
    }

    private fun offerBle(context: Context) {
        val manager = context.getSystemService(BluetoothManager::class.java) ?: return
        val bluetooth = adapter(context) ?: return
        if (gattServer == null) {
            gattServer = manager.openGattServer(context, object : BluetoothGattServerCallback() {
                override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
                    val value = (if (characteristic.uuid == BOARD) board else buses) ?: ByteArray(0)
                    val part = if (offset <= value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, part)
                }
            })?.also { server ->
                server.addService(BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
                    listOf(BUSES, BOARD).forEach {
                        addCharacteristic(BluetoothGattCharacteristic(it, BluetoothGattCharacteristic.PROPERTY_READ, BluetoothGattCharacteristic.PERMISSION_READ))
                    }
                })
            }
        }
        if (advertising == null) {
            val advertiser = bluetooth.bluetoothLeAdvertiser ?: return
            advertising = object : AdvertiseCallback() {
                override fun onStartFailure(errorCode: Int) { Log.w(TAG, "BLE advertising failed: $errorCode"); advertising = null }
            }
            advertiser.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
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
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) = Unit
                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
            })
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val client = Nearby.getConnectionsClient(context)
            val packets = listOfNotNull(buses, board)
            if (!result.status.isSuccess || packets.isEmpty()) return client.disconnectFromEndpoint(endpointId)
            packets.forEach { client.sendPayload(endpointId, Payload.fromBytes(it)) }
            // The listener closes once it has them; this is in case it doesn't.
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ client.disconnectFromEndpoint(endpointId) }, 10_000L)
        }

        override fun onDisconnected(endpointId: String) = Unit
    }

    fun stopOffering(context: Context) {
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
            adapter(context)?.bluetoothLeScanner?.let { scanner ->
                scanning = object : ScanCallback() {
                    override fun onScanResult(callbackType: Int, result: ScanResult) = readBle(context, result.device, onPacket)
                    override fun onScanFailed(errorCode: Int) { Log.w(TAG, "BLE scan failed: $errorCode"); scanning = null }
                }
                scanner.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_BALANCED).build(),
                    scanning
                )
            }
        }
        if (nearbyAllowed(context) && !nearbyListening) {
            nearbyListening = true
            val client = Nearby.getConnectionsClient(context)
            client.startDiscovery(NEARBY_ID, object : EndpointDiscoveryCallback() {
                override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
                    if (!fresh("nearby:$endpointId")) return
                    client.requestConnection("p", endpointId, receiver(context, onPacket))
                }
                override fun onEndpointLost(endpointId: String) = Unit
            }, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
                .addOnFailureListener { nearbyListening = false; Log.w(TAG, "nearby discovery", it) }
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
        Log.i(TAG, "packet from $from: " + if (taken) "taken" else "refused")
        if (taken) onPacket()
    }

    /** Reads both values (buses, then the stop), one after the other, then closes. */
    private fun readBle(context: Context, device: BluetoothDevice, onPacket: () -> Unit) {
        if (reading || !fresh("ble:${device.address}")) return
        reading = true
        device.connectGatt(context, false, object : BluetoothGattCallback() {
            private val queue = ArrayDeque<BluetoothGattCharacteristic>()
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                when {
                    status != BluetoothGatt.GATT_SUCCESS -> done(gatt)
                    newState == BluetoothProfile.STATE_CONNECTED -> if (!gatt.requestMtu(517)) gatt.discoverServices()
                    newState == BluetoothProfile.STATE_DISCONNECTED -> done(gatt)
                }
            }
            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { gatt.discoverServices() }
            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                gatt.getService(SERVICE)?.let { service -> listOf(BUSES, BOARD).mapNotNullTo(queue) { service.getCharacteristic(it) } }
                next(gatt)
            }
            private fun next(gatt: BluetoothGatt) {
                val c = queue.removeFirstOrNull() ?: return done(gatt)
                if (!gatt.readCharacteristic(c)) done(gatt)
            }
            @Deprecated("Android 12 and older")
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (Build.VERSION.SDK_INT < 33) {
                    take(characteristic.value.takeIf { status == BluetoothGatt.GATT_SUCCESS }, "BLE", onPacket)
                    next(gatt)
                }
            }
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                take(value.takeIf { status == BluetoothGatt.GATT_SUCCESS }, "BLE", onPacket)
                next(gatt)
            }
            private fun done(gatt: BluetoothGatt) {
                runCatching { gatt.close() }
                reading = false
            }
        }, BluetoothDevice.TRANSPORT_LE)
    }

    /** Nearby: accept the phone we asked, take its packets, close after a moment. Nothing is sent from here. */
    private fun receiver(context: Context, onPacket: () -> Unit) = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            Nearby.getConnectionsClient(context).acceptConnection(endpointId, object : PayloadCallback() {
                override fun onPayloadReceived(endpointId: String, payload: Payload) = take(payload.asBytes(), "Nearby", onPacket)
                override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
            })
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                Nearby.getConnectionsClient(context).disconnectFromEndpoint(endpointId)
            }, 5_000L)
        }
        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) = Unit
        override fun onDisconnected(endpointId: String) = Unit
    }

    fun stopListening(context: Context) {
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
 * When to fetch, offer and listen, asked by the follow service every round, with the setting on. With a working
 * connection: the packets for where the phone is (fetched once a minute) are offered. Without: it listens, and passes on
 * the newest packets it heard, so one phone with data reaches the phones beyond its own range.
 */
object P2p {
    private var fetchedAt = 0L
    private val turn = kotlinx.coroutines.sync.Mutex()

    fun online(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrDefault(false)

    suspend fun tick(context: Context, at: Pair<Double, Double>?) = turn.withLock { round(context, at) }

    private suspend fun round(context: Context, at: Pair<Double, Double>?) {
        if (!com.ksjd.testem.CredentialsManager(context).getP2pNearby() || !com.ksjd.testem.hub.HubClient.isConfigured) return stop(context)
        if (!online(context)) {
            fetchedAt = 0L
            val passOn = { P2pRadio.offer(context, P2pStore.busesWire?.second, P2pStore.boardWire?.second) }
            P2pRadio.listen(context, passOn)
            return passOn()
        }
        P2pRadio.stopListening(context)
        P2pStore.clearWires()
        val now = System.currentTimeMillis()
        if (at == null || now - fetchedAt < 60_000L) return
        fetchedAt = now
        val packets = runCatching { com.ksjd.testem.hub.HubClient.p2pPack(at.first, at.second) }
            .onFailure { android.util.Log.w("P2p", "packets", it) }.getOrNull()
        P2pRadio.offer(context, packets?.first, packets?.second)
    }

    fun stop(context: Context) {
        fetchedAt = 0L
        P2pRadio.stop(context)
    }
}
