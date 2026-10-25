package com.kvideo.tv

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * APK 自动更新协调器。
 *
 * 触发时机: 启动后延迟 + 每次回到前台 (onResume), 但同一版本只提示一次。
 *
 * 失败回退: 检查失败 / 下载失败 / 校验失败 一律不阻塞使用, 旧版本继续跑。
 */
class AppUpdater(private val activity: MainActivity) {

    companion object {
        private const val TAG = "KVideoUpdater"
        private const val PREFS_NAME = "kvideo_tv_settings"
        private const val PREF_LAST_SEEN_VERSION = "last_seen_update_version"
        private const val CHECK_DELAY_MS = 4000L
        private const val UPDATE_PATH = "/api/tv-update"
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var checking = false
    private var downloader: UpdateDownloader? = null

    /**
     * 启动后延迟检查 (给 WebView 一点时间先出内容, 避免和首屏渲染抢资源)。
     */
    fun scheduleStartupCheck(serverUrl: String) {
        if (serverUrl.isBlank()) return
        activity.window.decorView.postDelayed({
            checkForUpdate(serverUrl, silent = true)
        }, CHECK_DELAY_MS)
    }

    /**
     * 检查更新。
     *
     * @param silent true = 无更新时静默; 有更新时弹窗
     * @return 检查是否成功发起
     */
    fun checkForUpdate(serverUrl: String, silent: Boolean = false): Boolean {
        if (checking) return false
        val base = serverUrl.trimEnd('/')
        if (base.isBlank()) return false

        checking = true
        Thread {
            try {
                val info = fetchUpdateInfo(base)
                if (info == null) {
                    Log.i(TAG, "no update info from $base$UPDATE_PATH")
                    if (!silent) activity.toast(activity.getString(R.string.update_check_failed))
                } else if (info.versionCode <= currentVersionCode()) {
                    Log.i(TAG, "already up to date: ${info.version}")
                    if (!silent) activity.toast(activity.getString(R.string.update_already_latest))
                } else {
                    activity.runOnUiThread { promptUpdate(info) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "check failed", e)
                if (!silent) activity.toast(activity.getString(R.string.update_check_failed))
            } finally {
                checking = false
            }
        }.start()
        return true
    }

    private fun currentVersionCode(): Int {
        return try {
            activity.packageManager.getPackageInfo(activity.packageName, 0).let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) it.longVersionCode.toInt() else it.versionCode
            }
        } catch (e: Exception) {
            0
        }
    }

    private fun fetchUpdateInfo(base: String): UpdateInfo? {
        val conn = (URL(base + UPDATE_PATH).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 10000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "KVideo-AndroidTV-Updater")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "update endpoint HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return UpdateInfo.fromJson(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun promptUpdate(info: UpdateInfo) {
        val notes = if (info.notes.isNotBlank()) "\n\n${info.notes}" else ""
        val message = activity.getString(R.string.update_available, info.version) + notes
        val alreadyShown = prefs.getString(PREF_LAST_SEEN_VERSION, "") == info.version

        if (alreadyShown && !info.mandatory) {
            // 同一版本已提示过, 不重复打扰 (但仍提供手动检查入口)
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setCancelable(!info.mandatory)
            .setPositiveButton(R.string.update_now) { _, _ ->
                prefs.edit().putString(PREF_LAST_SEEN_VERSION, info.version).apply()
                startDownload(info)
            }
            .setNegativeButton(R.string.update_later) { _, _ -> }
            .show()
    }

    private fun startDownload(info: UpdateInfo) {
        val progressDialog = AlertDialog.Builder(activity)
            .setTitle(R.string.update_downloading)
            .setMessage(activity.getString(R.string.update_preparing))
            .setCancelable(false)
            .create()

        val downloadDir = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "updates")
        downloader = UpdateDownloader(object : UpdateDownloader.Callback {
            override fun onProgress(downloaded: Long, total: Long) {
                activity.runOnUiThread {
                    val msg = if (total > 0) {
                        activity.getString(R.string.update_progress, downloaded / 1024, total / 1024)
                    } else {
                        activity.getString(R.string.update_progress_unknown, downloaded / 1024)
                    }
                    progressDialog.setMessage(msg)
                }
            }

            override fun onSuccess(file: File) {
                activity.runOnUiThread {
                    progressDialog.setMessage(activity.getString(R.string.update_verifying))
                    progressDialog.dismiss()
                    installApk(file, info)
                }
            }

            override fun onFailure(reason: String, retryable: Boolean) {
                activity.runOnUiThread {
                    progressDialog.dismiss()
                    Log.e(TAG, "update failed: $reason (retryable=$retryable)")
                    activity.toast(activity.getString(R.string.update_failed, reason))
                }
            }
        })

        progressDialog.show()
        downloader?.start(info.url, info.sha256, downloadDir)
    }

    private fun installApk(file: File, info: UpdateInfo) {
        try {
            val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            } else {
                Uri.fromFile(file)
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
            activity.toast(activity.getString(R.string.update_install_started, info.version))
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            activity.toast(activity.getString(R.string.update_install_failed, e.message ?: ""))
        }
    }
}
