package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import java.util.*;
import java.util.regex.Pattern;

/** Только подсказка по синтаксису; не подменяет разрешение типов компилятором. */
final class CodeCategory {
    private final Map<String,String> variableTypes = new HashMap<>();
    CodeCategory(Node root) {
        root.findAll(VariableDeclarator.class).forEach(v -> variableTypes.put(v.getNameAsString(),v.getTypeAsString()));
        root.findAll(Parameter.class).forEach(v -> variableTypes.put(v.getNameAsString(),v.getTypeAsString()));
    }
    String of(Node node, String text) {
        if (text.contains("System.in") || typedReceiver(text,"Scanner")) return "input";
        if (text.contains("System.out") || text.contains("System.err") || typedReceiver(text,"Formatter")
                || text.contains("String.format") || text.contains("StandardCharsets")) return "io";
        if (Pattern.compile("\\b(Collection|Iterable|List|Set|Map|ArrayList|LinkedList|HashSet|TreeSet|HashMap|TreeMap|Iterator|Collections|Arrays|Comparator|Optional)\\b").matcher(text).find()) return "collections";
        if (variableTypes.entrySet().stream().anyMatch(e -> e.getValue().matches(".*\\b(List|Set|Map|Collection|Iterable|Iterator|Optional|ArrayList|LinkedList|HashSet|TreeSet|HashMap|TreeMap)\\b.*")
                && Pattern.compile("\\b"+Pattern.quote(e.getKey())+"\\s*\\.").matcher(text).find())) return "collections";
        if (node instanceof ExpressionStmt e && e.getExpression().isVariableDeclarationExpr()) return "variable";
        if (text.contains("Math.") || text.contains("String.") || text.contains("Object.")) return "java.lang";
        return "";
    }
    private boolean typedReceiver(String text,String type) {
        return text.contains("new "+type) || variableTypes.entrySet().stream()
                .anyMatch(e -> e.getValue().contains(type) && Pattern.compile("\\b"+Pattern.quote(e.getKey())+"\\b").matcher(text).find());
    }
}
