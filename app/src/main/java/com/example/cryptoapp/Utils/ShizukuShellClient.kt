package com.example.cryptoapp.Utils

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.example.cryptoapp.BuildConfig
import com.example.cryptoapp.shizuku.IShizukuShellService
import com.example.cryptoapp.shizuku.ShizukuShellUserService
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Shizuku 状态与 UserService 连接统一封装，Activity 不直接处理 Binder。 */
object ShizukuShellClient {
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun isAuthorized(): Boolean = isRunning() && runCatching {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun execute(context: Context, command: String, timeoutSeconds: Long = 20): ShellCommandResult {
        check(isRunning()) { "Shizuku 服务未运行，请先在 Shizuku 中启动服务" }
        check(isAuthorized()) { "尚未授予 Shizuku 权限" }

        val latch = CountDownLatch(1)
        var remote: IShizukuShellService? = null
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                remote = IShizukuShellService.Stub.asInterface(binder)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                remote = null
            }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context, ShizukuShellUserService::class.java))
            .processNameSuffix("terminal")
            .debuggable(BuildConfig.DEBUG)
            // UserService 实现变化时递增，Shizuku 会销毁仍加载旧 APK 代码的服务进程。
            .version(2)
            .tag("toolbox_terminal")

        Shizuku.bindUserService(args, connection)
        try {
            check(latch.await(8, TimeUnit.SECONDS)) { "连接 Shizuku UserService 超时" }
            val values = checkNotNull(remote).execute(command, timeoutSeconds.coerceIn(1, 120).toInt())
            return ShellCommandResult(
                exitCode = values.getOrNull(0)?.toIntOrNull() ?: -1,
                standardOutput = values.getOrNull(1).orEmpty(),
                errorOutput = values.getOrNull(2).orEmpty(),
                timedOut = values.getOrNull(3).toBoolean(),
                durationMillis = values.getOrNull(4)?.toLongOrNull() ?: 0L
            )
        } finally {
            // 某些 ROM/Shizuku 版本不会在 remove=true 时立即调用 destroy；显式通知服务退出，
            // 再解除绑定，避免后台长期残留 shell/root 身份进程。
            runCatching { remote?.destroy() }
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }
}
