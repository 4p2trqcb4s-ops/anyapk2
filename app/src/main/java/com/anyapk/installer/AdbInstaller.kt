package com.anyapk.installer

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

object AdbInstaller {

    private const val LOCALHOST = "127.0.0.1"
    private const val TARGET_PACKAGE = "com.carriez.flutter_hbb"

    enum class ConnectionStatus {
        NOT_CONNECTED,
        CONNECTED,
        NEEDS_PAIRING,
        ERROR
    }

    @Volatile private var lastConnectionCheck: Long = 0
    @Volatile private var lastConnectionStatus: ConnectionStatus = ConnectionStatus.NEEDS_PAIRING
    private const val CONNECTION_CACHE_MS = 2000L

    // ─────────────────────────────────────────────
    // Toast helper (safe to call from any thread)
    // ─────────────────────────────────────────────

    private fun toast(context: Context, message: String, long: Boolean = false) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context,
                message,
                if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ─────────────────────────────────────────────
    // Shell command runner (suspend, reusable)
    // ─────────────────────────────────────────────

    private suspend fun runShell(
        manager: AbsAdbConnectionManager,
        command: String,
        timeoutMs: Int = 5000
    ): String = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        return@withContext try {
            stream = manager.openStream("shell:$command")
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)
            val output = StringBuilder()
            var waited = 0

            while (waited < timeoutMs) {
                if (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) output.append(String(buffer, 0, bytesRead))
                    // Keep reading if more data is available
                    if (inputStream.available() == 0) {
                        delay(50)
                        if (inputStream.available() == 0) break
                    }
                } else {
                    delay(100)
                    waited += 100
                }
            }

            output.toString().trim()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        } finally {
            try { stream?.close() } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────
    // Connection status
    // ─────────────────────────────────────────────

    fun getConnectionStatus(context: Context, forceCheck: Boolean = false): ConnectionStatus {
        val now = System.currentTimeMillis()
        if (!forceCheck && (now - lastConnectionCheck) < CONNECTION_CACHE_MS) {
            return lastConnectionStatus
        }

        val status = try {
            val manager = AdbConnectionManager.getInstance(context)
            if (!manager.autoConnect(context, 3000)) {
                ConnectionStatus.NEEDS_PAIRING
            } else {
                runBlocking {
                    val result = runShell(manager, "echo __ping__", 3000)
                    if (result.contains("__ping__")) ConnectionStatus.CONNECTED
                    else ConnectionStatus.NEEDS_PAIRING
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            ConnectionStatus.ERROR
        }

        lastConnectionCheck = System.currentTimeMillis()
        lastConnectionStatus = status
        return status
    }

    // ─────────────────────────────────────────────
    // Pairing
    // ─────────────────────────────────────────────

    suspend fun pair(
        context: Context,
        pairingCode: String,
        pairingPort: Int
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            toast(context, "⏳ Pairing with code $pairingCode...")
            val manager = AdbConnectionManager.getInstance(context)
            manager.pair(LOCALHOST, pairingPort, pairingCode)
            toast(context, "✅ Pairing successful!", long = true)
            Result.success(true)
        } catch (e: Exception) {
            e.printStackTrace()
            toast(context, "❌ Pairing failed: ${e.message}", long = true)
            Result.failure(e)
        }
    }

    // ─────────────────────────────────────────────
    // Test connection
    // ─────────────────────────────────────────────

    suspend fun testConnection(context: Context): Result<Boolean> = withContext(Dispatchers.IO) {
        return@withContext try {
            toast(context, "⏳ Testing ADB connection...")
            val manager = AdbConnectionManager.getInstance(context)

            if (!manager.autoConnect(context, 10000)) {
                val msg = "❌ Could not connect. Enable Wireless Debugging."
                toast(context, msg, long = true)
                return@withContext Result.failure(Exception(msg))
            }

            // Basic echo test
            val echo = runShell(manager, "echo __test__", 5000)
            if (!echo.contains("__test__")) {
                val msg = "❌ ADB connected but not authorized. Approve the prompt on device."
                toast(context, msg, long = true)
                manager.close()
                return@withContext Result.failure(Exception(msg))
            }

            // Extra diagnostics
            val sdkVersion = runShell(manager, "getprop ro.build.version.sdk")
            val deviceModel = runShell(manager, "getprop ro.product.model")
            val adbUser = runShell(manager, "id")

            manager.close()

            val info = "✅ Connected!\nDevice: $deviceModel (API $sdkVersion)\nUser: $adbUser"
            toast(context, info, long = true)
            Result.success(true)

        } catch (e: Exception) {
            e.printStackTrace()
            toast(context, "❌ Connection error: ${e.message}", long = true)
            Result.failure(Exception("Authorization required. Check for debug prompt on device."))
        }
    }

    // ─────────────────────────────────────────────
    // Install APK
    // ─────────────────────────────────────────────

    suspend fun install(context: Context, apkPath: String): Result<String> = withContext(Dispatchers.IO) {
        var stream: AdbStream? = null
        var manager: AbsAdbConnectionManager? = null

        try {
            lastConnectionCheck = 0 // Invalidate cache
            toast(context, "⏳ Connecting to ADB...")

            manager = object : AbsAdbConnectionManager() {
                private val delegate = AdbConnectionManager.getInstance(context)
                override fun getPrivateKey() = delegate.getPrivateKey()
                override fun getCertificate() = delegate.getCertificate()
                override fun getDeviceName() = delegate.getDeviceName()
            }
            manager.setApi(Build.VERSION.SDK_INT)

            if (!manager.autoConnect(context, 10000)) {
                val msg = "❌ Failed to connect. Ensure Wireless Debugging is enabled and paired."
                toast(context, msg, long = true)
                return@withContext Result.failure(Exception(msg))
            }

            // Check if already installed
            val existingVersion = runShell(
                manager,
                "dumpsys package $TARGET_PACKAGE | grep versionName",
                3000
            )
            if (existingVersion.isNotEmpty()) {
                toast(context, "ℹ️ Existing version found: $existingVersion — upgrading...")
            }

            val apkFile = java.io.File(apkPath)
            val apkSize = apkFile.length()
            toast(context, "📦 Installing ${apkFile.name} (${apkSize / 1024}KB)...")

            // Stream APK via exec:cmd install
            stream = manager.openStream("exec:cmd package install -g -r -t -S $apkSize")
            val outputStream = stream.openOutputStream()

            java.io.FileInputStream(apkFile).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                }
                outputStream.flush()
            }

            // Read install result
            val output = StringBuilder()
            val inputStream = stream.openInputStream()
            val buffer = ByteArray(1024)
            var waited = 0
            val maxWait = 30000

            while (waited < maxWait) {
                if (inputStream.available() > 0) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break
                    if (bytesRead > 0) output.append(String(buffer, 0, bytesRead))
                } else {
                    delay(100)
                    waited += 100
                }
                val current = output.toString()
                if (current.contains("Success", ignoreCase = true) ||
                    current.contains("Failure", ignoreCase = true) ||
                    current.contains("Error", ignoreCase = true)
                ) break
            }

            stream.close()
            stream = null

            val installResult = output.toString().trim()

            if (!installResult.contains("Success", ignoreCase = true)) {
                val msg = "❌ Install failed: ${installResult.ifEmpty { "Unknown error" }}"
                toast(context, msg, long = true)
                return@withContext Result.failure(Exception(msg))
            }

            toast(context, "✅ APK installed! Granting permissions...")

            // Update cache
            lastConnectionCheck = System.currentTimeMillis()
            lastConnectionStatus = ConnectionStatus.CONNECTED

            delay(800)

            // Grant permissions
            val permResult = grantPermissions(context, manager, TARGET_PACKAGE)

            val finalMsg = if (permResult.isSuccess) {
                "🎉 Install complete!\n${permResult.getOrNull()}"
            } else {
                "✅ Installed, but permissions had issues:\n${permResult.exceptionOrNull()?.message}"
            }

            toast(context, finalMsg, long = true)
            Result.success(finalMsg)

        } catch (e: Exception) {
            e.printStackTrace()
            toast(context, "❌ Install error: ${e.message}", long = true)
            Result.failure(e)
        } finally {
            try { stream?.close() } catch (_: Exception) {}
            try { manager?.close() } catch (_: Exception) {}
        }
    }

    // ─────────────────────────────────────────────
    // Grant permissions with verification
    // ─────────────────────────────────────────────

    private suspend fun grantPermissions(
        context: Context,
        manager: AbsAdbConnectionManager,
        packageName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        data class PermissionTask(
            val label: String,
            val command: String,
            val verifyCommand: String,
            val verifyContains: String
        )

        val tasks = listOf(
            PermissionTask(
                label = "WRITE_SECURE_SETTINGS",
                command = "pm grant $packageName android.permission.WRITE_SECURE_SETTINGS",
                verifyCommand = "dumpsys package $packageName | grep WRITE_SECURE_SETTINGS",
                verifyContains = "granted=true"
            ),
            PermissionTask(
                label = "PROJECT_MEDIA (appops)",
                command = "appops set $packageName PROJECT_MEDIA allow",
                verifyCommand = "appops get $packageName PROJECT_MEDIA",
                verifyContains = "allow"
            ),
            PermissionTask(
                label = "SYSTEM_ALERT_WINDOW",
                command = "appops set $packageName SYSTEM_ALERT_WINDOW allow",
                verifyCommand = "appops get $packageName SYSTEM_ALERT_WINDOW",
                verifyContains = "allow"
            ),
            PermissionTask(
                label = "CAPTURE_VIDEO_OUTPUT",
                command = "appops set $packageName CAPTURE_VIDEO_OUTPUT allow",
                verifyCommand = "appops get $packageName CAPTURE_VIDEO_OUTPUT",
                verifyContains = "allow"
            )
        )

        val granted = mutableListOf<String>()
        val failed = mutableListOf<String>()

        for (task in tasks) {
            try {
                // Run grant command
                val grantOutput = runShell(manager, task.command, 5000)

                // Verify by running a check command
                delay(300)
                val verifyOutput = runShell(manager, task.verifyCommand, 5000)

                val success = verifyOutput.contains(task.verifyContains, ignoreCase = true) ||
                        (grantOutput.isEmpty() && !grantOutput.contains("error", ignoreCase = true))

                if (success) {
                    granted.add("✅ ${task.label}")
                    toast(context, "✅ Granted: ${task.label}")
                } else {
                    val reason = grantOutput.ifEmpty { "Verification failed" }
                    failed.add("❌ ${task.label}: $reason")
                    toast(context, "⚠️ Failed: ${task.label} — $reason", long = true)
                }

            } catch (e: Exception) {
                failed.add("❌ ${task.label}: ${e.message}")
                toast(context, "⚠️ Error granting ${task.label}: ${e.message}", long = true)
            }

            delay(200) // Small gap between commands
        }

        // Final summary
        val summary = buildString {
            if (granted.isNotEmpty()) appendLine("Granted:\n${granted.joinToString("\n")}")
            if (failed.isNotEmpty()) appendLine("Failed:\n${failed.joinToString("\n")}")
        }.trim()

        return@withContext if (failed.isEmpty()) {
            Result.success(summary)
        } else {
            Result.failure(Exception(summary))
        }
    }
}
