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
 * APK 自动更新协调器 —— 双源 + 完整性校验。
 *
 * 流程:
 *   1. [UpdateChecker] 依次查 182 `/api/tv-update` (JSON, 带 sha256) 和
 *      106 `version.txt` (纯数字), 取版本号最高的可用更新
 *   2. 与本机 versionCode 比对, 有新版则弹窗
 *   3. 下载 APK -> **校验 sha256** (服务端提供时必须一致) -> 安装
 *
 * 为什么双源: 生产上 APK 同时托管在 182 和 106, 家庭宽带下任一个被
 * 运营商策略挡住都可能导致更新失败, 任意一个可用即可完成更新。
 *
 * 失败回退: 检查失败 / 下载失败 / 校验失败 一律不阻塞使用, 旧版本继续跑。
 */
class AppUpdater(private val activity: MainActivity) {
    private val checker = UpdateChecker()


    companion object {
        private const val TAG = "KVideoUpdater"
        private const val PREFS_NAME = "kvideo_tv_settings"
        private const val PREF_LAST_SEEN_VERSION = "last_seen_update_version"
        private const val CHECK_DELAY_MS = 4000L

        /** APK 托管目录 (与网站分离, 避免 build 风险) */
        const val APK_BASE = "http://106.15.153.201/ziqidonglai"
        const val VERSION_FILE = "$APK_BASE/version.txt"
        const val APK_FILE = "$APK_BASE/latest.apk"

        /** 182 的更新清单接口 (JSON, 带 sha256) —— 首选源 */
        const val UPDATE_API = "https://kvideo.djjk.cc/api/tv-update"
    }

    private val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var checking = false
    private var downloader: UpdateDownloader? = null

    /**
     * 启动后延迟检查 (给 WebView 一点时间先出内容, 避免和首屏渲染抢资源)。
     */
    fun scheduleStartupCheck() {
        activity.window.decorView.postDelayed({
            checkForUpdate(silent = true)
        }, CHECK_DELAY_MS)
    }

    /**
     * 检查更新。
     *
     * @param silent true = 无更新时静默; 有更新时弹窗
     * @return 检查是否成功发起
     */
    fun checkForUpdate(silent: Boolean = false): Boolean {
        if (checking) return false
        checking = true
        Thread {
            try {
                when (val result = checker.check(currentVersionCode(), UpdateChecker.HTTP_FETCHER)) {
                    is UpdateChecker.Result.UpdateAvailable -> {
                        Log.i(TAG, "update available: ${result.manifest.versionName} " +
                            "(code=${result.manifest.versionCode}, from=${result.sourceName}, " +
                            "sha256=${if (result.manifest.hasChecksum) "yes" else "no"})")
                        activity.runOnUiThread { promptUpdate(result.manifest) }
                    }
                    is UpdateChecker.Result.UpToDate -> {
                        Log.i(TAG, "already up to date")
                        if (!silent) activity.toast(activity.getString(R.string.update_already_latest))
                    }
                    is UpdateChecker.Result.Unavailable -> {
                        Log.w(TAG, "update sources unavailable: ${result.reasons}")
                        if (!silent) activity.toast(activity.getString(R.string.update_check_failed))
                    }
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

    private fun promptUpdate(info: UpdateManifest) {
        val message = activity.getString(R.string.update_available, info.versionName)
        val alreadyShown = prefs.getInt(PREF_LAST_SEEN_VERSION, 0) == info.versionCode
        if (alreadyShown) {
            // 同一版本已提示过, 不重复打扰
            return
        }

        AlertDialog.Builder(activity)
            .setTitle(R.string.update_title)
            .setMessage(message)
            .setCancelable(true)
            .setPositiveButton(R.string.update_now) { _, _ ->
                prefs.edit().putInt(PREF_LAST_SEEN_VERSION, info.versionCode).apply()
                startDownload(info)
            }
            .setNegativeButton(R.string.update_later) { _, _ -> }
            .show()
    }

    private fun startDownload(info: UpdateManifest) {
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
        // sha256 为空时传 null —— UpdateDownloader 会跳过校验但仍走系统签名校验
        downloader?.start(info.url, info.sha256.ifEmpty { null }, downloadDir)
    }

    private fun installApk(file: File, info: UpdateManifest) {
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
            activity.toast(activity.getString(R.string.update_install_started, info.versionName))
        } catch (e: Exception) {
            Log.e(TAG, "install failed", e)
            activity.toast(activity.getString(R.string.update_install_failed, e.message ?: ""))
        }
    }
}
