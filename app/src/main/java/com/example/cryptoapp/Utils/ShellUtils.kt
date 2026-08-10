package com.example.cryptoapp.Utils

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

/** shell 命令执行工具，支持 root/sh 两种身份，返回退出码与输出。 */
object ShellUtils {
    const val COMMAND_SU = "su"
    const val COMMAND_SH = "sh"
    const val COMMAND_EXIT = "exit\n"
    const val COMMAND_LINE_END = "\n"

    /** 查看是否有了 root 权限。 */
    @JvmStatic
    fun checkRootPermission(): Boolean = execCommand("echo root", true, false).result == 0

    /** 执行 shell 命令，默认返回结果。 */
    @JvmStatic
    fun execCommand(command: String, isRoot: Boolean): CommandResult =
        execCommand(arrayOf(command), isRoot, true)

    /** 执行 shell 命令列表，默认返回结果。 */
    @JvmStatic
    fun execCommand(commands: List<String>?, isRoot: Boolean): CommandResult =
        execCommand(commands?.toTypedArray(), isRoot, true)

    /** 执行 shell 命令数组，默认返回结果。 */
    @JvmStatic
    fun execCommand(commands: Array<String>?, isRoot: Boolean): CommandResult =
        execCommand(commands, isRoot, true)

    @JvmStatic
    fun execCommand(command: String, isRoot: Boolean, isNeedResultMsg: Boolean): CommandResult =
        execCommand(arrayOf(command), isRoot, isNeedResultMsg)

    @JvmStatic
    fun execCommand(commands: List<String>?, isRoot: Boolean, isNeedResultMsg: Boolean): CommandResult =
        execCommand(commands?.toTypedArray(), isRoot, isNeedResultMsg)

    /**
     * 执行 shell 命令数组。
     * - isNeedResultMsg 为 false 时 successMsg 与 errorMsg 均为 null；
     * - result 为 -1 时表示发生了异常。
     */
    @JvmStatic
    fun execCommand(commands: Array<String>?, isRoot: Boolean, isNeedResultMsg: Boolean): CommandResult {
        var result = -1
        if (commands == null || commands.isEmpty()) return CommandResult(result, null, null)

        var process: Process? = null
        var successResult: BufferedReader? = null
        var errorResult: BufferedReader? = null
        var successMsg: StringBuilder? = null
        var errorMsg: StringBuilder? = null
        var os: DataOutputStream? = null
        try {
            process = Runtime.getRuntime().exec(if (isRoot) COMMAND_SU else COMMAND_SH)
            os = DataOutputStream(process.outputStream)
            for (command in commands) {
                if (command == null) continue
                // 不用 os.writeBytes(command)，避免中文编码问题。
                os.write(command.toByteArray())
                os.writeBytes(COMMAND_LINE_END)
                os.flush()
            }
            os.writeBytes(COMMAND_EXIT)
            os.flush()
            result = process.waitFor()
            if (isNeedResultMsg) {
                successMsg = StringBuilder()
                errorMsg = StringBuilder()
                successResult = BufferedReader(InputStreamReader(process.inputStream))
                errorResult = BufferedReader(InputStreamReader(process.errorStream))
                var s: String?
                while (successResult.readLine().also { s = it } != null) successMsg.append(s)
                while (errorResult.readLine().also { s = it } != null) errorMsg.append(s)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                os?.close()
                successResult?.close()
                errorResult?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
            process?.destroy()
        }
        return CommandResult(result, successMsg?.toString(), errorMsg?.toString())
    }

    /** 运行结果：result 为 0 表示正常，否则与 Linux shell 语义一致。 */
    class CommandResult {
        var result = 0
        var successMsg: String? = null
        var errorMsg: String? = null

        constructor(result: Int) {
            this.result = result
        }

        constructor(result: Int, successMsg: String?, errorMsg: String?) {
            this.result = result
            this.successMsg = successMsg
            this.errorMsg = errorMsg
        }
    }
}
