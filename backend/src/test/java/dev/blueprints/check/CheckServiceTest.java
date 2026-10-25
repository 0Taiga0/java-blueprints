package dev.blueprints.check;

import dev.blueprints.flow.BlockEditModels;
import dev.blueprints.flow.BlockEditService;
import dev.blueprints.roundtrip.RoundTripService;
import dev.blueprints.roundtrip.SourceException;
import java.time.Duration;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static dev.blueprints.check.CheckModels.*;
import static org.junit.jupiter.api.Assertions.*;

class CheckServiceTest {
    private final CheckService service = new CheckService();
    private static final String SOURCE = "public class Calculator { public static int twice(int n) { return n * 2; } }";
    private static final String TESTS = """
        import org.junit.jupiter.api.Test;
        import static org.junit.jupiter.api.Assertions.*;
        class CalculatorTest {
            @Test void doublesPositive() { assertEquals(6, Calculator.twice(3)); }
            @Test void doublesZero() { assertEquals(0, Calculator.twice(0)); }
        }
        """;
    private Request request(String source, String tests) { return new Request("Calculator.java", source, "CalculatorTest.java", tests); }

    @Test void compilesWithoutExecutingCodeOrRequiringTests() {
        var result = service.check(request("class Calculator { static { if(true) throw new Error(); } }", null), false);
        assertEquals("compiled", result.status(), result.output());
        assertTrue(result.tests().isEmpty());
    }

    @Test void findsTypeErrorsAndTheirSourceLines() {
        var result = service.check(request("class Calculator {\n int number = \"not a number\";\n}", TESTS), true);
        assertEquals("compile_error", result.status(), result.output());
        assertEquals(2, result.diagnostics().getFirst().line());
        assertEquals("source", result.diagnostics().getFirst().file());
        assertTrue(result.tests().isEmpty());
    }

    @Test void identifiesCompilationErrorInTests() {
        var result = service.check(request(SOURCE, "class CalculatorTest {\n int x = missing;\n}"), true);
        assertEquals("compile_error", result.status(), result.output());
        assertEquals("tests", result.diagnostics().getFirst().file());
        assertEquals(2, result.diagnostics().getFirst().line());
    }

    @Test void runsRealJunitAssertionsAndReportsExpectedActual() {
        var pass = service.check(request(SOURCE, TESTS), true);
        assertEquals("passed", pass.status(), pass.output()); assertEquals(2, pass.passed());
        var fail = service.check(request(SOURCE.replace("n * 2", "n * 3"), TESTS), true);
        assertEquals("tests_failed", fail.status(), fail.output());
        assertEquals(1, fail.failed()); assertEquals(1, fail.passed());
        assertTrue(fail.tests().stream().anyMatch(t -> t.message().contains("expected: <6> but was: <9>")));
    }

    @Test void compilesAndTestsAnActualDiagramEdit() {
        var converter = new RoundTripService();
        var graph = converter.convert("Calculator.java", SOURCE);
        var method = graph.methods().getFirst();
        var node = method.nodes().stream().filter(n -> n.edit() != null && n.edit().kind().equals("return")).findFirst().orElseThrow();
        var changed = new BlockEditService(converter).edit(new BlockEditModels.Request("Calculator.java", SOURCE,
                graph.revision(), method.id(), node.id(), "return n * 3;"));
        var result = service.check(request(changed.source(), TESTS), true);
        assertEquals("tests_failed", result.status(), result.output()); assertEquals(1, result.failed());
    }

    @Test void emptyAndDisabledSuitesAreNotGreen() {
        var none = service.check(request(SOURCE, "class CalculatorTest {}"), true);
        assertEquals("no_tests", none.status(), none.output());
        var disabled = service.check(request(SOURCE, TESTS.replace("@Test", "@org.junit.jupiter.api.Disabled @Test")), true);
        assertEquals("no_tests", disabled.status(), disabled.output()); assertEquals(2, disabled.skipped());
    }

    @Test void failedSetupIsNotSuccess() {
        String tests = TESTS.replace("class CalculatorTest {", "class CalculatorTest { @org.junit.jupiter.api.BeforeAll static void init(){ throw new IllegalStateException(\"setup failed\"); }");
        var result = service.check(request(SOURCE, tests), true);
        assertEquals("tests_failed", result.status(), result.output());
        assertTrue(result.output().contains("setup failed"));
    }

    @Test void stopsAnInfiniteTestAndCanRunAgain() {
        var limited = new CheckService(Duration.ofSeconds(15), Duration.ofSeconds(2));
        var timeout = limited.check(request(SOURCE, TESTS.replace("assertEquals(6, Calculator.twice(3));", "while(true) {}")), true);
        assertEquals("test_timeout", timeout.status(), timeout.output());
        assertTrue(timeout.durationMs() < 12000);
        assertEquals("compiled", limited.check(request(SOURCE, null), false).status());
    }

    @Test void earlyProcessExitDoesNotBecomeGreen() {
        var result = service.check(request(SOURCE, TESTS.replace("assertEquals(6, Calculator.twice(3));", "System.exit(0);")), true);
        assertEquals("runner_error", result.status(), result.output());
    }

    @Test void capturesAndCapsProgramOutput() {
        String tests = TESTS.replace("assertEquals(6, Calculator.twice(3));", "System.out.println(\"Привет\"); System.out.print(\"x\".repeat(80000)); assertEquals(6, Calculator.twice(3));");
        var result = service.check(request(SOURCE, tests), true);
        assertEquals("passed", result.status(), result.output());
        assertTrue(result.output().contains("Привет"), result.output().substring(0, Math.min(150, result.output().length())));
        assertTrue(result.outputTruncated(), "Large stdout must be marked as truncated");
        assertTrue(result.output().length() <= LimitedProcess.OUTPUT_LIMIT);
    }

    @Test void supportsPackagesNonpublicClassesBomAndCrLf() {
        String source = "\uFEFFpackage shop;\r\n" + SOURCE;
        String tests = "\uFEFFpackage shop;\r\n" + TESTS;
        var result = service.check(request(source, tests), true);
        assertEquals("passed", result.status(), result.output()); assertEquals(2, result.passed());
    }

    @Test void doesNotExposeServerClassesOrEnvironment() {
        String tests = TESTS.replace("assertEquals(6, Calculator.twice(3));", """
            assertThrows(ClassNotFoundException.class, () -> Class.forName("dev.blueprints.check.CheckService"));
            assertTrue(System.getenv().keySet().stream().allMatch(k -> java.util.Set.of("PATH", "TEMP", "TMP", "SystemRoot", "WINDIR").contains(k)));
            """);
        var result = service.check(request(SOURCE, tests), true);
        assertEquals("passed", result.status(), result.output());
    }

    @Test void rejectsPathsBlankInputReservedNamesAndOversizedTests() {
        for (String name : new String[]{"../Calculator.java", "C:/Calculator.java", "CON.java", "-Bad.java"}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(SourceException.class,
                () -> service.check(new Request(name, SOURCE, "CalculatorTest.java", TESTS), true)).status());
        }
        assertThrows(SourceException.class, () -> service.check(request(" ", TESTS), true));
        assertThrows(SourceException.class, () -> service.check(request(SOURCE, " "), true));
        for (String blank : new String[]{"\uFEFF", "\uFEFF \r\n\t"}) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(SourceException.class,
                () -> service.check(request(blank, TESTS), true)).status());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(SourceException.class,
                () -> service.check(request(SOURCE, blank), true)).status());
        }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, assertThrows(SourceException.class,
            () -> service.check(request(SOURCE, "x".repeat(128 * 1024 + 1)), true)).status());
    }

    @Test void onlyOneCheckRunsAtATime() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            Callable<String> task = () -> {
                start.await();
                try { return service.check(request(SOURCE, TESTS), true).status(); }
                catch (SourceException e) { return e.status().toString(); }
            };
            var first = executor.submit(task); var second = executor.submit(task); start.countDown();
            var values = java.util.List.of(first.get(25, TimeUnit.SECONDS), second.get(25, TimeUnit.SECONDS));
            assertTrue(values.contains("passed"), values.toString());
            assertTrue(values.stream().anyMatch(v -> v.startsWith("429")), values.toString());
        }
    }
}
