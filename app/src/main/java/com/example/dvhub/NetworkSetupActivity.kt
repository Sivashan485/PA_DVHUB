package com.example.dvhub

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dvhub.ui.theme.DVHUBTheme

class NetworkSetupActivity : ComponentActivity() {

    private var deviceAddress: String? = null
    private var deviceName: String? = null
    private lateinit var wifiManager: WifiManager
    private val availableNetworks = mutableStateListOf<ScanResult>()
    private var isScanning by mutableStateOf(false)

    private val wifiScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                if (success) {
                    handleScanResults()
                }
                isScanning = false
            }
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            startWifiScan()
        } else {
            Toast.makeText(this, "Location permission is required to scan WiFi networks", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get device info from intent
        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        deviceName = intent.getStringExtra("DEVICE_NAME")

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        // Register WiFi scan receiver
        val intentFilter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        registerReceiver(wifiScanReceiver, intentFilter)

        // Setup callback for WiFi credentials sent
        BleManager.onWifiCredentialsSent = { success, error ->
            runOnUiThread {
                if (success) {
                    Toast.makeText(this, "WiFi credentials sent successfully ✅", Toast.LENGTH_LONG).show()
                    // Optionally disconnect after successful send
                    // BleManager.disconnect()
                    finish()
                } else {
                    Toast.makeText(this, "Failed to send credentials: ${error ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }

        enableEdgeToEdge()
        setContent {
            DVHUBTheme {
                NetworkSetupScreen(
                    deviceName = deviceName ?: "Unknown Hub",
                    networks = availableNetworks,
                    isScanning = isScanning,
                    onScanWifi = {
                        checkPermissionsAndScan()
                    },
                    onSubmit = { ssid, password ->
                        Toast.makeText(this, "Sending WiFi credentials...", Toast.LENGTH_SHORT).show()
                        BleManager.sendWifiCredentials(ssid, password)
                    },
                    onCancel = {
                        BleManager.disconnect()
                        finish()
                    }
                )
            }
        }

        // Start initial scan
        checkPermissionsAndScan()
    }

    private fun checkPermissionsAndScan() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            startWifiScan()
        } else {
            locationPermissionLauncher.launch(permissions)
        }
    }

    private fun startWifiScan() {
        isScanning = true
        val success = wifiManager.startScan()
        if (!success) {
            Toast.makeText(this, "Failed to start WiFi scan", Toast.LENGTH_SHORT).show()
            isScanning = false
        }
    }

    @Suppress("MissingPermission")
    private fun handleScanResults() {
        val results = wifiManager.scanResults
        availableNetworks.clear()
        // Filter out duplicate SSIDs and empty SSIDs, keep strongest signal
        val uniqueNetworks = results
            .filter { it.SSID.isNotBlank() }
            .groupBy { it.SSID }
            .map { (_, networks) -> networks.maxByOrNull { it.level }!! }
            .sortedByDescending { it.level }
        availableNetworks.addAll(uniqueNetworks)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(wifiScanReceiver)
        // Clear the callback
        BleManager.onWifiCredentialsSent = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSetupScreen(
    deviceName: String,
    networks: List<ScanResult>,
    isScanning: Boolean,
    onScanWifi: () -> Unit,
    onSubmit: (ssid: String, password: String) -> Unit,
    onCancel: () -> Unit
) {
    var selectedSsid by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var showPasswordDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Setup") },
                actions = {
                    IconButton(
                        onClick = onScanWifi,
                        enabled = !isScanning
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Scan WiFi"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Configure WiFi for:",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = deviceName,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Available Networks",
                style = MaterialTheme.typography.titleMedium
            )

            if (isScanning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Scanning...")
                }
            } else if (networks.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "No networks found. Tap refresh to scan.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(networks) { network ->
                        NetworkItem(
                            scanResult = network,
                            isSelected = selectedSsid == network.SSID,
                            onClick = {
                                selectedSsid = network.SSID
                                showPasswordDialog = true
                            }
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }

    // Password dialog
    if (showPasswordDialog && selectedSsid != null) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Enter Password") },
            text = {
                Column {
                    Text(
                        text = "Network: $selectedSsid",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("WiFi Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (selectedSsid != null) {
                            onSubmit(selectedSsid!!, password)
                            showPasswordDialog = false
                        }
                    }
                ) {
                    Text("Connect")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPasswordDialog = false
                        password = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun NetworkItem(
    scanResult: ScanResult,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = getSignalIndicator(scanResult.level),
                style = MaterialTheme.typography.headlineSmall,
                color = when {
                    scanResult.level >= -50 -> MaterialTheme.colorScheme.primary
                    scanResult.level >= -70 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.error
                }
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = scanResult.SSID,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = getSecurityType(scanResult),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Text(
                text = "${WifiManager.calculateSignalLevel(scanResult.level, 5)}/4",
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
        }
    }
}

fun getSecurityType(scanResult: ScanResult): String {
    val capabilities = scanResult.capabilities
    return when {
        capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        else -> "Open"
    }
}

fun getSignalIndicator(level: Int): String {
    val bars = WifiManager.calculateSignalLevel(level, 4)
    return when (bars) {
        0 -> "▂___"
        1 -> "▂▄__"
        2 -> "▂▄▆_"
        3 -> "▂▄▆█"
        else -> "____"
    }
}
