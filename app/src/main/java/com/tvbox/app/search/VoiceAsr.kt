package com.tvbox.app.search

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.tvbox.app.core.AppError
import com.tvbox.app.core.ErrorCodes
import com.tvbox.app.core.Result
import com.tvbox.app.core.TvLog
import com.tvbox.app.data.Http
import com.tvbox.app.ui.settings.AppSettingsValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * 语音搜索 P1（docs/SEARCH_SPEC.md §1）：
 * 按住 OK 录 16k/16bit 单声道 PCM（AudioRecord），松手封装 WAV，上传到用户自建的
 * OpenAI 兼容 /audio/transcriptions endpoint；不常驻、不后台上传、只申请 RECORD_AUDIO。
 *
 * 讯飞 RTASR 的实时签名协议不在本轮实现范围（设置页只提供 OpenAI 兼容入口）。
 */
class VoiceAsr(private val context: Context) {

    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    @Volatile
    private var recording = false
    private val pcm = ByteArrayOutputStream()
    private var startedAt = 0L

    @SuppressLint("NewApi")
    fun hasPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun isRecording(): Boolean = recording

    @SuppressLint("MissingPermission", "NewApi")
    fun start(): Boolean {
        if (recording) return true
        if (!hasPermission()) return false
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            TvLog.e(TAG, "AudioRecord minBuffer=$minBuffer，设备不支持 16k 录音")
            return false
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                minBuffer * 2,
            )
        } catch (t: Throwable) {
            TvLog.e(TAG, "创建 AudioRecord 失败", t)
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        synchronized(pcm) { pcm.reset() }
        recording = true
        startedAt = System.currentTimeMillis()
        recorder = record
        record.startRecording()
        captureThread = Thread({
            val buffer = ByteArray(minBuffer)
            while (recording) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    synchronized(pcm) { pcm.write(buffer, 0, read) }
                }
            }
        }, "voice-asr-capture").apply {
            priority = Thread.NORM_PRIORITY - 1
            start()
        }
        return true
    }

    /** 停止录音并返回 WAV（可能为空）。 */
    fun stop(): ByteArray? {
        if (!recording) return null
        recording = false
        runCatching { captureThread?.join(400) }
        runCatching {
            recorder?.stop()
        }
        runCatching {
            recorder?.release()
        }
        recorder = null
        captureThread = null
        val durationMs = System.currentTimeMillis() - startedAt
        val data = synchronized(pcm) { pcm.toByteArray() }
        if (data.isEmpty() || durationMs < 250L) {
            TvLog.w(TAG, "录音过短：${durationMs}ms, bytes=${data.size}")
            return null
        }
        TvLog.i(TAG, "录音完成：${durationMs}ms, pcm=${data.size}B")
        return wrapWav(data)
    }

    fun cancel() {
        recording = false
        runCatching { captureThread?.join(300) }
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        captureThread = null
    }

    suspend fun transcribe(
        wav: ByteArray,
        settings: AppSettingsValues,
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!settings.asrConfigured) {
            return@withContext Result.failure(ErrorCodes.SOURCE_INVALID, "未配置 ASR endpoint/key")
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", "voice.wav", wav.toRequestBody("audio/wav".toMediaType()))
            .addFormDataPart("model", settings.asrModel.ifBlank { "whisper-1" })
            .addFormDataPart("language", "zh")
            .build()
        val request = Request.Builder()
            .url(settings.asrEndpoint)
            .header("Authorization", "Bearer ${settings.asrKey}")
            .post(body)
            .build()
        try {
            Http.client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        ErrorCodes.HTTP,
                        "ASR 服务返回 ${response.code}: ${text.take(120)}",
                    )
                }
                val json = JSONObject(text)
                val result = json.optString("text").ifBlank {
                    json.optString("result").ifBlank {
                        json.optJSONObject("data")?.optString("text").orEmpty()
                    }
                }.trim()
                if (result.isEmpty()) {
                    Result.failure(ErrorCodes.PARSE, "ASR 未返回文本：${text.take(120)}")
                } else {
                    Result.Ok(result)
                }
            }
        } catch (t: Throwable) {
            TvLog.w(TAG, "ASR 上传失败", t)
            Result.Err(AppError(if (t is IOException) ErrorCodes.NETWORK else ErrorCodes.UNKNOWN, "ASR 上传失败：${t.message}", t))
        }
    }

    private fun wrapWav(pcm: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val channels = 1
        val bits = 16
        val byteRate = sampleRate * channels * bits / 8
        val dataSize = pcm.size
        val out = ByteArrayOutputStream(44 + dataSize)
        fun writeIntLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            out.write((value shr 16) and 0xFF)
            out.write((value shr 24) and 0xFF)
        }
        fun writeShortLE(value: Int) {
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        writeIntLE(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(channels)
        writeIntLE(sampleRate)
        writeIntLE(byteRate)
        writeShortLE(channels * bits / 8)
        writeShortLE(bits)
        out.write("data".toByteArray())
        writeIntLE(dataSize)
        out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "VoiceAsr"
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
