package com.example.cryptoapp.shizuku;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 由 Shizuku 启动的 UserService。该类运行在 shell（ADB Shizuku）或 root（Sui）身份下，
 * 不持有 Activity/应用 Context，也不调用依赖普通应用进程的 Android API。
 */
public final class ShizukuShellUserService extends IShizukuShellService.Stub {
    public ShizukuShellUserService() { }
    public ShizukuShellUserService(Context ignored) { }

    @Override public String[] execute(String command, int timeoutSeconds) {
        long startedAt = System.currentTimeMillis();
        Process process = null;
        ExecutorService readers = Executors.newFixedThreadPool(2);
        try {
            process = new ProcessBuilder("/system/bin/sh", "-c", command).start();
            final Process runningProcess = process;
            Future<String> output = readers.submit(() -> readText(runningProcess.getInputStream()));
            Future<String> error = readers.submit(() -> readText(runningProcess.getErrorStream()));
            boolean completed = process.waitFor(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (!completed) process.destroyForcibly();
            return new String[] {
                    String.valueOf(completed ? process.exitValue() : -1),
                    output.get(2, TimeUnit.SECONDS).trim(),
                    error.get(2, TimeUnit.SECONDS).trim(),
                    String.valueOf(!completed),
                    String.valueOf(System.currentTimeMillis() - startedAt)
            };
        } catch (Exception error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            return new String[] {
                    "-1", "", error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(),
                    "false", String.valueOf(System.currentTimeMillis() - startedAt)
            };
        } finally {
            readers.shutdownNow();
            if (process != null) process.destroy();
        }
    }

    @Override public void destroy() {
        System.exit(0);
    }

    /** InputStream.readAllBytes() 需要 API 33；手动读取可兼容本项目最低 API 26。 */
    private static String readText(InputStream input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int length;
        while ((length = input.read(buffer)) != -1) output.write(buffer, 0, length);
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
