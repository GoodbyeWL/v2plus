package com.v2plus.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.v2plus.app.AppConfig
import com.v2plus.app.BuildConfig
import com.v2plus.app.R
import com.v2plus.app.databinding.ActivityCheckUpdateBinding
import com.v2plus.app.dto.CheckUpdateResult
import com.v2plus.app.extension.toast
import com.v2plus.app.extension.toastError
import com.v2plus.app.extension.toastSuccess
import com.v2plus.app.handler.MmkvManager
import com.v2plus.app.handler.UpdateCheckerManager
import com.v2plus.app.handler.V2RayNativeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

class CheckUpdateActivity : BaseActivity() {

    private val binding by lazy { ActivityCheckUpdateBinding.inflate(layoutInflater) }
    private var updateDownloadJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //setContentView(binding.root)
        setContentViewWithToolbar(binding.root, showHomeAsUp = true, title = getString(R.string.update_check_for_update))

        if (!BuildConfig.IN_APP_UPDATE_ENABLED) {
            toastError(R.string.update_disabled_for_rustore)
            finish()
            return
        }

        binding.layoutCheckUpdate.setOnClickListener {
            checkForUpdates()
        }

        "v${BuildConfig.VERSION_NAME} (${V2RayNativeManager.getLibVersion()})".also {
            binding.tvVersion.text = it
        }

        checkForUpdates()
    }

    private fun checkForUpdates() {
        toast(R.string.update_checking_for_update)
        showLoading()

        lifecycleScope.launch {
            try {
                val result = UpdateCheckerManager.checkForUpdate(false)
                if (result.hasUpdate) {
                    showUpdateDialog(result)
                } else {
                    toastSuccess(R.string.update_already_latest_version)
                }
            } catch (e: Exception) {
                Log.e(AppConfig.TAG, "Failed to check for updates: ${e.message}")
                toastError(e.message ?: getString(R.string.toast_failure))
            }
            finally {
                hideLoading()
            }
        }
    }

    private fun showUpdateDialog(result: CheckUpdateResult) {
        val latestVersion = result.latestVersion ?: return
        val message = getString(
            R.string.update_modal_message,
            BuildConfig.VERSION_NAME,
            latestVersion
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_new_version_found, latestVersion))
            .setMessage(message)
            .setPositiveButton(R.string.update_now) { _, _ ->
                startUpdateDownload(result.downloadUrl.orEmpty(), latestVersion)
            }
            .setNegativeButton(R.string.update_skip, null)
            .show()
    }

    private fun startUpdateDownload(downloadUrl: String, latestVersion: String) {
        if (downloadUrl.isBlank()) return
        updateDownloadJob?.cancel()

        MmkvManager.savePendingUpdate(downloadUrl, latestVersion)

        val content = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, (8 * resources.displayMetrics.density).toInt(), pad, 0)
        }

        val progressBar = android.widget.ProgressBar(
            this,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = 100
            progress = 0
        }
        val tvProgress = android.widget.TextView(this)
        val tvSize = android.widget.TextView(this)
        val tvSpeed = android.widget.TextView(this)
        val tvEta = android.widget.TextView(this)
        tvProgress.text = getString(R.string.update_download_progress, 0)
        tvSize.text = getString(R.string.update_download_size, "0 B", getString(R.string.update_size_unknown))
        tvSpeed.text = getString(R.string.update_download_speed, "0 B")
        tvEta.text = getString(R.string.update_download_eta, getString(R.string.update_eta_unknown))

        content.addView(progressBar)
        content.addView(tvProgress)
        content.addView(tvSize)
        content.addView(tvSpeed)
        content.addView(tvEta)

        var skipped = false
        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_downloading_title, latestVersion))
            .setView(content)
            .setNegativeButton(R.string.update_skip) { _, _ ->
                skipped = true
                updateDownloadJob?.cancel()
            }
            .setCancelable(false)
            .create()
        dialog.show()

        val wakeLock = (getSystemService(android.content.Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "v2plus:apk_download")
            ?.apply { acquire(10 * 60 * 1000L) }

        updateDownloadJob = lifecycleScope.launch {
            var downloadErrorMessage: String? = null
            val apkFile = withContext(Dispatchers.IO) {
                UpdateCheckerManager.downloadApk(
                    context = this@CheckUpdateActivity,
                    downloadUrl = downloadUrl,
                    onProgress = { progress ->
                        runOnUiThread {
                            progressBar.progress = progress.percent
                            tvProgress.text = getString(R.string.update_download_progress, progress.percent)
                            val totalText = if (progress.totalBytes > 0) {
                                formatBytes(progress.totalBytes)
                            } else {
                                getString(R.string.update_size_unknown)
                            }
                            tvSize.text = getString(
                                R.string.update_download_size,
                                formatBytes(progress.downloadedBytes),
                                totalText
                            )
                            tvSpeed.text = getString(
                                R.string.update_download_speed,
                                formatBytes(progress.speedBytesPerSec)
                            )
                            val etaText = if (progress.etaSeconds >= 0) {
                                formatEta(progress.etaSeconds)
                            } else {
                                getString(R.string.update_eta_unknown)
                            }
                            tvEta.text = getString(R.string.update_download_eta, etaText)
                        }
                    },
                    onError = { err ->
                        downloadErrorMessage = err
                    }
                )
            }

            wakeLock?.let { if (it.isHeld) it.release() }

            if (dialog.isShowing) {
                dialog.dismiss()
            }
            if (apkFile != null) {
                installDownloadedApk(apkFile)
            } else if (!skipped) {
                val message = downloadErrorMessage?.takeIf { it.isNotBlank() }?.let {
                    "${getString(R.string.update_download_failed)}: $it"
                } ?: getString(R.string.update_download_failed)
                toastError(message)
            }
        }
    }

    private fun installDownloadedApk(apkFile: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
                val permissionIntent = Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(permissionIntent)
                toastError(R.string.update_install_permission_required)
                return
            }
            MmkvManager.clearPendingUpdate()
            val uri = FileProvider.getUriForFile(
                this,
                BuildConfig.APPLICATION_ID + ".cache",
                apkFile
            )
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
                data = uri
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
                putExtra(Intent.EXTRA_RETURN_RESULT, true)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toastError(e.message ?: getString(R.string.update_download_failed))
        }
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024 && index < units.lastIndex) {
            value /= 1024.0
            index++
        }
        return String.format(Locale.US, "%.1f %s", value, units[index])
    }

    private fun formatEta(seconds: Long): String {
        val safe = seconds.coerceAtLeast(0L)
        val mins = safe / 60
        val secs = safe % 60
        return if (mins > 0) {
            String.format(Locale.US, "%dm %02ds", mins, secs)
        } else {
            String.format(Locale.US, "%ds", secs)
        }
    }

    override fun onDestroy() {
        updateDownloadJob?.cancel()
        super.onDestroy()
    }
}
