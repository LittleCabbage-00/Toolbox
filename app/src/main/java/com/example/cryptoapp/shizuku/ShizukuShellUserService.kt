package com.example.cryptoapp.shizuku

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * 由 Shizuku 启动的 UserService。该类运行在 shell（ADB Shizuku）或 root（Sui）身份下，
 * 不持有 Activity/应用 Context，也不调用依赖普通应用进程的 Android API。
 */
class ShizukuShellUserService : IShizukuShellService.Stub {
    constructor() : super()
    constructor(context: Context?) : super()

    override fun execute(command: String?, timeoutSeconds: Int): Array<String>? {
        val startedAt = System.currentTimeMillis()
        var process: Process? = null
        val readers: ExecutorService = Executors.newFixedThreadPool(2)
        try {
            process = ProcessBuilder("/system/bin/sh", "-c", command ?: "").start()
            val runningProcess = process
            val output: Future<String> = readers.submit<String> { readText(runningProcess.inputStream) }
            val error: Future<String> = readers.submit<String> { readText(runningProcess.errorStream) }
            val completed = process.waitFor(maxOf(1, timeoutSeconds).toLong(), TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            return arrayOf(
                (if (completed) process.exitValue() else -1).toString(),
                output.get(2, TimeUnit.SECONDS).trim(),
                error.get(2, TimeUnit.SECONDS).trim(),
                (!completed).toString(),
                (System.currentTimeMillis() - startedAt).toString()
            )
        } catch (error: Exception) {
            if (error is InterruptedException) Thread.currentThread().interrupt()
            return arrayOf(
                "-1", "", error.message ?: error.javaClass.simpleName,
                "false", (System.currentTimeMillis() - startedAt).toString()
            )
        } finally {
            readers.shutdownNow()
            if (process != null) process.destroy()
        }
    }

    override fun destroy() {
        System.exit(0)
    }

    private companion object {
        /** InputStream.readAllBytes() 需要 API 33；手动读取可兼容本项目最低 API 26。 */
        private fun readText(input: InputStream): String {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var length: Int
            while (input.read(buffer).also { length = it } != -1) output.write(buffer, 0, length)
            return output.toString(StandardCharsets.UTF_8.name())
        }
    }
}
