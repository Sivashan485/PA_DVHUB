package com.example.dvhub

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.dvhub.ui.theme.DVHUBTheme

class BleConnectActivity : ComponentActivity() {

    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothLeScanner: BluetoothLeScanner? = null

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        bluetoothLeScanner = bluetoothAdapter.bluetoothLeScanner

        enableEdgeToEdge()
        setContent {
            DVHUBTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = { Text(text = "Connect Hub") }
                        )
                    }
                ) { innerPadding ->
                    BleConnectScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        bluetoothAdapter = bluetoothAdapter,
                        scanner = bluetoothLeScanner
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleConnectScreen(
    modifier: Modifier = Modifier,
    bluetoothAdapter: BluetoothAdapter?,
    scanner: BluetoothLeScanner?
) {
    val context = LocalContext.current

    var hasPermissions by remember { mutableStateOf(false) }
    var isScanning by remember { mutableStateOf(false) }
    val devices = remember { mutableStateListOf<BluetoothDevice>() }

    // Permission launcher for Android 12+
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.values.all { it }
        hasPermissions = granted
    }

    // Check and request permissions on first composition
    LaunchedEffect(Unit) {
        hasPermissions = hasBlePermissions(context)
        if (!hasPermissions) {
            requestBlePermissions(permissionLauncher)
        }
    }

    // Scan callback
    val scanCallback = remember {
        object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    if (!devices.any { it.address == device.address }) {
                        devices.add(device)
                    }
                }
            }
        }
    }

    // Stop scanning when leaving the screen
    DisposableEffect(isScanning) {
        onDispose {
            if (isScanning && scanner != null && hasPermissions) {
                stopSafeScan(scanner, scanCallback)
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasPermissions) {
            Text(
                text = "Bluetooth permissions required to scan for devices.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = {
                requestBlePermissions(permissionLauncher)
            }) {
                Text("Grant permissions")
            }
        } else if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            Text(
                text = "Please enable Bluetooth to scan for devices.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = {
                context.startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }) {
                Text("Open Bluetooth settings")
            }
        } else {
            Text(
                text = "Available Hubs",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
            )

            Button(
                onClick = {
                    if (!isScanning && scanner != null) {
                        devices.clear()
                        startSafeScan(scanner, scanCallback, hasPermissions)
                        isScanning = true
                    } else if (isScanning && scanner != null) {
                        stopSafeScan(scanner, scanCallback)
                        isScanning = false
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (isScanning) "Stop Scan" else "Start Scan")
            }

            if (isScanning) {
                RowCentered {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 8.dp))
                }
            }

            if (devices.isEmpty() && hasPermissions && bluetoothAdapter.isEnabled && !isScanning) {
                Text(
                    text = "No devices found yet. Tap 'Start Scan' to search.",
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp)
                ) {
                    items(devices) { device ->
                        BleDeviceItem(device = device) { selected ->
                            if (scanner != null && isScanning) {
                                stopSafeScan(scanner, scanCallback)
                                isScanning = false
                            }
                            showConnectingToast(context, selected)
                            // TODO: initiate a real GATT connection here
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RowCentered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun BleDeviceItem(device: BluetoothDevice, onClick: (BluetoothDevice) -> Unit) {
    ListItem(
        headlineContent = {
            Text(text = safeDeviceName(device))
        },
        supportingContent = {
            Text(text = device.address)
        },
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick(device) }
    )
}

@SuppressLint("MissingPermission")
private fun startSafeScan(
    scanner: BluetoothLeScanner,
    callback: ScanCallback,
    hasPermissions: Boolean
) {
    if (!hasPermissions) return
    scanner.startScan(callback)
}

@SuppressLint("MissingPermission")
private fun stopSafeScan(scanner: BluetoothLeScanner, callback: ScanCallback) {
    scanner.stopScan(callback)
}

@SuppressLint("MissingPermission")
private fun safeDeviceName(device: BluetoothDevice): String {
    return device.name ?: "Unknown device"
}

@SuppressLint("MissingPermission")
private fun showConnectingToast(context: Context, device: BluetoothDevice) {
    val name = device.name ?: device.address
    Toast.makeText(
        context,
        "Connecting to $name",
        Toast.LENGTH_SHORT
    ).show()
}

private fun hasBlePermissions(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val scanPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val connectPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
        scanPermission && connectPermission
    } else {
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        coarse || fine
    }
}

private fun requestBlePermissions(
    launcher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>
) {
    val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    launcher.launch(permissions)
}
