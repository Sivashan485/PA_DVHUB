package com.example.dvhub

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.dvhub.ui.theme.DVHUBTheme

enum class NetworkType { WIFI, ETHERNET }

class NetworkTypeSelectionActivity : ComponentActivity() {

    private var deviceAddress: String? = null
    private var deviceName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Get device info from intent
        deviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        deviceName = intent.getStringExtra("DEVICE_NAME")

        enableEdgeToEdge()
        setContent {
            DVHUBTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    NetworkTypeSelectionPage(
                        modifier = Modifier.padding(innerPadding),
                        deviceAddress = deviceAddress,
                        deviceName = deviceName
                    )
                }
            }
        }
    }
}

@Composable
fun NetworkTypeSelectionPage(
    modifier: Modifier = Modifier,
    deviceAddress: String? = null,
    deviceName: String? = null
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<NetworkType?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Choose your network type",
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Select how your hub will connect to the internet.",
                style = TextStyle(fontSize = 16.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            NetworkOptionCard(
                title = "Wi-Fi",
                subtitle = "Connect using a wireless network (2.4 GHz recommended).",
                selected = selected == NetworkType.WIFI,
                onClick = { selected = NetworkType.WIFI }
            )

            Spacer(modifier = Modifier.height(12.dp))

            NetworkOptionCard(
                title = "Ethernet (PoE)",
                subtitle = "Connect via Ethernet cable (Power over Ethernet if supported).",
                selected = selected == NetworkType.ETHERNET,
                onClick = { selected = NetworkType.ETHERNET }
            )
        }

        // Bottom action
        val buttonShape = RoundedCornerShape(24.dp)

        Button(
            onClick = {
                when (selected) {
                    NetworkType.WIFI -> {
                        val intent = Intent(context, WifiSetupActivity::class.java).apply {
                            putExtra("DEVICE_ADDRESS", deviceAddress ?: "")
                            putExtra("DEVICE_NAME", deviceName ?: "Unknown Hub")
                        }
                        context.startActivity(intent)
                    }
                    NetworkType.ETHERNET -> {
                        val intent = Intent(context, EthernetSetupActivity::class.java).apply {
                            putExtra("DEVICE_ADDRESS", deviceAddress ?: "")
                            putExtra("DEVICE_NAME", deviceName ?: "Unknown Hub")
                        }
                        context.startActivity(intent)
                    }
                    null -> Unit
                }
            },
            enabled = selected != null,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),
                contentColor = Color.White,
                disabledContainerColor = Color(0xFFBDBDBD),
                disabledContentColor = Color.White
            ),
            shape = buttonShape
        ) {
            Text(
                text = "Continue",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun NetworkOptionCard(
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
            Text(
                text = title,
                style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold,         color = if (selected) Color.Black else Color.White
                )

            )
            Text(
                text = if (selected) "Selected" else "",
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
                color = Color(0xFF2E7D32)
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = subtitle,
            style = TextStyle(fontSize = 14.sp),
            color = Color(0xFF424242)
        )
    }
}

/**
 * TODO placeholders — create these or replace with your actual activities.
 */
class EthernetSetupActivity : ComponentActivity()
