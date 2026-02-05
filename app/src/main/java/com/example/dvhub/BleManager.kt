package com.example.dvhub

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.UUID

private const val TAG = "BleManager"

private val HUB_SERVICE_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef0")

private val HUB_INFO_CHAR_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef3")

private val AUTH_CHAR_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef4")

private val WIFI_CHAR_UUID: UUID =
    UUID.fromString("12345678-1234-5678-1234-56789abcdef2")

data class HubIdentity(
    val deviceId: String,
    val fw: String,
    val vendor: String,
    val model: String
)

/**
 * Singleton BLE manager to maintain connection across activities
 */
object BleManager {
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var hubIdentity: HubIdentity? = null
    private val authToken = "pair-token-123"

    var onConnectionStateChanged: ((Boolean) -> Unit)? = null
    var onHubVerified: ((HubIdentity) -> Unit)? = null
    var onWifiCredentialsSent: ((Boolean, String?) -> Unit)? = null

    private var isAuthenticated = false
    private var pendingWifiJson: String? = null

    @SuppressLint("MissingPermission")
    fun connectToDevice(context: Context, device: BluetoothDevice) {
        Log.d(TAG, "Connecting to ${device.address}")
        connectedDevice = device
        isAuthenticated = false
        pendingWifiJson = null

        val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt?, status: Int, newState: Int) {
                if (g == null) return

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        Log.d(TAG, "Connected. Discovering services...")
                        onConnectionStateChanged?.invoke(true)
                        g.discoverServices()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.d(TAG, "Disconnected")
                        onConnectionStateChanged?.invoke(false)
                        g.close()
                        if (gatt == g) {
                            gatt = null
                            connectedDevice = null
                            hubIdentity = null
                            isAuthenticated = false
                        }
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
                    g.disconnect()
                    return
                }

                val hubInfoChar = hubService.getCharacteristic(HUB_INFO_CHAR_UUID)
                if (hubInfoChar == null || (hubInfoChar.properties and BluetoothGattCharacteristic.PROPERTY_READ) == 0) {
                    Log.e(TAG, "Not a hub (missing hub info char)")
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

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                handleCharacteristicRead(g, ch, value, status)
            }

            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicRead(
                g: BluetoothGatt?,
                ch: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (g == null || ch == null) return
                @Suppress("DEPRECATION")
                val value = ch.value ?: byteArrayOf()
                handleCharacteristicRead(g, ch, value, status)
            }

            private fun handleCharacteristicRead(
                g: BluetoothGatt,
                ch: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(TAG, "Read failed: $status")
                    g.disconnect()
                    return
                }

                if (ch.uuid == HUB_INFO_CHAR_UUID) {
                    val raw = value.toString(Charsets.UTF_8)
                    Log.d(TAG, "Hub info: $raw")

                    val identity = parseAndValidateHubIdentity(raw)
                    if (identity == null) {
                        Log.e(TAG, "Invalid hub identity")
                        g.disconnect()
                        return
                    }

                    hubIdentity = identity
                    Log.d(TAG, "Hub verified: ${identity.deviceId}")

                    // Send authentication
                    val hubService = g.getService(HUB_SERVICE_UUID) ?: run {
                        g.disconnect()
                        return
                    }
                    val authChar = hubService.getCharacteristic(AUTH_CHAR_UUID)

                    if (authChar != null && (authChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                        val authBytes = authToken.toByteArray(Charsets.UTF_8)
                        val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            val result = g.writeCharacteristic(authChar, authBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                            result == BluetoothGatt.GATT_SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            authChar.value = authBytes
                            @Suppress("DEPRECATION")
                            g.writeCharacteristic(authChar) == true
                        }
                        Log.d(TAG, "Auth write started: $writeSuccess")
                        if (!writeSuccess) {
                            g.disconnect()
                            return
                        }
                    } else {
                        Log.w(TAG, "No auth characteristic; proceeding without auth")
                        isAuthenticated = true
                    }

                    // Notify that hub is verified
                    onHubVerified?.invoke(identity)
                }
            }

            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicWrite(
                g: BluetoothGatt?,
                ch: BluetoothGattCharacteristic?,
                status: Int
            ) {
                if (g == null || ch == null) return

                if (ch.uuid == AUTH_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Auth OK")
                        isAuthenticated = true

                        // If there's pending WiFi credentials, send them now
                        pendingWifiJson?.let { json ->
                            writeWifiJsonNow(g, json)
                        }
                    } else {
                        Log.e(TAG, "Auth failed: $status")
                        isAuthenticated = false
                        onWifiCredentialsSent?.invoke(false, "Authentication failed")
                        g.disconnect()
                    }
                    return
                }

                if (ch.uuid == WIFI_CHAR_UUID) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "WiFi credentials sent successfully")
                        pendingWifiJson = null
                        onWifiCredentialsSent?.invoke(true, null)
                    } else {
                        Log.e(TAG, "WiFi write failed: $status")
                        onWifiCredentialsSent?.invoke(false, "Write failed with status: $status")
                    }
                }
            }
        }

        gatt?.close()
        gatt = device.connectGatt(context, false, callback)
    }

    @SuppressLint("MissingPermission")
    fun sendWifiCredentials(ssid: String, password: String) {
        val json = JSONObject()
            .put("ssid", ssid)
            .put("password", password)
            .toString()

        Log.d(TAG, "Preparing to send WiFi credentials: $json")
        pendingWifiJson = json

        val g = gatt
        if (g != null) {
            if (isAuthenticated) {
                writeWifiJsonNow(g, json)
            } else {
                Log.d(TAG, "Waiting for authentication before sending WiFi credentials")
            }
        } else {
            Log.e(TAG, "No GATT connection available")
            onWifiCredentialsSent?.invoke(false, "Not connected")
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeWifiJsonNow(g: BluetoothGatt, wifiJson: String) {
        val hubService = g.getService(HUB_SERVICE_UUID) ?: run {
            Log.e(TAG, "Missing hub service when writing WiFi")
            onWifiCredentialsSent?.invoke(false, "Service not found")
            g.disconnect()
            return
        }

        val wifiChar = hubService.getCharacteristic(WIFI_CHAR_UUID)
        if (wifiChar == null || (wifiChar.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) == 0) {
            Log.e(TAG, "Missing wifi characteristic or not writable")
            onWifiCredentialsSent?.invoke(false, "WiFi characteristic not writable")
            g.disconnect()
            return
        }

        val wifiBytes = wifiJson.toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Writing WiFi credentials: $wifiJson (${wifiBytes.size} bytes)")

        val writeSuccess = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = g.writeCharacteristic(wifiChar, wifiBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            result == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            wifiChar.value = wifiBytes
            @Suppress("DEPRECATION")
            g.writeCharacteristic(wifiChar) == true
        }

        Log.d(TAG, "WiFi write started: $writeSuccess")

        if (!writeSuccess) {
            onWifiCredentialsSent?.invoke(false, "Failed to start write")
            g.disconnect()
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse hub identity", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        connectedDevice = null
        hubIdentity = null
        isAuthenticated = false
        pendingWifiJson = null
    }

    fun getConnectedDevice(): BluetoothDevice? = connectedDevice
    fun getHubIdentity(): HubIdentity? = hubIdentity
    fun isConnected(): Boolean = gatt != null && connectedDevice != null
}
