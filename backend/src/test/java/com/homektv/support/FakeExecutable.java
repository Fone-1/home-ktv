package com.homektv.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

/**
 * 测试用「假可执行程序」生成器。
 *
 * <p>部分测试需要模拟一个会失败（或返回特定退出码）的 ffmpeg。原实现直接写 {@code .sh} 并调用
 * {@code Files.setPosixFilePermissions}，在 Windows 上会因不支持 POSIX 权限而抛
 * {@link UnsupportedOperationException}，导致测试在进入被测代码前就失败。
 *
 * <p>这里按平台生成对应脚本：POSIX 用 shell 脚本并赋可执行权限，Windows 用 {@code .cmd}。
 * 两者都支持「把内容写到最后一个参数（即输出文件）」这一假 ffmpeg 行为。
 */
public final class FakeExecutable {

    private FakeExecutable() {
    }

    /**
     * 生成一个「写出部分内容后以非零码退出」的假可执行程序，模拟转码中途失败。
     *
     * <p>约定：输出文件路径是命令的最后一个参数（与 MediaTranscoder / TranscodeService 的构造方式一致）。
     *
     * @param dir  生成目录
     * @param name 文件基础名（不含扩展名）
     * @return 可直接作为命令首元素使用的路径
     */
    public static Path failingWriter(Path dir, String name) throws IOException {
        return writeLastArgumentWriter(dir, name, "partial", 1);
    }

    /**
     * 生成一个「把指定内容写入最后一个参数后以指定退出码结束」的假可执行程序。
     */
    public static Path writeLastArgumentWriter(Path dir, String name, String content, int exitCode)
            throws IOException {
        if (isWindows()) {
            Path script = dir.resolve(name + ".cmd");
            // 逐个 shift 取出最后一个参数，避免 >9 个参数时 %9 的限制
            String batch = "@echo off\r\n"
                    + "setlocal enabledelayedexpansion\r\n"
                    + "set \"last=\"\r\n"
                    + ":loop\r\n"
                    + "if \"%~1\"==\"\" goto done\r\n"
                    + "set \"last=%~1\"\r\n"
                    + "shift\r\n"
                    + "goto loop\r\n"
                    + ":done\r\n"
                    + "if not \"!last!\"==\"\" echo " + content + "> \"!last!\"\r\n"
                    + "exit /b " + exitCode + "\r\n";
            Files.writeString(script, batch);
            return script;
        }
        Path script = dir.resolve(name + ".sh");
        String shell = "#!/bin/sh\n"
                + "for last; do :; done\n"
                + "if [ -n \"$last\" ]; then printf '%s' '" + content + "' > \"$last\"; fi\n"
                + "exit " + exitCode + "\n";
        Files.writeString(script, shell);
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException posixUnavailable) {
            script.toFile().setExecutable(true);
        }
        return script;
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 生成一个「把指定文件复制到最后一个参数」后正常退出的假可执行程序。
     * 用于模拟 ffmpeg 成功产出目标文件（例如封面格式转换）。
     */
    public static Path copyFileToLastArgument(Path dir, String name, Path source) throws IOException {
        if (isWindows()) {
            Path script = dir.resolve(name + ".cmd");
            String batch = "@echo off\r\n"
                    + "setlocal enabledelayedexpansion\r\n"
                    + "set \"last=\"\r\n"
                    + ":loop\r\n"
                    + "if \"%~1\"==\"\" goto done\r\n"
                    + "set \"last=%~1\"\r\n"
                    + "shift\r\n"
                    + "goto loop\r\n"
                    + ":done\r\n"
                    + "if not \"!last!\"==\"\" copy /y \"" + source + "\" \"!last!\" >nul\r\n"
                    + "exit /b 0\r\n";
            Files.writeString(script, batch);
            return script;
        }
        Path script = dir.resolve(name + ".sh");
        Files.writeString(script, "#!/bin/sh\nfor last; do :; done\ncp '" + source + "' \"$last\"\n");
        try {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException posixUnavailable) {
            script.toFile().setExecutable(true);
        }
        return script;
    }
}
