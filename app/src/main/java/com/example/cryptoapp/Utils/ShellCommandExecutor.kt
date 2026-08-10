package com.example.cryptoapp.Utils

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class ShellCommandResult(
    val exitCode: Int,
    val standardOutput: String,
    val errorOutput: String,
    val timedOut: Boolean,
    val durationMillis: Long
)

/** 命令执行身份。Shizuku 通常是 shell(uid 2000)，使用 Sui 启动时可能是 root。 */
enum class ShellExecutionMode(val displayName: String) {
    APP("应用沙箱"),
    ROOT("Root"),
    SHIZUKU("Shizuku")
}

/**
 * 安全地执行单条 Android Shell 命令。
 * 标准输出与错误输出并行读取，避免缓冲区写满导致死锁；命令超过时限会被强制终止。
 */
object ShellCommandExecutor {
    fun execute(
        context: android.content.Context,
        command: String,
        mode: ShellExecutionMode,
        timeoutSeconds: Long = 20
    ): ShellCommandResult {
        require(command.isNotBlank()) { "命令不能为空" }
        if (mode == ShellExecutionMode.SHIZUKU) {
            return ShizukuShellClient.execute(context.applicationContext, command, timeoutSeconds)
        }
        val startedAt = System.currentTimeMillis()
        val arguments = if (mode == ShellExecutionMode.ROOT) listOf("su", "-c", command)
        else listOf("/system/bin/sh", "-c", command)
        val process = ProcessBuilder(arguments).start()
        val readers = Executors.newFixedThreadPool(2)
        return try {
            val output = readers.submit<String> {
                process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val error = readers.submit<String> {
                process.errorStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) process.destroyForcibly()
            ShellCommandResult(
                exitCode = if (completed) process.exitValue() else -1,
                standardOutput = output.get(2, TimeUnit.SECONDS).trimEnd(),
                errorOutput = error.get(2, TimeUnit.SECONDS).trimEnd(),
                timedOut = !completed,
                durationMillis = System.currentTimeMillis() - startedAt
            )
        } catch (interrupted: InterruptedException) {
            process.destroyForcibly()
            Thread.currentThread().interrupt()
            throw interrupted
        } finally {
            readers.shutdownNow()
            process.destroy()
        }
    }
}
