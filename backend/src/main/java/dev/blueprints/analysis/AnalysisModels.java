package dev.blueprints.analysis;

import dev.blueprints.flow.FlowModels.SourceRange;
import java.util.List;

/** Данные анализа без изменяемых объектов AST. */
public final class AnalysisModels {
    private AnalysisModels() {}
    public record Report(List<Finding> findings, List<MethodAnalysis> methods,
                         List<String> limitations, boolean truncated) {}
    public record Finding(String id, String rule, String severity, String title, String message,
                          String recommendation, String methodId, SourceRange range, List<String> nodeIds) {}
    public record MethodAnalysis(String methodId, String signature, SourceRange range,
                                 int statementCount, int maxNesting, Complexity complexity) {}
    public record Complexity(String loopEstimate, String methodEstimate, String explanation,
                             List<Variable> variables, List<String> assumptions, List<String> unknownReasons) {}
    public record Variable(String symbol, String meaning) {}
}
