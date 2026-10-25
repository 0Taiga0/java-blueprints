package dev.blueprints.flow;

import java.util.List;

/** Typed UI data: no Java statement strings are accepted by the visual editor. */
public final class VisualModels {
    private VisualModels() {}
    public record Context(String fileName, String source, String revision, String methodId,
                          String nodeId, String position) {}
    public record Apply(String fileName, String source, String revision, String methodId,
                        String nodeId, String position, String mode, Spec spec) {}
    public record Expr(String kind, String value, String operator, Expr left, Expr right,
                       Expr operand, String callId, List<Expr> arguments) {
        public static Expr literal(String kind, String value) { return new Expr(kind,value,null,null,null,null,null,null); }
        public static Expr binary(String operator, Expr left, Expr right) { return new Expr("binary",null,operator,left,right,null,null,null); }
        public static Expr unary(String operator, Expr operand) { return new Expr("unary",null,operator,null,null,operand,null,null); }
        public static Expr call(String id, List<Expr> args) { return new Expr("call",null,null,null,null,null,id,args); }
    }
    public record Spec(String kind, String name, String type, Expr value, Boolean finalVariable,
                       String target, String operator, Expr condition, String loopKind,
                       Expr start, Expr step, Expr iterable, String callId, List<Expr> arguments) {}
    public record Variable(String name, String type, boolean writable) {}
    public record Parameter(String name, String type) {}
    public record Method(String id, String label, String returnType, List<Parameter> parameters) {}
    public record Description(String revision, boolean supported, String reason, Spec spec,
                              List<Variable> variables, List<Method> methods, List<String> types) {}
}
