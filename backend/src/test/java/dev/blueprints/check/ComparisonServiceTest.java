package dev.blueprints.check;

import dev.blueprints.flow.BlockEditModels;
import dev.blueprints.flow.BlockEditService;
import dev.blueprints.roundtrip.RoundTripService;
import dev.blueprints.roundtrip.SourceException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import static dev.blueprints.check.CheckModels.*;
import static org.junit.jupiter.api.Assertions.*;

class ComparisonServiceTest {
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
    private ComparisonRequest request(String before, String after, String tests) {
        return new ComparisonRequest("Calculator.java", before, after, "CalculatorTest.java", tests);
    }

    @Test void sameTestsFindARegressionFromAnActualDiagramEdit() {
        var converter = new RoundTripService();
        var graph = converter.convert("Calculator.java", SOURCE);
        var method = graph.methods().getFirst();
        var node = method.nodes().stream().filter(n -> n.edit() != null && n.edit().kind().equals("return")).findFirst().orElseThrow();
        var edited = new BlockEditService(converter).edit(new BlockEditModels.Request("Calculator.java", SOURCE,
            graph.revision(), method.id(), node.id(), "return n * 3;"));
        var report = service.compare(request(SOURCE, edited.source(), TESTS));
        assertEquals("changed", report.status(), report.toString());
        assertEquals(2, report.before().passed()); assertEquals(1, report.after().failed());
        assertEquals(1, report.regressions()); assertEquals(1, report.unchanged());
        assertTrue(report.tests().stream().anyMatch(t -> t.after().message().contains("expected: <6> but was: <9>")));
    }

    @Test void equivalentRewriteMatchesWithParameterizedTests() {
        String tests = """
            import org.junit.jupiter.params.ParameterizedTest;
            import org.junit.jupiter.params.provider.ValueSource;
            import static org.junit.jupiter.api.Assertions.*;
            class CalculatorTest {
              @ParameterizedTest @ValueSource(ints={-2,0,3})
              void doubles(int n) { assertEquals(n * 2, Calculator.twice(n)); }
            }
            """;
        var report = service.compare(request(SOURCE, SOURCE.replace("n * 2", "n + n"), tests));
        assertEquals("matched", report.status(), report.toString()); assertEquals(3, report.unchanged());
    }

    @Test void runsIdenticalSourcesTwiceWithFreshStaticStateAndWorkingDirectories() {
        String source = "class Calculator { static int calls; static int next(){return ++calls;} }";
        String tests = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.*;
            class CalculatorTest {
              @Test void startsFresh() throws Exception {
                assertEquals(1, Calculator.next());
                java.nio.file.Files.createFile(java.nio.file.Path.of("created-by-test.txt"));
              }
            }
            """;
        var report = service.compare(request(source, source, tests));
        assertEquals("matched", report.status(), report.toString()); assertEquals(1, report.unchanged());
    }

    @Test void runsAfterEvenWhenBeforeDoesNotCompile() {
        var report = service.compare(request(SOURCE.replace("n * 2", "\"bad\""), SOURCE, TESTS));
        assertEquals("baseline_failed", report.status()); assertEquals("compile_error", report.before().status());
        assertEquals("passed", report.after().status()); assertEquals(2, report.after().passed());
    }

    @Test void currentCompilationErrorDoesNotClaimEqualBehavior() {
        var report = service.compare(request(SOURCE, SOURCE.replace("n * 2", "\"bad\""), TESTS));
        assertEquals("inconclusive", report.status()); assertEquals("passed", report.before().status());
        assertEquals("compile_error", report.after().status()); assertFalse(report.after().diagnostics().isEmpty());
    }

    @Test void timeoutInBeforeDoesNotPreventCheckingAfter() {
        var limited = new CheckService(Duration.ofSeconds(15), Duration.ofSeconds(2));
        var report = limited.compare(request(SOURCE.replace("return n * 2;", "while(true){}"), SOURCE, TESTS));
        assertEquals("inconclusive", report.status()); assertEquals("test_timeout", report.before().status());
        assertEquals("passed", report.after().status());
        assertEquals("compiled", limited.check(new Request("Calculator.java", SOURCE, null, null), false).status());
    }

    @Test void baselineFailureIsVisibleEvenWhenBothVersionsFailIdentically() {
        var wrong = SOURCE.replace("n * 2", "n * 3");
        var report = service.compare(request(wrong, wrong, TESTS));
        assertEquals("baseline_failed", report.status());
        assertEquals(1, report.before().failed()); assertEquals(1, report.after().failed());
        assertTrue(report.tests().stream().anyMatch(t -> t.change().equals("both_failed")));
    }

    @Test void validatesBothSourcesAndSharedTests() {
        assertThrows(SourceException.class, () -> service.compare(request(SOURCE, " ", TESTS)));
        assertThrows(SourceException.class, () -> service.compare(request(" ", SOURCE, TESTS)));
        assertThrows(SourceException.class, () -> service.compare(request(SOURCE, SOURCE, " ")));
        assertThrows(SourceException.class, () -> service.compare(new ComparisonRequest("../Calculator.java", SOURCE, SOURCE, "CalculatorTest.java", TESTS)));
    }

    @Test void comparisonAndOrdinaryCheckShareOneExecutionSlot() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> {
                start.await();
                try { return service.compare(request(SOURCE, SOURCE, TESTS)).status(); }
                catch (SourceException exception) { return exception.status().toString(); }
            });
            var second = executor.submit(() -> {
                start.await();
                try { return service.check(new Request("Calculator.java", SOURCE, null, null), false).status(); }
                catch (SourceException exception) { return exception.status().toString(); }
            });
            start.countDown();
            var results = List.of(first.get(45, TimeUnit.SECONDS), second.get(45, TimeUnit.SECONDS));
            assertTrue(results.stream().anyMatch(s -> s.startsWith("429")), results.toString());
            assertTrue(results.contains("matched") || results.contains("compiled"), results.toString());
        }
    }
}
