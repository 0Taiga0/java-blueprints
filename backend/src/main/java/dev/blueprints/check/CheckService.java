package dev.blueprints.check;

import dev.blueprints.roundtrip.SourceException;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static dev.blueprints.check.CheckModels.*;

@Service
public class CheckService {
    private final Semaphore slot = new Semaphore(1);
    private final Duration compileTimeout;
    private final Duration testTimeout;
    public CheckService() { this(Duration.ofSeconds(15), Duration.ofSeconds(8)); }
    CheckService(Duration compileTimeout, Duration testTimeout) {
        this.compileTimeout = compileTimeout; this.testTimeout = testTimeout;
    }

    public Result check(Request request, boolean runTests) {
        Request prepared = prepare(request, runTests);
        acquire();
        try { return execute(prepared, runTests); }
        finally { slot.release(); }
    }

    public Comparison compare(ComparisonRequest request) {
        if (request == null) throw bad("Передайте обе версии Java-кода.");
        // Один текст тестов: пользователь не может случайно проверить версии разными тестами.
        Request before = prepare(new Request(request.fileName(), request.baselineSource(), request.testFileName(), request.testSource()), true);
        Request after = prepare(new Request(request.fileName(), request.source(), request.testFileName(), request.testSource()), true);
        acquire();
        try {
            // Каждому запуску — новая папка и JVM. Проверяем обе версии, даже если первая ошибочна.
            Result original = execute(before, true);
            Result current = execute(after, true);
            return BehaviorComparison.compare(original, current);
        } finally { slot.release(); }
    }

    private static Request prepare(Request request, boolean runTests) {
        if (request == null) throw bad("Передайте Java-код.");
        return new Request(validateName(request.fileName()), validateSource(request.source(), 1024 * 1024, "Java-код"),
            runTests ? validateName(request.testFileName()) : null,
            runTests ? validateSource(request.testSource(), 128 * 1024, "Тесты") : null);
    }

    private void acquire() {
        if (!slot.tryAcquire()) throw new SourceException(HttpStatus.TOO_MANY_REQUESTS,
                "Уже выполняется проверка. Дождитесь её завершения.");
    }

    private Result execute(Request request, boolean runTests) {
        String name = request.fileName(), source = request.source(), testName = request.testFileName(), tests = request.testSource();
        Path javac = executable("javac"), java = executable("java");
        if (!Files.isRegularFile(javac)) throw new SourceException(HttpStatus.SERVICE_UNAVAILABLE,
                "Для компиляции нужен полный JDK 21 или новее. Запустите сервер через JDK, а не JRE.");
        long start = System.nanoTime();
        Path directory = null;
        try {
            directory = Files.createTempDirectory("java-blueprints-check-").toAbsolutePath();
            Path sourceDir = Files.createDirectory(directory.resolve("source"));
            Path classes = Files.createDirectory(directory.resolve("classes"));
            Files.writeString(sourceDir.resolve(name), source, StandardCharsets.UTF_8);
            List<String> command = new ArrayList<>(List.of(javac.toString(), "-J-Xmx192m", "-J-XX:ActiveProcessorCount=2",
                "-J-Dfile.encoding=UTF-8", "-J-Dstdout.encoding=UTF-8", "-J-Dstderr.encoding=UTF-8", "-J-Duser.language=en", "-J-Duser.country=US",
                "--release", "21", "-encoding", "UTF-8", "-proc:none", "-Xmaxerrs", "25",
                "-d", "classes", "-classpath", runTests ? "junit-console.jar" : "classes", "source/" + name));
            if (runTests) {
                try (InputStream bundled = getClass().getResourceAsStream("/runner/junit-console.jar")) {
                    if (bundled == null) throw new SourceException(HttpStatus.SERVICE_UNAVAILABLE,
                            "Не найден JUnit. Пересоберите проект через build.ps1.");
                    Files.copy(bundled, directory.resolve("junit-console.jar"));
                }
                Path testDir = Files.createDirectory(directory.resolve("tests"));
                Files.writeString(testDir.resolve(testName), tests, StandardCharsets.UTF_8);
                command.add("tests/" + testName);
            }
            var compilation = LimitedProcess.run(command, directory, compileTimeout);
            String compileOutput = cleanOutput(compilation.output(), directory);
            List<Diagnostic> diagnostics = diagnostics(compileOutput);
            if (compilation.timedOut()) return result("compile_timeout", "Компиляция превысила лимит времени.", start,
                    diagnostics, null, compileOutput, compilation.truncated());
            if (compilation.exitCode() != 0) return result("compile_error", "Java-код не скомпилирован. Исправьте ошибки ниже.", start,
                    diagnostics, null, compileOutput, compilation.truncated());
            if (!runTests) return result("compiled", "Код скомпилирован. Теперь можно проверить поведение тестами.", start,
                    diagnostics, null, compileOutput, compilation.truncated());

            // JUnit и пользовательские классы находятся в другой JVM, без классов сервера.
            var execution = LimitedProcess.run(List.of(java.toString(), "-Xmx128m", "-XX:MaxMetaspaceSize=96m",
                "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-Duser.language=en", "-Duser.country=US",
                "-Djava.io.tmpdir=" + directory, "-Duser.home=" + directory,
                "-jar", "junit-console.jar", "execute", "--disable-banner", "--disable-ansi-colors", "--details=summary",
                "--include-engine=junit-jupiter", "--class-path=classes", "--scan-class-path=classes", "--include-classname=.*",
                "--reports-dir=reports", "--fail-if-no-tests", "--config=junit.jupiter.execution.parallel.enabled=false"), directory, testTimeout);
            String output = compileOutput + cleanOutput(execution.output(), directory);
            boolean truncated = compilation.truncated() || execution.truncated();
            if (execution.timedOut()) return result("test_timeout", "Тесты превысили лимит времени и остановлены. Возможен бесконечный цикл.", start,
                    diagnostics, null, output, truncated);
            JunitReportReader.Report report;
            try { report = JunitReportReader.read(directory.resolve("reports")); }
            catch (Exception exception) {
                return result("runner_error", "Запуск завершился без полного отчёта JUnit. Посмотрите вывод процесса.", start,
                        diagnostics, null, output, truncated);
            }
            truncated |= report.truncated();
            String status, message;
            if (execution.exitCode() == 2 || (execution.exitCode() == 0 && report.passed() + report.failed() == 0)) {
                status = "no_tests"; message = "Ни один тест не выполнен. Добавьте метод с @Test и проверьте @Disabled.";
            } else if (execution.exitCode() == 1 || report.failed() > 0) {
                status = "tests_failed"; message = "Есть ошибки тестов или их подготовки. Сравните ожидаемый и полученный результат.";
            } else if (execution.exitCode() != 0) {
                status = "runner_error"; message = "Процесс JUnit завершился с ошибкой.";
            } else {
                status = "passed"; message = report.skipped() > 0 ? "Выполненные тесты прошли; часть тестов пропущена." : "Все выполненные тесты прошли.";
            }
            return result(status, message, start, diagnostics, report, output, truncated);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SourceException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка прервана. Повторите запуск.");
        } catch (IOException exception) {
            throw new SourceException(HttpStatus.SERVICE_UNAVAILABLE, "Не удалось запустить JDK или создать временные файлы проверки.");
        } finally {
            cleanup(directory);
        }
    }

    private Result result(String status, String message, long start, List<Diagnostic> diagnostics,
                          JunitReportReader.Report report, String output, boolean truncated) {
        int limit = LimitedProcess.OUTPUT_LIMIT;
        return new Result(status, message, (System.nanoTime() - start) / 1_000_000, diagnostics,
                report == null ? List.of() : report.tests(), report == null ? 0 : report.passed(),
                report == null ? 0 : report.failed(), report == null ? 0 : report.skipped(),
                output.length() > limit ? output.substring(0, limit) : output, truncated || output.length() > limit);
    }

    static List<Diagnostic> diagnostics(String output) {
        String[] lines = output.split("\\R");
        Pattern header = Pattern.compile("^(?:source|tests)[/\\\\](.+\\.java):(\\d+): (?:error|warning): (.*)$");
        List<Diagnostic> result = new ArrayList<>();
        for (int i = 0; i < lines.length && result.size() < 25; i++) {
            var matcher = header.matcher(lines[i]);
            if (!matcher.matches()) continue;
            String kind = lines[i].startsWith("tests") ? "tests" : "source";
            int column = i + 2 < lines.length ? Math.max(1, lines[i + 2].indexOf('^') + 1) : 1;
            result.add(new Diagnostic(kind, Integer.parseInt(matcher.group(2)), column, matcher.group(3)));
        }
        return List.copyOf(result);
    }

    private static String validateName(String value) {
        if (value == null || value.length() > 160 || !value.matches("[\\p{L}_$][\\p{L}\\p{N}_$]*\\.java"))
            throw bad("Укажите имя Java-файла без папок, например CalculatorTest.java.");
        String stem = value.substring(0, value.length() - 5).toUpperCase(Locale.ROOT);
        if (stem.matches("CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9]")) throw bad("Выберите другое имя Java-файла.");
        return value;
    }
    private static String validateSource(String source, int limit, String label) {
        if (source == null || source.isBlank()) throw bad(label + ": добавьте текст перед проверкой.");
        if (source.getBytes(StandardCharsets.UTF_8).length > limit)
            throw new SourceException(HttpStatus.PAYLOAD_TOO_LARGE, label + ": превышен допустимый размер.");
        String normalized = source.replace("\r\n", "\n").replace("\r", "\n").replaceFirst("^\uFEFF", "");
        if (normalized.isBlank()) throw bad(label + ": добавьте текст перед проверкой.");
        return normalized;
    }
    private static SourceException bad(String message) { return new SourceException(HttpStatus.BAD_REQUEST, message); }
    private static Path executable(String name) {
        return Path.of(System.getProperty("java.home"), "bin", name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
    }
    private static String cleanOutput(String output, Path directory) {
        return output.replace(directory.toString(), "<проверка>");
    }
    private static void cleanup(Path directory) {
        if (directory == null) return;
        // Только созданная нами временная папка. Символические ссылки не обходим.
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file); return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException error) throws IOException {
                    Files.deleteIfExists(dir); return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) { /* ОС может удерживать файл после принудительной остановки. */ }
    }
}
