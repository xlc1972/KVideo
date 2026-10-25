package com.kvideo.tv

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * APK 自更新下载器。
 *
 * 流程: 下载 -> SHA256 校验 -> 交给调用方安装
 * 回退: 任一步失败都删除临时文件并回调失败, 旧版本继续可用。
 */
class UpdateDownloader(private val callback: Callback) {

    interface Callback {
        /** 下载进度, [downloaded]/[total], total<=0 表示未知大小 */
        fun onProgress(downloaded: Long, total: Long)

        /** 下载并校验成功, [file] 是可安装的 APK */
        fun onSuccess(file: File)

        /** 失败, [retryable] 表示是否值得重试 */
        fun onFailure(reason: String, retryable: Boolean)
    }

    companion object {
        private const val TAG = "KVideoUpdate"
        private const val CONNECT_TIMEOUT_MS = 15000
        private const val READ_TIMEOUT_MS = 30000
        private const val MAX_REDIRECTS = 5
    }

    @Volatile
    private var cancelled = AtomicBoolean(false)

    fun cancel() {
        cancelled.set(true)
    }

    /**
     * 在后台线程执行下载。
     */
    fun start(downloadUrl: String, expectedSha256: String?, targetDir: File) {
        Thread {
            try {
                val result = download(downloadUrl, expectedSha256, targetDir)
                when (result) {
                    is Result.Success -> callback.onSuccess(result.file)
                    is Result.Failure -> callback.onFailure(result.reason, result.retryable)
                }
            } catch (e: Exception) {
                Log.e(TAG, "download crashed", e)
                callback.onFailure(e.message ?: "unknown error", true)
            }
        }.start()
    }

    private sealed class Result {
        data class Success(val file: File) : Result()
        data class Failure(val reason: String, val retryable: Boolean) : Result()
    }

    private fun download(url: String, expectedSha256: String?, targetDir: File): Result {
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            return Result.Failure("cannot create download dir", false)
        }

        val tmpFile = File(targetDir, "update-${System.currentTimeMillis()}.apk.part")
        var connection: HttpURLConnection? = null

        try {
            var currentUrl = url
            var redirects = 0

            while (true) {
                connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", "KVideo-AndroidTV-Updater")
                    setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream")
                    setRequestProperty("Accept-Encoding", "identity")
                }

                val code = connection.responseCode
                if (code == HttpURLConnection.HTTP_MOVED_PERM ||
                    code == HttpURLConnection.HTTP_MOVED_TEMP ||
                    code == HttpURLConnection.HTTP_SEE_OTHER ||
                    code == 307 || code == 308
                ) {
                    val location = connection.getHeaderField("Location")
                    if (location.isNullOrBlank() || redirects >= MAX_REDIRECTS) {
                        return Result.Failure("bad redirect (code=$code)", true)
                    }
                    redirects++
                    currentUrl = URL(URL(currentUrl), location).toString()
                    connection.disconnect()
                    connection = null
                    continue
                }

                if (code != HttpURLConnection.HTTP_OK) {
                    return Result.Failure("HTTP $code", code >= 500 || code == 429)
                }

                val total = connection.contentLengthLong
                var downloaded = 0L

                connection.inputStream.use { input: InputStream ->
                    FileOutputStream(tmpFile).use { out ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled.get()) {
                                return Result.Failure("cancelled", false)
                            }
                            val read = input.read(buffer)
                            if (read == -1) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            callback.onProgress(downloaded, total)
                        }
                        out.flush()
                        out.fd.sync()
                    }
                }

                if (total > 0 && downloaded != total) {
                    tmpFile.delete()
                    return Result.Failure("incomplete download ($downloaded/$total)", true)
                }

                // SHA256 校验 (服务端提供则必须一致)
                if (!expectedSha256.isNullOrBlank()) {
                    val actual = sha256(tmpFile)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        tmpFile.delete()
                        Log.e(TAG, "sha256 mismatch: expected=$expectedSha256 actual=$actual")
                        return Result.Failure("signature (sha256) mismatch", false)
                    }
                    Log.i(TAG, "sha256 verified: $actual")
                }

                val finalFile = File(targetDir, "update-${System.currentTimeMillis()}.apk")
                if (!tmpFile.renameTo(finalFile)) {
                    tmpFile.delete()
                    return Result.Failure("cannot finalize download", true)
                }
                return Result.Success(finalFile)
            }
        } catch (e: Exception) {
            tmpFile.delete()
            Log.e(TAG, "download failed", e)
            val msg = e.message ?: e.javaClass.simpleName
            val retryable = msg.contains("timeout", true) ||
                msg.contains("reset", true) ||
                msg.contains("refused", true) ||
                msg.contains("Unable to resolve host", true)
            return Result.Failure(msg, retryable)
        } finally {
            connection?.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
