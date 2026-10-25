package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;

/** Только структурные ошибки переходов и очевидной достижимости. Проверка типов остаётся javac. */
final class MutationValidation {
    private MutationValidation() {}
    static void validate(Node root) {
        InsertableCode.validateSequences(root);
        for (SwitchEntry entry : root.findAll(SwitchEntry.class)) validateSequence(entry.getStatements());
        for (Statement statement : root.findAll(Statement.class)) {
            if ((statement instanceof BreakStmt || statement instanceof ContinueStmt) && jumpTarget(statement) == null)
                throw EditableCode.invalid("break/continue остался без подходящего цикла, switch или метки. Изменение отменено.");
            if (statement instanceof YieldStmt && yieldTarget(statement) == null)
                throw EditableCode.invalid("yield должен находиться внутри switch-выражения. Изменение отменено.");
            if (statement instanceof WhileStmt w && isFalse(w.getCondition())
                    || statement instanceof ForStmt f && f.getCompare().map(MutationValidation::isFalse).orElse(false))
                throw EditableCode.invalid("Тело while/for с постоянным условием false недостижимо. Изменение отменено.");
        }
    }
    static void validateSequence(List<Statement> statements) {
        for (int i = 0; i + 1 < statements.size(); i++) if (!InsertableCode.completes(statements.get(i)))
            throw EditableCode.invalid("После return, throw, break, continue или бесконечного цикла есть недостижимый код. Изменение отменено.");
    }
    static Node jumpTarget(Statement jump) {
        boolean continuing = jump instanceof ContinueStmt;
        Optional<SimpleName> label = jump instanceof BreakStmt b ? b.getLabel()
                : jump instanceof ContinueStmt c ? c.getLabel() : Optional.empty();
        for (Node p = jump.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (boundary(p) || p instanceof SwitchExpr) return null;
            if (label.isPresent()) {
                if (p instanceof LabeledStmt l && l.getLabel().equals(label.orElseThrow()))
                    return !continuing || InsertableCode.loopBody(l.getStatement()) != null ? l : null;
            } else if (p instanceof Statement s && (InsertableCode.loopBody(s) != null || !continuing && s instanceof SwitchStmt)) return p;
        }
        return null;
    }
    private static Node yieldTarget(Node node) {
        for (Node p = node.getParentNode().orElse(null); p != null; p = p.getParentNode().orElse(null)) {
            if (boundary(p)) return null;
            if (p instanceof SwitchExpr) return p;
        }
        return null;
    }
    private static boolean boundary(Node node) {
        return node instanceof CallableDeclaration<?> || node instanceof InitializerDeclaration
                || node instanceof LambdaExpr || node instanceof TypeDeclaration<?>;
    }
    private static boolean isFalse(Expression e) {
        return e instanceof EnclosedExpr p ? isFalse(p.getInner()) : e instanceof BooleanLiteralExpr b && !b.getValue();
    }
}
