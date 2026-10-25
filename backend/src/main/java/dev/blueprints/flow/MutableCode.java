package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static dev.blueprints.flow.FlowModels.*;

/** Изменяется исходный оператор целиком, даже если граф показывает несколько маршрутов finally. */
final class MutableCode {
    private MutableCode() {}
    static Statement statement(Node node) {
        if (node instanceof Expression e) {
            Node parent = e.getParentNode().orElse(null);
            if (parent instanceof IfStmt s && s.getCondition() == e) node = s;
            else if (parent instanceof WhileStmt s && s.getCondition() == e) node = s;
            else if (parent instanceof DoStmt s && s.getCondition() == e) node = s;
            else if (parent instanceof ForEachStmt s && s.getIterable() == e) node = s;
            else if (parent instanceof ForStmt s && s.getCompare().orElse(null) == e) node = s;
            else return null;
        }
        if (!(node instanceof Statement s) || s instanceof BlockStmt || s instanceof EmptyStmt
                || s instanceof ExplicitConstructorInvocationStmt) return null;
        // У выражения после -> в switch-выражении есть неявный yield. Не превращаем его в действие.
        if (s.getParentNode().orElse(null) instanceof SwitchEntry entry
                && entry.getType() == SwitchEntry.Type.EXPRESSION
                && entry.getParentNode().orElse(null) instanceof SwitchExpr) return null;
        return s;
    }
    static MutationTarget describe(String kind, Node node) {
        if (Set.of("start", "end", "exception-end", "finally", "catch", "case").contains(kind)) return null;
        Statement s = statement(node);
        if (s == null) return null;
        List<String> modes = new ArrayList<>(List.of("delete"));
        if (s instanceof IfStmt branch && branch.getCondition().findAll(PatternExpr.class).isEmpty()) {
            modes.add("keep-then");
            if (branch.getElseStmt().isPresent()) modes.add("keep-else");
        } else if (InsertableCode.loopBody(s) != null && canKeepBody(s)) modes.add("keep-body");
        return new MutationTarget(InsertableCode.range(s),
                s.getTokenRange().map(Object::toString).orElseGet(s::toString), List.copyOf(modes), true);
    }
    static boolean canKeepBody(Statement loop) {
        Statement body = InsertableCode.loopBody(loop);
        if (body == null) return false;
        if (loop instanceof DoStmt d && !d.getCondition().findAll(PatternExpr.class).isEmpty()
                || loop instanceof WhileStmt w && !w.getCondition().findAll(PatternExpr.class).isEmpty()
                || loop instanceof ForStmt f && f.getCompare().map(e -> !e.findAll(PatternExpr.class).isEmpty()).orElse(false)) return false;
        Set<String> headerVariables = new HashSet<>();
        if (loop instanceof ForEachStmt each) each.getVariable().getVariables().forEach(v -> headerVariables.add(v.getNameAsString()));
        if (loop instanceof ForStmt forLoop) forLoop.getInitialization().stream().filter(e -> e instanceof VariableDeclarationExpr)
                .map(e -> (VariableDeclarationExpr)e).forEach(e -> e.getVariables().forEach(v -> headerVariables.add(v.getNameAsString())));
        if (body.findAll(NameExpr.class).stream().anyMatch(name -> headerVariables.contains(name.getNameAsString()))) return false;
        // Не позволяем break/continue незаметно начать относиться к внешнему циклу.
        return body.findAll(Statement.class).stream()
                .filter(s -> s instanceof BreakStmt || s instanceof ContinueStmt)
                .noneMatch(s -> MutationValidation.jumpTarget(s) == loop
                        || MutationValidation.jumpTarget(s) instanceof LabeledStmt label && label.getStatement() == loop);
    }
    static boolean isAncestor(Node ancestor, Node node) {
        for (Node p = node; p != null; p = p.getParentNode().orElse(null)) if (p == ancestor) return true;
        return false;
    }
}
