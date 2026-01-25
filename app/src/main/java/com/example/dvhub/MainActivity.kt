package com.example.dvhub

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import com.example.dvhub.ui.theme.DVHUBTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DVHUBTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WelcomePage(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun WelcomePage(modifier: Modifier = Modifier) {

    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Header text, centered
        Text(
            text = "Welcome 👋, Setup your Hub and Save food from going to waste!",
            style = TextStyle(fontSize = 25.sp, fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        )

        // Main content: image and instruction text stacked
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val imageShape = RoundedCornerShape(16.dp)

            Image(
                painter = androidx.compose.ui.res.painterResource(id = R.drawable.device_img),
                contentDescription = "Welcome Image",
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(imageShape)
                    .border(2.dp, Color.Black, imageShape)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Please connect power to the hub via USB Type-C or through Ethernet (PoE).",
                style = TextStyle(fontSize = 16.sp),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )
        }

        val buttonShape = RoundedCornerShape(24.dp)

        // Bottom button - beautified, navigates to BleConnectActivity
        Button(
            onClick = {
                val intent = Intent(context, BleConnectActivity::class.java)
                context.startActivity(intent)
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .clip(buttonShape),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2E7D32),    // green, matches "save food/planet" theme
                contentColor = Color.White
            ),
            shape = buttonShape
        ) {
            Text(
                text = "Get Started",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}