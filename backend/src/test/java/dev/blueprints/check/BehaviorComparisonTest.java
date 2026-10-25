package dev.blueprints.check;

import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.blueprints.check.CheckModels.*;
import static org.junit.jupiter.api.Assertions.*;

class BehaviorComparisonTest {
    private TestCase test(String name, String status) { return new TestCase(name, "ExampleTest", status, "", 0.1); }
    private Result result(String status, TestCase... tests) {
        var list = List.of(tests);
        return new Result(status, status, 100, List.of(), list,
            (int) list.stream().filter(t -> t.status().equals("passed")).count(),
            (int) list.stream().filter(t -> t.status().equals("failed")).count(),
            (int) list.stream().filter(t -> t.status().equals("skipped")).count(), "", false);
    }

    @Test void matchesByIdentityRatherThanOrderAndIgnoresTiming() {
        var before = result("passed", test("a", "passed"), test("b", "passed"));
        var after = result("passed", new TestCase("b", "ExampleTest", "passed", "", 3.7), test("a", "passed"));
        var report = BehaviorComparison.compare(before, after);
        assertEquals("matched", report.status()); assertEquals(2, report.unchanged());
    }

    @Test void labelsRegressionsAndSuccessfulTests() {
        var report = BehaviorComparison.compare(result("passed", test("a", "passed"), test("b", "passed")),
            result("tests_failed", test("a", "failed"), test("b", "passed")));
        assertEquals("changed", report.status()); assertEquals(1, report.regressions()); assertEquals(1, report.unchanged());
        assertEquals("regression", report.tests().getFirst().change());
    }

    @Test void bothFailingAndFixedBaselineNeverBecomeGreen() {
        var old = result("tests_failed", test("a", "failed"));
        var same = BehaviorComparison.compare(old, old);
        assertEquals("baseline_failed", same.status()); assertEquals("both_failed", same.tests().getFirst().change());
        var fixed = BehaviorComparison.compare(old, result("passed", test("a", "passed")));
        assertEquals("baseline_failed", fixed.status()); assertEquals(1, fixed.fixed());
    }

    @Test void missingAddedOrChangedTestsAreIncomplete() {
        var report = BehaviorComparison.compare(result("passed", test("a", "passed")), result("passed", test("b", "passed")));
        assertEquals("inconclusive", report.status()); assertEquals(2, report.unresolved());
        assertTrue(report.tests().stream().allMatch(t -> t.change().equals("missing")));
    }

    @Test void skippedEmptyTruncatedAndAmbiguousReportsAreNotGreen() {
        for (Result value : List.of(
            result("no_tests"), result("passed", test("a", "passed"), test("b", "skipped")),
            result("passed", test("a", "passed"), test("a", "passed")),
            new Result("passed", "", 0, List.of(), List.of(test("a", "passed")), 1, 0, 0, "", true))) {
            var report = BehaviorComparison.compare(value, value);
            assertEquals("inconclusive", report.status()); assertFalse(report.reasons().isEmpty());
        }
    }

    @Test void duplicateNamesAreNotArbitrarilyPaired() {
        var report = BehaviorComparison.compare(result("passed", test("a", "passed"), test("a", "passed")),
            result("passed", test("a", "passed")));
        assertEquals("ambiguous", report.tests().getFirst().change());
        assertNull(report.tests().getFirst().before());
    }

    @Test void compileErrorsTimeoutsAndMissingReportsAreNotGreen() {
        var success = result("passed", test("a", "passed"));
        for (String status : List.of("compile_error", "compile_timeout", "test_timeout", "runner_error", "no_tests")) {
            assertEquals("inconclusive", BehaviorComparison.compare(success, result(status)).status());
            assertNotEquals("matched", BehaviorComparison.compare(result(status), success).status());
        }
    }

    @Test void containerSetupFailureIsStillADifference() {
        var report = BehaviorComparison.compare(result("passed", test("a", "passed")), result("tests_failed"));
        assertEquals("changed", report.status());
    }
}
