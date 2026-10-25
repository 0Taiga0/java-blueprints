package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static dev.blueprints.flow.FlowModels.*;

/** Места вставки описывает сервер, используя связи AST, а не экранные координаты. */
final class InsertableCode {
    static SourceRange range(Node node) {
        var r = node.getRange().orElseThrow();
        return new SourceRange(r.begin.line, r.begin.column, r.end.line, r.end.column);
    }
    static InsertTarget describe(String kind, Node node) {
        if (node == null) return null;
        if (node instanceof SwitchEntry entry) {
            List<String> positions = new ArrayList<>(List.of("body-start"));
            boolean yields = entry.getType() == SwitchEntry.Type.EXPRESSION && entry.getParentNode().orElse(null) instanceof SwitchExpr;
            if (!yields && (entry.getStatements().isEmpty() || completes(entry.getStatement(entry.getStatements().size()-1)))) positions.add("body-end");
            return new InsertTarget("case", range(entry), List.copyOf(positions));
        }
        if (node instanceof BlockStmt body) {
            List<String> positions = new ArrayList<>();
            if (!kind.equals("end")) positions.add("body-start");
            if (completes(body)) positions.add("body-end");
            return new InsertTarget("body", range(body), positions);
        }
        if (node instanceof Expression e) {
            Node parent = e.getParentNode().orElse(null);
            if (parent instanceof IfStmt s && s.getCondition() == e) node = s;
            else if (parent instanceof WhileStmt s && s.getCondition() == e) node = s;
            else if (parent instanceof DoStmt s && s.getCondition() == e) node = s;
            else if (parent instanceof ForEachStmt s && s.getIterable() == e) node = s;
            else if (parent instanceof ForStmt s && s.getCompare().orElse(null) == e) node = s;
            else return null; // Части заголовка for не являются отдельными операторами.
        }
        if (!(node instanceof Statement statement) || statement instanceof BlockStmt) return null;
        if (statement.getParentNode().orElse(null) instanceof SwitchEntry entry
                && entry.getType() == SwitchEntry.Type.EXPRESSION
                && entry.getParentNode().orElse(null) instanceof SwitchExpr) return null;
        List<String> positions = new ArrayList<>();
        if (!(statement instanceof ExplicitConstructorInvocationStmt)) positions.add("before");
        if (completes(statement)) positions.add("after");
        String role = "statement";
        if (statement instanceof IfStmt branch) {
            role = "if";
            positions.add("then-start");
            if (completes(branch.getThenStmt())) positions.add("then-end");
            positions.add("else-start");
            if (branch.getElseStmt().map(InsertableCode::completes).orElse(true)) positions.add("else-end");
        } else if (statement instanceof TryStmt t) {
            role = "try"; positions.add("body-start");
            if (completes(t.getTryBlock())) positions.add("body-end");
        } else if (statement instanceof SynchronizedStmt sync) {
            role = "synchronized"; positions.add("body-start");
            if (completes(sync.getBody())) positions.add("body-end");
        } else if (loopBody(statement) != null) {
            role = "loop";
            positions.add("body-start");
            if (completes(loopBody(statement))) positions.add("body-end");
        }
        return new InsertTarget(role, range(statement), positions);
    }
    static Statement loopBody(Statement s) {
        if (s instanceof WhileStmt w) return w.getBody();
        if (s instanceof ForStmt f) return f.getBody();
        if (s instanceof ForEachStmt f) return f.getBody();
        if (s instanceof DoStmt d) return d.getBody();
        return null;
    }
    // Это структурная проверка очевидно недостижимых операторов; полную проверку делает javac.
    static boolean completes(Statement s) {
        if (s instanceof YieldStmt || s instanceof ReturnStmt || s instanceof ThrowStmt || s instanceof BreakStmt || s instanceof ContinueStmt) return false;
        if (s instanceof BlockStmt b) return b.isEmpty() || completes(b.getStatement(b.getStatements().size() - 1));
        if (s instanceof SynchronizedStmt sync) return completes(sync.getBody());
        if (s instanceof LabeledStmt label) return completes(label.getStatement()) || label.findAll(BreakStmt.class).stream()
                .anyMatch(jump -> MutationValidation.jumpTarget(jump) == label);
        if (s instanceof IfStmt b) return b.getElseStmt().isEmpty() || completes(b.getThenStmt()) || completes(b.getElseStmt().orElseThrow());
        if (s instanceof TryStmt t) {
            if (t.getFinallyBlock().isPresent() && !completes(t.getFinallyBlock().orElseThrow())) return false;
            return completes(t.getTryBlock()) || t.getCatchClauses().stream().anyMatch(c -> completes(c.getBody()));
        }
        if (s instanceof SwitchStmt sw) {
            if (sw.getEntries().stream().noneMatch(e -> e.isDefault() || e.getLabels().isEmpty())) return true;
            if (sw.findAll(BreakStmt.class).stream().anyMatch(b -> b.getLabel().isEmpty()
                    && b.findAncestor(Statement.class, p -> p instanceof SwitchStmt || loopBody(p) != null).orElse(null) == sw)) return true;
            if (sw.getEntries().isEmpty()) return true;
            var last = sw.getEntry(sw.getEntries().size()-1);
            if (last.getStatements().isEmpty() || completes(last.getStatement(last.getStatements().size()-1))) return true;
            return sw.getEntries().stream().filter(e -> e.getType() != SwitchEntry.Type.STATEMENT_GROUP)
                    .anyMatch(e -> e.getStatements().isEmpty() || completes(e.getStatement(e.getStatements().size()-1)));
        }
        boolean always = s instanceof WhileStmt w && isTrue(w.getCondition())
                || s instanceof ForStmt f && f.getCompare().map(InsertableCode::isTrue).orElse(true)
                || s instanceof DoStmt d && isTrue(d.getCondition());
        if (always && !hasJump(loopBody(s), false)) return false;
        if (s instanceof DoStmt d && !completes(d.getBody()) && !hasJump(d.getBody(), false) && !hasJump(d.getBody(), true)) return false;
        return true;
    }
    private static boolean isTrue(Expression e) {
        if (e instanceof EnclosedExpr p) return isTrue(p.getInner());
        return e instanceof BooleanLiteralExpr b && b.getValue();
    }
    private static boolean hasJump(Node node, boolean continuation) {
        if (node == null || node instanceof BodyDeclaration<?> || node instanceof LambdaExpr) return false;
        if (continuation && node instanceof ContinueStmt c) return c.getLabel().isEmpty();
        if (!continuation && node instanceof BreakStmt b) return b.getLabel().isEmpty();
        if ((!continuation && node instanceof SwitchStmt) || (node instanceof Statement s && loopBody(s) != null)) return false;
        return node.getChildNodes().stream().anyMatch(child -> hasJump(child, continuation));
    }
    static void validateSequences(Node root) {
        for (BlockStmt block : root.findAll(BlockStmt.class)) {
            for (int i = 0; i < block.getStatements().size() - 1; i++) {
                if (!completes(block.getStatement(i)))
                    throw EditableCode.invalid("После return, break, continue или бесконечного цикла есть недостижимый код. Выберите другое место вставки.");
            }
        }
    }
}
