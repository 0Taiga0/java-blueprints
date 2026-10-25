package dev.blueprints.flow;

public final class BlockInsertModels {
    private BlockInsertModels() {}
    public record Request(String fileName, String source, String revision, String methodId,
            String nodeId, String position, String kind, String code) {}
}
