package dev.blueprints.check;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Отдельный процесс, ограниченный по времени и объёму читаемого вывода. Это не security sandbox. */
final class LimitedProcess {
    static final int OUTPUT_LIMIT = 64 * 1024;
    record Result(int exitCode, boolean timedOut, String output, boolean truncated) {}

    static Result run(List<String> command, Path directory, Duration timeout) throws IOException, InterruptedException {
        var builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        var env = builder.environment();
        String windowsRoot = env.get("SystemRoot");
        env.clear(); // Не передаём токены, JAVA_TOOL_OPTIONS и настройки окружения сервера.
        if (windowsRoot != null) { env.put("SystemRoot", windowsRoot); env.put("WINDIR", windowsRoot); }
        env.put("PATH", Path.of(System.getProperty("java.home"), "bin").toString());
        env.put("TEMP", directory.toString()); env.put("TMP", directory.toString());
        var process = builder.start();
        process.getOutputStream().close(); // Ввод с клавиатуры не поддерживается: чтение получает EOF.
        var capture = new Capture();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (var input = process.getInputStream()) {
                byte[] chunk = new byte[4096];
                for (int n; (n = input.read(chunk)) != -1;) capture.append(chunk, n);
            } catch (IOException ignored) { /* Поток закрывается при остановке процесса. */ }
        });
        boolean finished;
        // Запоминаем дочерние процессы до завершения родителя, чтобы остановить их вместе с ним.
        Set<ProcessHandle> children = new HashSet<>();
        try {
            long deadline = System.nanoTime() + timeout.toNanos();
            do {
                process.descendants().forEach(children::add);
                finished = process.waitFor(40, TimeUnit.MILLISECONDS);
            } while (!finished && System.nanoTime() < deadline);
            return finish(process, children, reader, capture, finished);
        } finally {
            process.descendants().forEach(children::add);
            children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            if (process.isAlive()) process.destroyForcibly();
            process.getInputStream().close();
        }
    }

    private static Result finish(Process process, Set<ProcessHandle> children, Thread reader,
                                 Capture capture, boolean finished) throws InterruptedException {
        process.descendants().forEach(children::add);
        children.forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
        if (!finished) process.destroyForcibly();
        process.waitFor(2, TimeUnit.SECONDS);
        reader.join(1500);
        return new Result(finished ? process.exitValue() : -1, !finished, capture.text(), capture.truncated());
    }

    private static final class Capture {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private boolean truncated;
        synchronized void append(byte[] chunk, int count) {
            int accepted = Math.min(count, OUTPUT_LIMIT - bytes.size());
            bytes.write(chunk, 0, accepted);
            if (accepted < count) truncated = true;
        }
        synchronized String text() { return bytes.toString(StandardCharsets.UTF_8); }
        synchronized boolean truncated() { return truncated; }
    }
}
