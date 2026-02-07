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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.dvhub.ui.theme.DVHUBTheme

class WifiSetupActivity : ComponentActivity() {

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

    val green = Color(0xFF2E7D32)
    val shape = RoundedCornerShape(16.dp)

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Wi-Fi Setup") })
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {

            // ===== TOP CONTENT =====
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Connect your hub to Wi-Fi",
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Select a Wi-Fi network for $deviceName.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(16.dp))

                // Scan button (primary)
                Button(
                    onClick = onScanWifi,
                    enabled = !isScanning,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = green,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFBDBDBD),
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text(if (isScanning) "Scanning…" else "Scan for networks", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(12.dp))

                if (isScanning) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Looking for nearby Wi-Fi…")
                    }
                    Spacer(Modifier.height(8.dp))
                }

                Text(
                    text = "Available networks: ${networks.size}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))

                if (!isScanning && networks.isEmpty()) {
                    // empty state card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        shape = shape
                    ) {
                        Text(
                            text = "No networks found. Tap Scan to try again.",
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(networks, key = { it.BSSID ?: it.SSID }) { network ->
                            WifiOptionCard(
                                ssid = network.SSID,
                                subtitle = "${getSecurityType(network)}  •  Signal ${WifiManager.calculateSignalLevel(network.level, 5)}/4",
                                indicator = getSignalIndicator(network.level),
                                selected = selectedSsid == network.SSID,
                                onClick = { selectedSsid = network.SSID
                                    showPasswordDialog = true   // ✅ open dialog immediately

                                }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
            }

            // ===== BOTTOM ACTIONS =====
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { showPasswordDialog = true },
                    enabled = selectedSsid != null,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = green,
                        contentColor = Color.White,
                        disabledContainerColor = Color(0xFFBDBDBD),
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Continue", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }

    // ===== PASSWORD DIALOG =====
    if (showPasswordDialog && selectedSsid != null) {
        AlertDialog(
            onDismissRequest = { showPasswordDialog = false },
            title = { Text("Enter Wi-Fi password") },
            text = {
                Column {
                    Text(
                        text = "Network: ${selectedSsid!!}",
                        color = green,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                    )
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text("Wi-Fi Password") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSubmit(selectedSsid!!, password)
                        showPasswordDialog = false
                        password = ""
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = green,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(24.dp)
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
                    Text("Back")
                }
            }
        )
    }
}

@Composable
private fun WifiOptionCard(
    ssid: String,
    subtitle: String,
    indicator: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val green = Color(0xFF2E7D32)
    val shape = RoundedCornerShape(16.dp)
    val borderColor = if (selected) green else Color.Black
    val backgroundColor = if (selected) Color(0xFFE8F5E9) else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(2.dp, borderColor, shape)
            .background(backgroundColor, shape)
            .clickable { onClick() }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = ssid,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )
            Text(
                text = if (selected) "Selected" else "",
                color = green,
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = indicator,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = green
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF424242)
            )
        }
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
