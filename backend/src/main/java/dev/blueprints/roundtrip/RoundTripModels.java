package dev.blueprints.roundtrip;

import java.util.List;
import dev.blueprints.flow.FlowModels.MethodFlow;

/** Контракт между браузером и сервером: простые данные без объектов JavaParser. */
public final class RoundTripModels {
    private RoundTripModels() {}

    public record Request(String fileName, String source) {}

    public record Diagnostic(String message, Integer line, Integer column) {}

    public record ApiError(String message, List<Diagnostic> diagnostics) {}

    public record Declaration(String kind, String name, int line) {}

    public record Statistics(
            int types, int methods, int constructors,
            int sourceLines, int generatedLines, long astNodes) {}

    public record Response(
            String fileName, String generatedSource, String languageLevel,
            boolean reparsed, boolean structureEquivalent,
            Statistics statistics, List<Declaration> declarations, List<MethodFlow> methods, String revision, MethodFlow structure, dev.blueprints.analysis.AnalysisModels.Report analysis) {}
}
