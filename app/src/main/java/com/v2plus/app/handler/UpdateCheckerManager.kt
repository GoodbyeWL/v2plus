package com.v2plus.app.handler

import android.content.Context
import android.os.Build
import android.util.Log
import com.v2plus.app.AppConfig
import com.v2plus.app.BuildConfig
import com.v2plus.app.dto.CheckUpdateResult
import com.v2plus.app.dto.GitHubRelease
import com.v2plus.app.util.HttpUtil
import com.v2plus.app.util.JsonUtil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

object UpdateCheckerManager {
    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val percent: Int,
        val speedBytesPerSec: Long,
        val etaSeconds: Long
    )

    suspend fun checkForUpdate(): CheckUpdateResult = withContext(Dispatchers.IO) {
        val timeoutMs = 15000
        val httpPort = SettingsManager.getHttpPort()
        val json = fetchGithubLatestReleaseJson(timeoutMs, 0)
            ?: fetchGithubLatestReleaseJson(timeoutMs, httpPort)
            ?: throw IllegalStateException(
                "GitHub Releases unavailable (network, DNS, or github.com blocked)"
            )

        val release = JsonUtil.fromJson(json, GitHubRelease::class.java)
            ?: throw IllegalStateException("Invalid GitHub release JSON")
        val latestVersion = release.tagName.trim().removePrefix("v")
        if (latestVersion.isEmpty()) {
            throw IllegalStateException("Latest GitHub release has no tag")
        }
        val downloadUrl = pickApkUrl(release)
            ?: throw IllegalStateException("Latest GitHub release has no APK asset")

        Log.i(
            AppConfig.TAG,
            "GitHub latest: $latestVersion (current: ${BuildConfig.VERSION_NAME})"
        )

        return@withContext if (compareVersions(latestVersion, BuildConfig.VERSION_NAME) > 0) {
            CheckUpdateResult(
                hasUpdate = true,
                latestVersion = latestVersion,
                releaseNotes = release.body,
                downloadUrl = downloadUrl,
                isPreRelease = release.prerelease
            )
        } else {
            CheckUpdateResult(hasUpdate = false)
        }
    }

    private fun fetchGithubLatestReleaseJson(timeoutMs: Int, httpPort: Int): String? {
        val conn = HttpUtil.createProxyConnection(
            AppConfig.GITHUB_LATEST_RELEASE_API,
            httpPort,
            timeoutMs,
            timeoutMs
        ) ?: return null
        try {
            conn.setRequestProperty("User-Agent", "v2plus/${BuildConfig.VERSION_NAME}")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(AppConfig.TAG, "GitHub release API HTTP $code (port=$httpPort)")
                return null
            }
            return conn.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "GitHub release API failed (port=$httpPort): ${e.message}")
            return null
        } finally {
            try {
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun pickApkUrl(release: GitHubRelease): String? {
        val apks = release.assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
        if (apks.isEmpty()) return null
        apks.firstOrNull { it.name.contains("universal", ignoreCase = true) }?.let {
            return it.browserDownloadUrl
        }
        val abi = Build.SUPPORTED_ABIS.firstOrNull()
        if (!abi.isNullOrBlank()) {
            apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }?.let {
                return it.browserDownloadUrl
            }
        }
        return apks.first().browserDownloadUrl
    }

    suspend fun downloadApk(
        context: Context,
        downloadUrl: String,
        onProgress: (DownloadProgress) -> Unit = {},
        onError: (String) -> Unit = {}
    ): File? = withContext(Dispatchers.IO) {
        val httpPort = SettingsManager.getHttpPort()
        val portsToTry = listOf(httpPort, 0).distinct()
        var lastError = "Download failed"

        for (port in portsToTry) {
            val connection: HttpURLConnection = try {
                if (port == 0) {
                    (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000
                        readTimeout = 10000
                        instanceFollowRedirects = true
                    }
                } else {
                    HttpUtil.createProxyConnection(downloadUrl, port, 10000, 10000, true)
                        ?: throw IllegalStateException("Failed to create proxy connection on port $port")
                }
            } catch (e: Exception) {
                lastError = e.message ?: "Failed to create connection"
                continue
            }

            try {
                connection.connect()
                if (connection.responseCode !in 200..299) {
                    val responseMessage = connection.responseMessage?.takeIf { it.isNotBlank() } ?: "Error"
                    lastError = "HTTP ${connection.responseCode}: $responseMessage"
                    continue
                }
                val apkFile = File(context.cacheDir, "update.apk")
                Log.i(AppConfig.TAG, "Downloading APK to: ${apkFile.absolutePath}")
                val totalBytes = connection.contentLengthLong.takeIf { it > 0 } ?: -1L
                val startTs = System.currentTimeMillis()
                var lastEmitTs = startTs
                var downloadedBytes = 0L

                FileOutputStream(apkFile).use { outputStream ->
                    connection.inputStream.use { inputStream ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = inputStream.read(buffer)
                            if (read <= 0) break
                            outputStream.write(buffer, 0, read)
                            downloadedBytes += read

                            val now = System.currentTimeMillis()
                            val shouldEmit = now - lastEmitTs >= 300
                            if (shouldEmit) {
                                val elapsedSec = ((now - startTs).coerceAtLeast(1)) / 1000.0
                                val speed = (downloadedBytes / elapsedSec).toLong().coerceAtLeast(1L)
                                val percent = if (totalBytes > 0) {
                                    ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
                                } else {
                                    0
                                }
                                val etaSeconds = if (totalBytes > 0) {
                                    ((totalBytes - downloadedBytes).coerceAtLeast(0L) / speed).coerceAtLeast(0L)
                                } else {
                                    -1L
                                }
                                onProgress(
                                    DownloadProgress(
                                        downloadedBytes = downloadedBytes,
                                        totalBytes = totalBytes,
                                        percent = percent,
                                        speedBytesPerSec = speed,
                                        etaSeconds = etaSeconds
                                    )
                                )
                                lastEmitTs = now
                            }
                        }
                    }
                }
                val finishTs = System.currentTimeMillis()
                val totalElapsedSec = ((finishTs - startTs).coerceAtLeast(1)) / 1000.0
                val finalSpeed = (downloadedBytes / totalElapsedSec).toLong().coerceAtLeast(1L)
                onProgress(
                    DownloadProgress(
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        percent = 100,
                        speedBytesPerSec = finalSpeed,
                        etaSeconds = 0L
                    )
                )
                Log.i(AppConfig.TAG, "APK download completed")
                return@withContext apkFile
            } catch (_: CancellationException) {
                val apkFile = File(context.cacheDir, "update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                return@withContext null
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to download APK: ${e.message}")
                lastError = e.message ?: "Download stream failed"
                val apkFile = File(context.cacheDir, "update.apk")
                if (apkFile.exists()) {
                    apkFile.delete()
                }
            } finally {
                try {
                    connection.disconnect()
                } catch (e: Exception) {
                    Log.e(AppConfig.TAG, "Error closing connection: ${e.message}")
                }
            }
        }
        onError(lastError)
        return@withContext null
    }

    private fun compareVersions(version1: String, version2: String): Int {
        val v1 = version1.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val v2 = version2.split(".").map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(v1.size, v2.size)) {
            val num1 = if (i < v1.size) v1[i] else 0
            val num2 = if (i < v2.size) v2[i] else 0
            if (num1 != num2) return num1 - num2
        }
        return 0
    }
}
