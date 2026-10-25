package dev.blueprints.flow;

import dev.blueprints.roundtrip.RoundTripModels.Response;

public final class BlockEditModels {
    private BlockEditModels() {}
    public record Request(String fileName, String source, String revision,
            String methodId, String nodeId, String replacement) {}
    public record Result(String source, Response conversion, String selectedMethodId, String selectedNodeId) {}
}
