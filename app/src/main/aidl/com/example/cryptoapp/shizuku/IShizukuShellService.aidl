package com.example.cryptoapp.shizuku;

/** 在 Shizuku UserService 进程内以 shell/root 身份执行命令。 */
interface IShizukuShellService {
    String[] execute(String command, int timeoutSeconds) = 1;
    void destroy() = 16777114;
}
