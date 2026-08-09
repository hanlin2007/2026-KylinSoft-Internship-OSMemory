package com.example.osmemory.core.model

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** 下载、校验并保存端侧 GGUF；模型位于 noBackupFilesDir，不进入 APK 或 Git。 */
object LocalModelStore {
    data class Progress(val downloaded: Long, val total: Long)

    data class Status(
        val ready: Boolean,
        val message: String,
        val bytes: Long = 0L
    )

    fun modelFile(context: Context): File =
        File(File(context.applicationContext.noBackupFilesDir, "models"), LocalModelSpec.FILE_NAME)

    fun readyFile(context: Context): File? {
        val file = modelFile(context)
        if (!file.isFile || file.length() != LocalModelSpec.EXPECTED_SIZE) return null
        val prefs = prefs(context)
        return file.takeIf {
            prefs.getBoolean(KEY_VERIFIED, false) &&
                prefs.getLong(KEY_SIZE, -1L) == file.length() &&
                prefs.getString(KEY_SHA256, "") == LocalModelSpec.SHA256
        }
    }

    fun status(context: Context): Status {
        val file = modelFile(context)
        val ready = readyFile(context) != null
        return when {
            ready -> Status(true, "模型已就绪 · ${formatBytes(file.length())}", file.length())
            file.exists() -> Status(false, "模型文件未通过校验，请重新准备", file.length())
            else -> Status(false, "模型尚未下载 · 约 469 MB")
        }
    }

    suspend fun ensureReady(
        context: Context,
        onProgress: (Progress) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        readyFile(context) ?: synchronizedDownload(context.applicationContext, onProgress)
    }

    fun delete(context: Context) {
        modelFile(context).delete()
        File(modelFile(context).parentFile, "${LocalModelSpec.FILE_NAME}.part").delete()
        prefs(context).edit().clear().apply()
    }

    private suspend fun synchronizedDownload(
        context: Context,
        onProgress: (Progress) -> Unit
    ): File = synchronized(DOWNLOAD_LOCK) {
        readyFile(context) ?: run {
            val target = modelFile(context)
            target.parentFile?.mkdirs()

            if (target.isFile && target.length() == LocalModelSpec.EXPECTED_SIZE) {
                val hash = sha256(target)
                if (hash == LocalModelSpec.SHA256) {
                    markVerified(context, target)
                    return@synchronized target
                }
                target.delete()
            }

            val part = File(target.parentFile, "${LocalModelSpec.FILE_NAME}.part")
            part.delete()
            // huggingface.co 国内可能不可达：失败自动切 hf-mirror.com 镜像重试（SHA-256 校验一致）
            val response = openDownload(LocalModelSpec.DOWNLOAD_URL)
                ?: openDownload(LocalModelSpec.DOWNLOAD_URL.replace(
                    "https://huggingface.co/", "https://hf-mirror.com/"
                )) ?: throw ModelException("端侧模型下载失败（官方与镜像均不可达）")

            val digest = MessageDigest.getInstance("SHA-256")
            val declaredTotal = response.body?.contentLength()?.takeIf { it > 0L }
                ?: LocalModelSpec.EXPECTED_SIZE
            var downloaded = 0L
            var nextReport = 0L
            try {
                val body = response.body ?: throw ModelException("端侧模型下载响应为空")
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            digest.update(buffer, 0, count)
                            downloaded += count
                            if (downloaded >= nextReport) {
                                onProgress(Progress(downloaded, declaredTotal))
                                nextReport = downloaded + PROGRESS_STEP
                            }
                        }
                        output.fd.sync()
                    }
                }
            } catch (error: Throwable) {
                part.delete()
                throw error
            } finally {
                response.close()
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            if (downloaded != LocalModelSpec.EXPECTED_SIZE || hash != LocalModelSpec.SHA256) {
                part.delete()
                throw ModelException(
                    "端侧模型校验失败：大小或 SHA-256 与固定版本不一致"
                )
            }
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
            }
            markVerified(context, target)
            onProgress(Progress(target.length(), target.length()))
            target
        }
    }

    /** 打开下载响应；网络异常或非 2xx 返回 null（调用方切换镜像重试） */
    private fun openDownload(url: String): okhttp3.Response? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "OSMemory-Android/1.0")
                .build()
            val response = CLIENT.newCall(request).execute()
            if (!response.isSuccessful) {
                response.close()
                null
            } else response
        } catch (_: Exception) {
            null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 4)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun markVerified(context: Context, file: File) {
        prefs(context).edit()
            .putBoolean(KEY_VERIFIED, true)
            .putLong(KEY_SIZE, file.length())
            .putString(KEY_SHA256, LocalModelSpec.SHA256)
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private fun formatBytes(bytes: Long): String = "%.1f MB".format(bytes / 1024.0 / 1024.0)

    private const val PREFS_NAME = "local_model_store"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_SIZE = "size"
    private const val KEY_SHA256 = "sha256"
    private const val PROGRESS_STEP = 1024L * 1024L
    private val DOWNLOAD_LOCK = Any()
    private val CLIENT = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
}
