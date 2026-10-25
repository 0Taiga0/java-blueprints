package dev.blueprints.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.stmt.*;
import dev.blueprints.roundtrip.SourceException;
import org.springframework.http.HttpStatus;
import static dev.blueprints.flow.FlowModels.*;

/** Один редактируемый блок соответствует одному узлу AST, а не подписи на экране. */
public final class EditableCode {
    private EditableCode() {}

    public static EditTarget describe(Node node) {
        if (node == null) return null;
        String kind = null;
        if (node instanceof ExpressionStmt) kind = "statement";
        else if (node instanceof ReturnStmt) kind = "return";
        else if (node instanceof ThrowStmt) kind = "throw";
        else if (node instanceof Expression) {
            Node parent = node.getParentNode().orElse(null);
            if (parent instanceof com.github.javaparser.ast.nodeTypes.SwitchNode sw && sw.getSelector() == node) kind = "selector";
            else if (parent instanceof IfStmt || parent instanceof WhileStmt || parent instanceof DoStmt) kind = "condition";
            else if (parent instanceof ForEachStmt loop && loop.getIterable() == node) kind = "iterable";
            else if (parent instanceof ForStmt loop) {
                if (loop.getCompare().orElse(null) == node) kind = "condition";
                else if (loop.getInitialization().stream().anyMatch(n -> n == node)) kind = "for-init";
                else if (loop.getUpdate().stream().anyMatch(n -> n == node)) kind = "for-update";
            }
        }
        if (kind == null) return null;
        var r = node.getRange().orElseThrow();
        return new EditTarget(kind, node.getTokenRange().map(Object::toString).orElseGet(node::toString),
                new SourceRange(r.begin.line, r.begin.column, r.end.line, r.end.column));
    }

    public static Node parseReplacement(JavaParser parser, String kind, String text) {
        // Парсим целый фрагмент; несколько операторов вместо одного не принимаются.
        if (kind.equals("condition") || kind.equals("iterable") || kind.equals("selector")) return require(parser.parseExpression(text));
        if (kind.equals("for-init") || kind.equals("for-update")) {
            String header = kind.equals("for-init") ? text + ";true;" : ";;" + text;
            Statement parsed = require(parser.parseStatement("for (" + header + ") {}"));
            if (!(parsed instanceof ForStmt loop) || !loop.getBody().isBlockStmt()
                    || !loop.getBody().asBlockStmt().isEmpty()) throw invalid("Измените только одну часть заголовка for.");
            var expressions = kind.equals("for-init") ? loop.getInitialization() : loop.getUpdate();
            if (expressions.size() != 1 || (kind.equals("for-init") && (!loop.getUpdate().isEmpty()
                    || !loop.getCompare().map(n -> n.toString().equals("true")).orElse(false)))
                    || (kind.equals("for-update") && (!loop.getInitialization().isEmpty() || loop.getCompare().isPresent())))
                throw invalid("В блоке for должно быть одно выражение, без разделителей ;.");
            return expressions.get(0).clone();
        }
        Statement parsed = require(parser.parseStatement(text));
        boolean matches = switch (kind) {
            case "statement" -> parsed instanceof ExpressionStmt;
            case "return" -> parsed instanceof ReturnStmt;
            case "throw" -> parsed instanceof ThrowStmt;
            default -> false;
        };
        if (!matches) throw invalid("Сохраните вид блока: действие, return или throw. Условия и циклы нельзя вставлять вместо действия.");
        return parsed;
    }

    private static <T> T require(ParseResult<T> parsed) {
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            String detail = parsed.getProblems().stream().findFirst().map(p -> p.getMessage()).orElse("Неполный фрагмент.");
            if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
            throw invalid("Ошибка в тексте блока. " + detail);
        }
        return parsed.getResult().orElseThrow();
    }

    static SourceException invalid(String message) {
        return new SourceException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
