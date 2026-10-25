package dev.blueprints.flow;

public final class BlockDeleteModels {
    private BlockDeleteModels() {}
    public record Request(String fileName, String source, String revision, String methodId, String nodeId, String mode) {}
}
