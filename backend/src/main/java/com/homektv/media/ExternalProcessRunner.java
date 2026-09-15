package com.homektv.media;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

/**
 * 统一的外部进程执行器（阶段三任务 3.1）。
 *
 * <p>集中解决散落在 FFmpeg/FFprobe 调用点上的四个问题：
 * <ol>
 *   <li>每次调用都有执行超时，超时后 destroyForcibly() 并等待进程真正退出；</li>
 *   <li>stdout/stderr 由后台守护线程持续消费，避免管道写满导致子进程阻塞死锁；</li>
 *   <li>输出只保留末尾固定长度，防止异常日志撑爆内存；</li>
 *   <li>统一记录阶段、输入文件、耗时与退出码，并对命令参数做脱敏，不落任何 Token。</li>
 * </ol>
 *
 * <p>取消语义：传入 {@link BooleanSupplier} 后执行器按 200ms 轮询，取消或线程中断时
 * 强制结束子进程并返回 {@code cancelled=true} / 抛出 {@link InterruptedException}，由调用方决定如何上报。
 */
public final class ExternalProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ExternalProcessRunner.class);

    /** 输出保留上限：只留末尾 8K 字符用于报错，避免长日志占用内存。 */
    private static final int MAX_OUTPUT_CHARS = 8_000;
    /** 取消/超时的轮询间隔。 */
    private static final long POLL_INTERVAL_MS = 200;
    /** 强制结束后的收尾等待时间。 */
    private static final long FORCE_KILL_GRACE_MS = 5_000;

    /**
     * 敏感信息脱敏规则：Bearer Token、URL 里的 token/sign/key 参数、Cookie 会话值。
     * 命令日志只用于排查，绝不记录凭据。
     */
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)(bearer\\s+\\S+|(?:token|sign|key|secret|sessdata|access_token)\\s*[=:]\\s*\\S+|SESSDATA=\\S+)");

    private ExternalProcessRunner() {
    }

    /**
     * 执行结果。merged 模式下 stderr 已并入 stdout，{@code stderr} 为空字符串。
     *
     * @param exitCode  子进程退出码；超时或取消时为 -1
     * @param stdout    标准输出（merged 模式下含 stderr）
     * @param stderr    标准错误（merged 模式下为空）
     * @param timedOut  是否因超时被强制结束
     * @param cancelled 是否因取消或线程中断被强制结束
     * @param elapsedMs 实际耗时（毫秒）
     */
    public record Result(int exitCode, String stdout, String stderr, boolean timedOut,
                         boolean cancelled, long elapsedMs) {

        /** 进程正常结束且退出码为 0。 */
        public boolean ok() {
            return !timedOut && !cancelled && exitCode == 0;
        }

        /** 用于错误信息的最长有效输出：优先 stderr，其次 stdout。 */
        public String diagnostic() {
            String text = stderr == null || stderr.isBlank() ? stdout : stderr;
            return text == null ? "" : text.trim();
        }
    }

    /** 合并 stderr 到 stdout 执行（多数 FFmpeg 调用只需一份日志）。 */
    public static Result run(String stage, List<String> command, Path inputFile, Duration timeout)
            throws IOException, InterruptedException {
        return run(stage, command, inputFile, timeout, null);
    }

    /** 合并 stderr 到 stdout 执行，支持取消。 */
    public static Result run(String stage, List<String> command, Path inputFile, Duration timeout,
                             BooleanSupplier cancelled) throws IOException, InterruptedException {
        return execute(stage, command, inputFile, timeout, cancelled, true);
    }

    /** stdout/stderr 分离执行（ffprobe 需要干净 JSON，不能被 stderr 污染）。 */
    public static Result runSplit(String stage, List<String> command, Path inputFile, Duration timeout)
            throws IOException, InterruptedException {
        return execute(stage, command, inputFile, timeout, null, false);
    }

    private static Result execute(String stage, List<String> command, Path inputFile, Duration timeout,
                                  BooleanSupplier cancelled, boolean mergeErrorStream)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(mergeErrorStream);
        long startedAt = System.nanoTime();
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            log.warn("外部进程无法启动 stage={} program={} error={}", stage, programName(command), e.getMessage());
            throw e;
        }

        BoundedOutput primary = new BoundedOutput(process.getInputStream());
        Thread primaryThread = startDrainThread(primary, stage + "-stdout");
        BoundedOutput secondary = null;
        Thread secondaryThread = null;
        if (!mergeErrorStream) {
            secondary = new BoundedOutput(process.getErrorStream());
            secondaryThread = startDrainThread(secondary, stage + "-stderr");
        }

        boolean timedOut = false;
        boolean wasCancelled = false;
        int exitCode = -1;
        try {
            long deadlineNs = System.nanoTime() + timeout.toNanos();
            while (true) {
                if (cancelled != null && cancelled.getAsBoolean()) {
                    wasCancelled = true;
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    // 中断语义交给调用方：先清理子进程再抛出，避免留下孤儿进程
                    destroyForcibly(process);
                    throw new InterruptedException(stage + " 被中断");
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNs - System.nanoTime());
                if (remainingMs <= 0) {
                    timedOut = true;
                    break;
                }
                if (process.waitFor(Math.min(POLL_INTERVAL_MS, remainingMs), TimeUnit.MILLISECONDS)) {
                    exitCode = process.exitValue();
                    break;
                }
            }
        } catch (InterruptedException e) {
            destroyForcibly(process);
            throw e;
        }

        if (timedOut || wasCancelled) {
            destroyForcibly(process);
            exitCode = -1;
        }

        // 等待消费线程收尾，保证 output 拿到完整末尾内容
        joinQuietly(primaryThread);
        joinQuietly(secondaryThread);

        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        Result result = new Result(exitCode, primary.text(),
                secondary == null ? "" : secondary.text(), timedOut, wasCancelled, elapsedMs);

        if (timedOut) {
            log.warn("外部进程执行超时 stage={} input={} timeout={}s elapsed={}ms output={}",
                    stage, fileName(inputFile), timeout.toSeconds(), elapsedMs, result.diagnostic());
        } else if (wasCancelled) {
            log.info("外部进程已取消 stage={} input={} elapsed={}ms", stage, fileName(inputFile), elapsedMs);
        } else {
            log.info("外部进程完成 stage={} input={} exit={} elapsed={}ms",
                    stage, fileName(inputFile), exitCode, elapsedMs);
            if (exitCode != 0) {
                log.warn("外部进程非零退出 stage={} input={} exit={} output={}",
                        stage, fileName(inputFile), exitCode, result.diagnostic());
            }
        }
        if (log.isDebugEnabled()) {
            log.debug("外部进程命令 stage={} command={}", stage, maskedCommand(command));
        }
        return result;
    }

    private static Thread startDrainThread(Runnable task, String name) {
        Thread thread = new Thread(task, "extproc-" + name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void destroyForcibly(Process process) {
        if (process == null || !process.isAlive()) return;
        process.destroyForcibly();
        try {
            process.waitFor(FORCE_KILL_GRACE_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        if (thread == null) return;
        try {
            thread.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String programName(List<String> command) {
        return command == null || command.isEmpty() ? "<empty>" : command.get(0);
    }

    private static String fileName(Path inputFile) {
        if (inputFile == null) return "-";
        Path name = inputFile.getFileName();
        return name == null ? inputFile.toString() : name.toString();
    }

    /** 命令脱敏后拼接，仅供 debug 日志使用。 */
    static String maskedCommand(List<String> command) {
        if (command == null) return "";
        StringBuilder text = new StringBuilder();
        for (String arg : command) {
            if (text.length() > 0) text.append(' ');
            text.append(mask(arg));
        }
        return text.toString();
    }

    private static String mask(String arg) {
        if (arg == null) return "";
        if (SENSITIVE.matcher(arg).find()) return "[REDACTED]";
        // 带长查询串的直链（如 B 站签名 URL）只保留 origin+path
        int query = arg.indexOf('?');
        if (query > 0 && arg.length() - query > 40) {
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("http://") || lower.startsWith("https://")) {
                return arg.substring(0, query) + "?[REDACTED]";
            }
        }
        return arg;
    }

    /**
     * 有界输出消费器：后台线程持续读取，只保留末尾 {@link #MAX_OUTPUT_CHARS} 个字符。
     * 读取必须持续进行，否则子进程会因管道写满而永久阻塞。
     */
    private static final class BoundedOutput implements Runnable {

        private final InputStream stream;
        private final StringBuilder buffer = new StringBuilder();

        BoundedOutput(InputStream stream) {
            this.stream = stream;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    append(new String(chunk, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // 进程被强制结束时读取中断属预期行为
            }
        }

        private synchronized void append(String text) {
            buffer.append(text);
            int overflow = buffer.length() - MAX_OUTPUT_CHARS;
            if (overflow > 0) buffer.delete(0, overflow);
        }

        synchronized String text() {
            return buffer.toString();
        }
    }
}
