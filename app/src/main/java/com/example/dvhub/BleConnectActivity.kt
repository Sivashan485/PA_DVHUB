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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dvhub.ui.theme.DVHUBTheme
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
            DVHUBTheme {
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
        val intent = Intent(this, NetworkTypeSelectionActivity::class.java).apply {
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
    var selectedAddress by remember { mutableStateOf<String?>(null) }

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

                hubs = if (!hubs.any { it.device.address == device.address }) {
                    hubs + item
                } else {
                    hubs.map { if (it.device.address == device.address) item else it }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed: $errorCode")
                Toast.makeText(context, "Scan failed ($errorCode)", Toast.LENGTH_LONG).show()
                isScanning = false
            }
        }
    }

    fun stopScanIfRunning() {
        if (scanner != null && isScanning) {
            stopSafeScan(scanner, scanCallback)
            isScanning = false
        }
    }

    fun startOrStopScan() {
        if (scanner == null) return

        if (!isScanning) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled(context)) {
                Toast.makeText(context, "Enable Location for BLE scanning", Toast.LENGTH_LONG).show()
                context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return
            }

            hubs = emptyList()
            selectedAddress = null

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
            stopScanIfRunning()
        }
    }

    DisposableEffect(Unit) {
        onDispose { stopScanIfRunning() }
    }

    LaunchedEffect(isScanning) {
        if (isScanning) {
            delay(SCAN_TIMEOUT_MS)
            if (isScanning) {
                stopScanIfRunning()
                Toast.makeText(context, "Scan completed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text("Connect to a Hub") })
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // ======= TOP CONTENT (like your network page) =======
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Find your hub",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Start a scan and select the hub you want to set up. It may take a few seconds to connect to the device. ",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(16.dp))

                // ======= STATES =======
                if (!hasPermissions) {
                    InfoCard(
                        title = "Bluetooth permissions required",
                        subtitle = "Grant permissions to scan and connect.",
                        actionText = "Grant Permissions",
                        onAction = { requestBlePermissions(permissionLauncher) }
                    )
                    return@Column
                }

                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
                    InfoCard(
                        title = "Bluetooth is off",
                        subtitle = "Turn on Bluetooth to find nearby hubs.",
                        actionText = "Open Bluetooth Settings",
                        onAction = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                    )
                    return@Column
                }

                // ======= SCAN BUTTON =======
                Button(
                    onClick = { startOrStopScan() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2E7D32),
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFBDBDBD),
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(if (isScanning) "Stop Scan" else "Start Scan", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Scanning…", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(6.dp))
                }

                Text(
                    text = "Hubs found: ${hubs.size}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                // ======= HUB LIST AS CARDS =======
                if (hubs.isEmpty() && !isScanning) {
                    Text(
                        text = "No hubs found. Tap Start Scan.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp) // prevents pushing the bottom button off-screen
                    ) {
                        items(hubs, key = { it.device.address }) { hub ->
                            val selected = selectedAddress == hub.device.address

                            HubOptionCard(
                                title = hub.name,
                                subtitle = "${hub.device.address}  •  RSSI ${hub.rssi}",
                                selected = selected,
                                onClick = { selectedAddress = hub.device.address }
                            )

                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
            }

            // ======= BOTTOM ACTION (like Continue) =======
            val selectedDevice = hubs.firstOrNull { it.device.address == selectedAddress }?.device
            Button(
                onClick = {
                    val device = selectedDevice ?: return@Button
                    stopScanIfRunning()
                    onConnect(device)
                },
                enabled = selectedDevice != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF2E7D32),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFFBDBDBD),
                    disabledContentColor = Color.White
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("Continue", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun HubOptionCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) Color(0xFF2E7D32) else Color.Black
    val backgroundColor = if (selected) Color(0xFFE8F5E9) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(width = 2.dp, color = borderColor, shape = shape)
            .background(color = backgroundColor, shape = shape)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, fontWeight = FontWeight.Bold,
                    color = if (selected) Color.Black else Color.White,

                )
            Text(
                text = if (selected) "Selected" else "",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = subtitle,
            color = Color(0xFF424242),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun InfoCard(
    title: String,
    subtitle: String,
    actionText: String,
    onAction: () -> Unit
) {
    val shape = RoundedCornerShape(16.dp)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, Color.Black, shape)
            .padding(16.dp)
    ) {
        Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF424242))
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onAction,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(24.dp)
        ) {
            Text(actionText)
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
