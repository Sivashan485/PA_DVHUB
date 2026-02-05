package com.example.dvhub

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.dvhub.ui.theme.DVHUBTheme

class NetworkSetupActivity : ComponentActivity() {

    private var deviceAddress: String? = null
    private var deviceName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get device info from intent
        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        deviceName = intent.getStringExtra("DEVICE_NAME")

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
    }

    override fun onDestroy() {
        super.onDestroy()
        // Clear the callback
        BleManager.onWifiCredentialsSent = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkSetupScreen(
    deviceName: String,
    onSubmit: (ssid: String, password: String) -> Unit,
    onCancel: () -> Unit
) {
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Setup") }
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

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("WiFi SSID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("WiFi Password") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    if (ssid.isBlank()) {
                        return@Button
                    }
                    onSubmit(ssid, password)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = ssid.isNotBlank()
            ) {
                Text("Send Credentials")
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        }
    }
}
