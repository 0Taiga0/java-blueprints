package dev.blueprints.flow;

import java.util.List;

public final class FlowModels {
    private FlowModels() {}
    public record SourceRange(int startLine, int startColumn, int endLine, int endColumn) {}
    public record EditTarget(String kind, String text, SourceRange range) {}
    public record InsertTarget(String role, SourceRange range, List<String> positions) {}
    public record MutationTarget(SourceRange range, String text, List<String> deleteModes, boolean movable) {}
    public record FlowNode(String id, String kind, String label, SourceRange range, EditTarget edit,
            InsertTarget insert, String code, String detail, String category, String targetMethodId, MutationTarget mutation) {
        public FlowNode(String id, String kind, String label, SourceRange range, EditTarget edit, InsertTarget insert,
                String code, String detail, String category, String targetMethodId) {
            this(id,kind,label,range,edit,insert,code,detail,category,targetMethodId,null);
        }
        public FlowNode(String id, String kind, String label, SourceRange range, EditTarget edit, InsertTarget insert) {
            this(id,kind,label,range,edit,insert,label,"","",null);
        }
    }
    public record FlowEdge(String id, String source, String target, String label, String kind) {
        public FlowEdge(String id, String source, String target, String label) { this(id,source,target,label,"normal"); }
    }
    public record MethodFlow(String id, String owner, String signature, String kind,
            SourceRange range, String mode, List<String> warnings,
            List<FlowNode> nodes, List<FlowEdge> edges) {}
}
