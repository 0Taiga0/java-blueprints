package dev.blueprints.check;

import java.util.List;

public final class CheckModels {
    private CheckModels() {}
    public record Request(String fileName, String source, String testFileName, String testSource) {}
    public record ComparisonRequest(String fileName, String baselineSource, String source, String testFileName, String testSource) {}
    public record Diagnostic(String file, int line, int column, String message) {}
    public record TestCase(String name, String className, String status, String message, double seconds) {}
    public record Result(String status, String message, long durationMs, List<Diagnostic> diagnostics,
                         List<TestCase> tests, int passed, int failed, int skipped,
                         String output, boolean outputTruncated) {}
    public record TestComparison(String name, String className, String change, TestCase before, TestCase after) {}
    public record Comparison(String status, String message, List<String> reasons, Result before, Result after,
                             List<TestComparison> tests, int unchanged, int regressions, int fixed, int unresolved) {}
}
