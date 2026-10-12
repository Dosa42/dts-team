package com.example.hostuser

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Custom tablet icin sinirsiz komut calistirma ve sistem yonetim motoru.
 * WebSocket veya yerel tetiklemeyle gelen donanim ve kabuk komutlarini dogrudan calistirir.
 */
class DeviceCommandExecutor(private val context: Context) {

    companion object {
        private const val TAG = "DeviceCommandExecutor"
    }

    data class ShellResult(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val success: Boolean
    )

    /**
     * Root veya dogrudan Android sh uzerinden kabuk komutu calistirir.
     */
    fun executeShell(command: String): ShellResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = StringBuilder()
            val stderr = StringBuilder()

            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))

            var line: String?
            while (outReader.readLine().also { line = it } != null) {
                stdout.append(line).append("\n")
            }
            while (errReader.readLine().also { line = it } != null) {
                stderr.append(line).append("\n")
            }

            val completed = process.waitFor(15, TimeUnit.SECONDS)
            val exitCode = if (completed) process.exitValue() else -1

            ShellResult(
                exitCode = exitCode,
                stdout = stdout.toString().trim(),
                stderr = stderr.toString().trim(),
                success = completed && exitCode == 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Shell execution error: ${e.message}", e)
            ShellResult(-1, "", e.message ?: "Unknown error", false)
        }
    }

    /**
     * Pil durumunu ve yuzdesini okur.
     */
    fun getBatteryInfo(): JSONObject {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1

        return JSONObject().apply {
            put("level_percent", batteryPct)
            put("is_charging", isCharging)
            put("status_code", status)
        }
    }

    /**
     * Sistem ve ag telemetrisini toplar.
     */
    fun getSystemTelemetry(): JSONObject {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val activeNet = cm?.activeNetwork
        val caps = cm?.getNetworkCapabilities(activeNet)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ?: false
        val isCellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ?: false
        val isEthernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ?: false

        val runtime = Runtime.getRuntime()
        val totalMemMb = runtime.totalMemory() / (1024 * 1024)
        val freeMemMb = runtime.freeMemory() / (1024 * 1024)

        return JSONObject().apply {
            put("device_model", Build.MODEL)
            put("device_manufacturer", Build.MANUFACTURER)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("jvm_total_mem_mb", totalMemMb)
            put("jvm_free_mem_mb", freeMemMb)
            put("network_wifi", isWifi)
            put("network_cellular", isCellular)
            put("network_ethernet", isEthernet)
            put("battery", getBatteryInfo())
        }
    }

    /**
     * Cihazi yeniden baslatir.
     */
    fun rebootDevice(): Boolean {
        // 1. Yol: PowerManager reboot
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.reboot(null)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "PowerManager reboot failed, trying shell: ${e.message}")
        }

        // 2. Yol: Root / system shell reboot
        val res = executeShell("/system/bin/reboot")
        return res.success
    }

    /**
     * Gelen WebSocket mesajini isler ve calistirir.
     * JSON veya duz metin formatindaki yonetim komutlarini kabul eder.
     */
    fun handleIncomingMessage(rawMessage: String): String {
        Log.i(TAG, "Executing message: $rawMessage")
        return try {
            val trimmed = rawMessage.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                val json = JSONObject(trimmed)
                val action = json.optString("action", "")

                when (action.lowercase()) {
                    "shell", "exec" -> {
                        val cmd = json.optString("cmd", "")
                        val res = executeShell(cmd)
                        JSONObject().apply {
                            put("action", action)
                            put("success", res.success)
                            put("exit_code", res.exitCode)
                            put("stdout", res.stdout)
                            put("stderr", res.stderr)
                        }.toString()
                    }
                    "telemetry", "info", "status" -> {
                        JSONObject().apply {
                            put("action", action)
                            put("telemetry", getSystemTelemetry())
                        }.toString()
                    }
                    "reboot" -> {
                        val ok = rebootDevice()
                        JSONObject().apply {
                            put("action", "reboot")
                            put("initiated", ok)
                        }.toString()
                    }
                    "ping" -> {
                        JSONObject().apply {
                            put("action", "pong")
                            put("timestamp", System.currentTimeMillis())
                        }.toString()
                    }
                    else -> {
                        JSONObject().apply {
                            put("error", "Unknown action: $action")
                        }.toString()
                    }
                }
            } else {
                // Duz metin komut
                when (trimmed.lowercase()) {
                    "ping" -> "pong"
                    "reboot" -> if (rebootDevice()) "rebooting" else "reboot_failed"
                    "info" -> getSystemTelemetry().toString()
                    else -> {
                        val res = executeShell(trimmed)
                        "exit=${res.exitCode}\nout=${res.stdout}\nerr=${res.stderr}"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message execution error: ${e.message}", e)
            "error: ${e.message}"
        }
    }
}
