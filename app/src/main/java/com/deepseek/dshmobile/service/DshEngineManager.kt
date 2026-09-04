package com.deepseek.dshmobile.service

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class DshEngineManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DshEngineManager"
        private const val PORT = 3080
        private const val HOST = "127.0.0.1"

        val instance: DshEngineManager by lazy {
            DshEngineManager(ApplicationProvider.context)
        }

        var isRunning: Boolean = false
            private set
    }

    private var process: Process? = null

    /**
     * 初始化并启动本地 dsh 引擎
     * 检查 assets 中的 Node.js 和 dsh CLI 是否可用，否则从 assets 解压
     */
    suspend fun initialize(context: Context): Boolean {
        if (isRunning) return true

        val appDir = File(context.filesDir, "dsh_engine")
        val nodeBin = File(appDir, "node").absolutePath
        val dshCli = File(appDir, "dsh").absolutePath

        // 检查二进制文件是否存在
        if (!File(nodeBin).exists() || !File(dshCli).exists()) {
            Log.i(TAG, "Engine binaries not found, extracting from assets...")
            if (!extractFromAssets(appDir)) {
                Log.e(TAG, "Failed to extract engine binaries")
                return false
            }
        }

        return try {
            // 启动 dsh web 服务
            val pb = ProcessBuilder(nodeBin, dshCli, "web", "--port", PORT.toString(), "--host", HOST)
                .redirectErrorStream(true)
                .directory(appDir)
                .start()

            process = pb
            Log.i(TAG, "Engine process started: PID=${pb.pid()}")

            // 等待服务就绪
            waitForReady()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start engine", e)
            false
        }
    }

    /**
     * 从 assets 解压引擎文件
     * 支持两种格式：压缩包或直接文件
     */
    private fun extractFromAssets(targetDir: File): Boolean {
        return try {
            targetDir.mkdirs()

            // 尝试从 assets/engine/ 解压
            context.assets.list("engine")?.forEach { fileName ->
                val targetFile = File(targetDir, fileName)
                if (fileName.endsWith(".gz") || fileName.endsWith(".tar")) {
                    // 处理压缩包（简化：直接复制）
                    context.assets.open("engine/$fileName").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    // 直接复制二进制文件
                    context.assets.open("engine/$fileName").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    // 设置可执行权限
                    targetFile.setExecutable(true)
                }
            }
            Log.i(TAG, "Engine extracted successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract engine", e)
            false
        }
    }

    /**
     * 等待 dsh web 服务就绪
     */
    private suspend fun waitForReady() {
        val timeoutMs = 30_000L
        val start = System.currentTimeMillis()

        while (System.currentTimeMillis() - start < timeoutMs) {
            try {
                val conn = URL("http://$HOST:$PORT").openConnection() as HttpURLConnection
                conn.connectTimeout = 500
                if (conn.responseCode == 200) {
                    Log.i(TAG, "Engine service is ready")
                    isRunning = true
                    return
                }
            } catch (_: Exception) {
                // 服务尚未就绪，继续等待
            }
            kotlinx.coroutines.delay(300)
        }

        throw RuntimeException("Engine startup timeout after ${timeoutMs}ms")
    }

    /**
     * 停止引擎服务
     */
    fun stop() {
        process?.let {
            it.destroy()
            Log.i(TAG, "Engine process destroyed: PID=${it.pid()}")
        }
        process = null
        isRunning = false
    }

    /**
     * 检查引擎是否正在运行
     */
    fun ping(): Boolean {
        return try {
            val conn = URL("http://$HOST:$PORT").openConnection() as HttpURLConnection
            conn.connectTimeout = 1000
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 发送消息到引擎
     */
    suspend fun sendMessage(content: String, sessionId: String? = null): String {
        return withContext(java.util.concurrent.Executors.newSingleThreadExecutor()) {
            try {
                val url = URL("http://$HOST:$PORT/api/chat")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000

                val body = org.json.JSONObject().apply {
                    put("content", content)
                    put("sessionId", sessionId ?: org.json.JSONObject.NULL)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }

                val responseCode = conn.responseCode
                if (responseCode == 200) {
                    conn.inputStream.bufferedReader().readText()
                } else {
                    throw RuntimeException("HTTP $responseCode")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message", e)
                throw e
            }
        }
    }
}

// Application context provider for lazy initialization
private object ApplicationProvider {
    var context: Context? = null
}
