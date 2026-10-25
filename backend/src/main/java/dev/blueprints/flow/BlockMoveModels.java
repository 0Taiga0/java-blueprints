package dev.blueprints.flow;

public final class BlockMoveModels {
    private BlockMoveModels() {}
    public record Request(String fileName, String source, String revision, String methodId, String nodeId,
            String targetNodeId, String position) {}
}
