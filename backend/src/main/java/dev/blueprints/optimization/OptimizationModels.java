package dev.blueprints.optimization;

import dev.blueprints.flow.FlowModels.SourceRange;
import dev.blueprints.roundtrip.RoundTripModels.Response;
import java.util.List;

/** Предложение не применяет правку: клиент отдельно проверяет и принимает новый исходник. */
public final class OptimizationModels {
    private OptimizationModels() {}
    public record OptionsRequest(String fileName, String source, String revision) {}
    public record PreviewRequest(String fileName, String source, String revision, String candidateId) {}
    public record Candidate(String id, String rule, String title, String description, String methodId,
                            SourceRange range, List<String> nodeIds, boolean available, String reason) {}
    public record Options(String revision, List<Candidate> candidates, List<String> limitations) {}
    public record Preview(Candidate candidate, String revision, String source, Response conversion,
                          String selectedMethodId, String selectedNodeId, String summary, List<String> notes,
                          String beforeFragment, String afterFragment) {}
}
