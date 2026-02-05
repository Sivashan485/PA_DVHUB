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
import java.util.UUID

private const val TAG = "BleConnectActivity"
private const val SCAN_TIMEOUT_MS = 10_000L

private val HUB_SERVICE_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef0")


data class HubScanItem(
    val device: BluetoothDevice,
    val name: String,
    val rssi: Int
)


class BleConnectActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null
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

        // Setup callbacks for BleManager
        BleManager.onConnectionStateChanged = { connected ->
            runOnUiThread {
                if (connected) {
                    Toast.makeText(this@BleConnectActivity, "Connected, discovering…", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@BleConnectActivity, "Disconnected", Toast.LENGTH_SHORT).show()
                }
            }
        }

        BleManager.onHubVerified = { identity ->
            runOnUiThread {
                Toast.makeText(this@BleConnectActivity, "Hub verified: ${identity.deviceId}", Toast.LENGTH_SHORT).show()
                // Navigate to NetworkSetupActivity after successful verification
                val deviceName = try {
                    device.name ?: identity.deviceId
                } catch (_: SecurityException) {
                    identity.deviceId
                }
                navigateToNetworkSetup(deviceName)
            }
        }

        // Connect using BleManager
        BleManager.connectToDevice(this, device)
    }

    private fun navigateToNetworkSetup(deviceName: String) {
        val intent = Intent(this, NetworkSetupActivity::class.java).apply {
            putExtra("DEVICE_ADDRESS", pendingDevice?.address ?: "")
            putExtra("DEVICE_NAME", deviceName)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        // BleManager maintains the GATT connection for NetworkSetupActivity
        // Don't disconnect here, let NetworkSetupActivity handle it
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
    } catch (_: SecurityException) {
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
