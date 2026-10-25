package dev.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.*;
import dev.blueprints.flow.FlowModels.SourceRange;
import java.util.*;

final class AnalysisAst {
    private AnalysisAst() {}
    record Item(Node node, int nesting) {}
    record Scan(List<Item> items, boolean truncated) {}
    private record Pending(Node node, int depth, int nesting) {}
    static BlockStmt body(Node declaration) {
        if (declaration instanceof MethodDeclaration m) return m.getBody().orElse(null);
        if (declaration instanceof ConstructorDeclaration c) return c.getBody();
        if (declaration instanceof InitializerDeclaration i) return i.getBody();
        return null;
    }
    static boolean boundary(Node node) {
        return node instanceof CallableDeclaration<?> || node instanceof TypeDeclaration<?>
                || node instanceof InitializerDeclaration || node instanceof LambdaExpr;
    }
    /** Лямбду/вложенный класс видим, но их тело принадлежит другому вычислению. */
    static Scan scan(Node root) {
        List<Item> result = new ArrayList<>();
        Deque<Pending> pending = new ArrayDeque<>();
        pending.push(new Pending(root, 0, 0));
        boolean truncated = false;
        while (!pending.isEmpty()) {
            Pending next = pending.pop();
            if (result.size() >= 20000) { truncated = true; break; }
            if (next.depth() > 120) { truncated = true; continue; }
            Node node = next.node();
            int nesting = next.nesting() + (isControl(node) ? 1 : 0);
            result.add(new Item(node, nesting));
            if (node != root && boundary(node)) continue;
            List<Node> children = node.getChildNodes();
            for (int i = children.size() - 1; i >= 0; i--) {
                // Объявления членов анонимного класса не исполняются при создании его объекта.
                if (node instanceof com.github.javaparser.ast.expr.ObjectCreationExpr
                        && children.get(i) instanceof BodyDeclaration<?>) continue;
                pending.push(new Pending(children.get(i), next.depth() + 1, nesting));
            }
        }
        return new Scan(List.copyOf(result), truncated);
    }
    static boolean isControl(Node node) {
        return node instanceof IfStmt || isLoop(node) || node instanceof SwitchStmt
                || node instanceof com.github.javaparser.ast.expr.SwitchExpr || node instanceof TryStmt;
    }
    static boolean isLoop(Node node) {
        return node instanceof ForStmt || node instanceof ForEachStmt || node instanceof WhileStmt || node instanceof DoStmt;
    }
    static SourceRange range(Node node) {
        return node.getRange().map(r -> new SourceRange(r.begin.line, r.begin.column, r.end.line, r.end.column))
                .orElse(new SourceRange(1, 1, 1, 1));
    }
    static long start(SourceRange range) { return ((long) range.startLine() << 32) + range.startColumn(); }
    static long end(SourceRange range) { return ((long) range.endLine() << 32) + range.endColumn(); }
    static boolean contains(SourceRange outer, SourceRange inner) {
        return start(outer) <= start(inner) && end(outer) >= end(inner);
    }
    static String key(Node node) { return range(node).startLine() + ":" + range(node).startColumn(); }
}
