package com.example.dvhub

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresPermission
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.UUID

private const val TAG = "BleConnectActivity"
private const val SCAN_TIMEOUT_MS = 10_000L

private val HUB_SERVICE_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef0")

private val HUB_INFO_CHAR_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef3")

private val AUTH_CHAR_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef4")

private val WIFI_CHAR_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef2")

data class HubScanItem(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int
)

data class HubIdentity(
    val deviceId: String,
    val fw: String,
    val vendor: String,
    val model: String
)

class BleConnectActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
    private var gatt: BluetoothGatt? = null

    // simple demo token (must match server)
    private val authToken = "pair-token-123"

    // keep pending actions for the current connection
    private var pendingWifiJson: String? = null
    private var pendingDevice: BluetoothDevice? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        scanner = bluetoothAdapter.bluetoothLeScanner

        enableEdgeToEdge()

        setContent {
            MaterialTheme {
                Scaffold(
                ) { innerPadding ->
                    BleConnectScreen(
                        modifier = Modifier.padding(innerPadding),
                        bluetoothAdapter = bluetoothAdapter,
                        scanner = scanner,
                        onConnect = { device -> connectToDevice(device) }
                    )
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        pendingDevice = device

        val callback = object : BluetoothGattCallback() {

            override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
                if (g == null) return

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected. Discovering services...")
                        runOnUiThread {
                            Toast.makeText(this@BleConnectActivity, "Connected, discovering…", Toast.LENGTH_SHORT).show()
                        }
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected")
                        runOnUiThread {
                            Toast.makeText(this@BleConnectActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                        }
                        g.close()
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt?, status: Int) {
                if (g == null) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Service discovery failed: $status")
                    g.disconnect()
                    return
                }

                val hubService = g.getService(HUB_SERVICE_UUID)
                if (hubService == null) {
                    Log.e(TAG, "Not a hub (missing service)")
                    runOnUiThread { Toast.makeText(this@BleConnectActivity, "Not a hub device", Toast.LENGTH_SHORT).show() }
                    g.disconnect()
                    return
                }

                val hubInfoChar = hubService.getCharacteristic(HUB_INFO_CHAR_UUID)
                if (hubInfoChar == null || (hubInfoChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                    Log.e(TAG, "Not a hub (missing hub info char)")
                    runOnUiThread { Toast.makeText(this@BleConnectActivity, "Not a hub device", Toast.LENGTH_SHORT).show() }
                    g.disconnect()
                    return
                }

                // Read hub identity JSON
                val ok = g.readCharacteristic(hubInfoChar)
                if (!ok) {
                    Log.e(TAG, "Failed to start hub info read")
                    g.disconnect()
                }
            }

            override fun onCharacteristicRead(g: BluetoothGatt?, ch: BluetoothGattCharacteristic?, status: Int) {
                if (g == null || ch == null) return
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Read failed: $status")
                    g.disconnect()
                    return
                }

                if (ch.uuid == HUB_INFO_CHAR_UUID) {
                    val raw = ch.value?.toString(Charsets.UTF_8) ?: ""
                    Log.d(TAG, "Hub info: $raw")

                    val identity = parseAndValidateHubIdentity(raw)
                    if (identity == null) {
                        runOnUiThread {
                            Toast.makeText(this@BleConnectActivity, "Device is not a valid hub", Toast.LENGTH_SHORT).show()
                        }
                        g.disconnect()
                        return
                    }

                    runOnUiThread {
                        Toast.makeText(this@BleConnectActivity, "Hub verified: ${identity.deviceId}", Toast.LENGTH_SHORT).show()
                    }

                    // Optional: auth step before allowing WiFi writes
                    val hubService = g.getService(HUB_SERVICE_UUID) ?: run {
                        g.disconnect(); return
                    }
                    val authChar = hubService.getCharacteristic(AUTH_CHAR_UUID)

                    if (authChar != null && (authChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        authChar.value = authToken.toByteArray(Charsets.UTF_8)
                        val started = g.writeCharacteristic(authChar)
                        Log.d(TAG, "Auth write started: $started")
                        if (!started) g.disconnect()
                    } else {
                        Log.w(TAG, "No auth characteristic; proceeding without auth")
                        // If you want to write WiFi immediately without auth, do it here.
                        pendingWifiJson?.let { writeWifiJsonNow(g, it) }
                    }
                }
            }

            override fun onCharacteristicWrite(g: BluetoothGatt?, ch: BluetoothGattCharacteristic?, status: Int) {
                if (g == null || ch == null) return

                if (ch.uuid == AUTH_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Auth OK")
                        pendingWifiJson?.let { writeWifiJsonNow(g, it) }
                    } else {
                        Log.e(TAG, "Auth failed: $status")
                        runOnUiThread {
                            Toast.makeText(this@BleConnectActivity, "Auth failed", Toast.LENGTH_SHORT).show()
                        }
                        g.disconnect()
                    }
                    return
                }

                if (ch.uuid == WIFI_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        runOnUiThread {
                            Toast.makeText(this@BleConnectActivity, "WiFi credentials sent ✅", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        runOnUiThread {
                            Toast.makeText(this@BleConnectActivity, "WiFi write failed ($status)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        gatt?.close()
        gatt = device.connectGatt(this, false, callback)
    }

    /**
     * Call this after you connected+verified (or set pendingWifiJson before connect).
     * If already connected, it will attempt to write immediately (after auth).
     */
    fun sendWifiCredentials(ssid: String, password: String) {
        val json = JSONObject()
            .put("ssid", ssid)
            .put("password", password)
            .toString()

        pendingWifiJson = json

        val g = gatt
        if (g != null) {
            // If already connected, try writing now (auth flow will handle ordering)
            writeWifiJsonNow(g, json)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeWifiJsonNow(g: BluetoothGatt, wifiJson: String) {
        val hubService = g.getService(HUB_SERVICE_UUID) ?: run {
            Log.e(TAG, "Missing hub service when writing WiFi")
            g.disconnect(); return
        }
        val wifiChar = hubService.getCharacteristic(WIFI_CHAR_UUID)
        if (wifiChar == null || (wifiChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            Log.e(TAG, "Missing wifi characteristic or not writable")
            g.disconnect()
            return
        }
        wifiChar.value = wifiJson.toByteArray(Charsets.UTF_8)
        val started = g.writeCharacteristic(wifiChar)
        Log.d(TAG, "WiFi write started: $started")
        if (!started) g.disconnect()
    }

    private fun parseAndValidateHubIdentity(rawJson: String): HubIdentity? {
        return try {
            val obj = JSONObject(rawJson)
            val type = obj.optString("type")
            val vendor = obj.optString("vendor")
            val model = obj.optString("model")
            val fw = obj.optString("fw")
            val deviceId = obj.optString("device_id")

            // strict validation: only accept YOUR hub signature
            val ok = (type == "SMARTTUPPLEWARE_HUB" && vendor == "ZHAW" && model == "DVHUB" && deviceId.isNotBlank())
            if (!ok) null else HubIdentity(deviceId = deviceId, fw = fw, vendor = vendor, model = model)
        } catch (_: Exception) {
            null
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onDestroy() {
        super.onDestroy()
        gatt?.close()
        gatt = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleConnectScreen(
    modifier: Modifier = Modifier,
    bluetoothAdapter: BluetoothAdapter?,
    scanner: BluetoothLeScanner?,
    onConnect: (BluetoothDevice) -> Unit
) {
    val context = LocalContext.current

    var hasPermissions by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    var hubs by remember { mutableStateOf<List<HubScanItem>>(emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
        Toast.makeText(
            context,
            if (hasPermissions) "Permissions granted" else "Permissions denied",
            Toast.LENGTH_SHORT
        ).show()
    }

    LaunchedEffect(Unit) {
        hasPermissions = hasBlePermissions(context)
        if (!hasPermissions) requestBlePermissions(permissionLauncher)
    }

    val scanSettings = remember {
        ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
    }

    // Filter: ONLY devices advertising your service UUID
    val scanFilters = remember {
        listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(HUB_SERVICE_UUID))
                .build()
        )
    }

    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                if (result == null) return
                val device = result.device ?: return

                val name = try {
                    device.name ?: result.scanRecord?.deviceName ?: "Hub"
                } catch (_: SecurityException) { "Hub" }

                val item = HubScanItem(device = device, name = name, rssi = result.rssi)

                if (!hubs.any { it.device.address == device.address }) {
                    hubs = hubs + item
                } else {
                    // Update RSSI/name if already present
                    hubs = hubs.map {
                        if (it.device.address == device.address) item else it
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                Toast.makeText(context, "Scan failed ($errorCode)", Toast.LENGTH_LONG).show()
                isScanning = false
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (isScanning && scanner != null) {
                stopSafeScan(scanner, scanCallback)
            }
        }
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(SCAN_TIMEOUT_MS)
            if (isScanning) {
                if (scanner != null) stopSafeScan(scanner, scanCallback)
                isScanning = false
                Toast.makeText(context, "Scan completed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Available Hubs") })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasPermissions) {
                Text("Bluetooth permissions are required.")
                Button(onClick = { requestBlePermissions(permissionLauncher) }) {
                    Text("Grant Permissions")
                }
                return@Column
            }

            if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                Text("Please enable Bluetooth.")
                Button(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                    Text("Open Bluetooth Settings")
                }
                return@Column
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    if (scanner == null) return@Button

                    if (!isScanning) {
                        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled(context)) {
                            Toast.makeText(context, "Enable Location for BLE scanning", Toast.LENGTH_LONG).show()
                            context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            return@Button
                        }

                        hubs = emptyList()
                        val started = startSafeScanFiltered(
                            scanner = scanner,
                            callback = scanCallback,
                            settings = scanSettings,
                            filters = scanFilters,
                            context = context
                        )
                        if (started) {
                            isScanning = true
                            Toast.makeText(context, "Scanning for hubs…", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        stopSafeScan(scanner, scanCallback)
                        isScanning = false
                    }
                }
            ) {
                Text(if (isScanning) "Stop Scan" else "Start Scan")
            }

            if (isScanning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning…")
                }
            }

            Text(
                text = "Hubs found: ${hubs.size}",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            if (hubs.isEmpty() && !isScanning) {
                Text("No hubs found. Tap Start Scan.")
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(hubs, key = { it.device.address }) { hub ->
                        ListItem(
                            headlineContent = { Text(hub.name) },
                            supportingContent = { Text("${hub.device.address}  •  RSSI ${hub.rssi}") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (scanner != null && isScanning) {
                                        stopSafeScan(scanner, scanCallback)
                                        isScanning = false
                                    }
                                    onConnect(hub.device)
                                }
                        )
                    }
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
private fun startSafeScanFiltered(
    scanner: BluetoothLeScanner,
    callback: ScanCallback,
    settings: ScanSettings,
    filters: List<ScanFilter>,
    context: Context
): Boolean {
    return try {
        scanner.startScan(filters, settings, callback)
        Log.d(TAG, "Filtered hub scan started")
        true
    } catch (e: SecurityException) {
        Toast.makeText(context, "Missing Bluetooth permissions", Toast.LENGTH_SHORT).show()
        false
    } catch (e: Exception) {
        Toast.makeText(context, "Scan error: ${e.message}", Toast.LENGTH_SHORT).show()
        false
    }
}

@SuppressLint("MissingPermission")
private fun stopSafeScan(scanner: BluetoothLeScanner, callback: ScanCallback) {
    try {
        scanner.stopScan(callback)
    } catch (_: Exception) {}
}

private fun hasBlePermissions(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val scan = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        val connect = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        scan && connect
    } else {
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        coarse || fine
    }
}

private fun requestBlePermissions(
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
    }
    launcher.launch(permissions)
}

private fun isLocationEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm?.isLocationEnabled == true
        else (lm?.allProviders?.isNotEmpty() == true)
    } catch (_: Exception) {
        false
    }
}
