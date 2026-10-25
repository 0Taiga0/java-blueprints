package dev.blueprints.optimization;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import java.util.*;

/** Узкое подмножество выражений, чтение которых не меняет состояние и не бросает исключений. */
final class SafeCondition {
    private enum Kind { BOOLEAN, INTEGRAL, FLOATING }
    private record Value(Kind kind, String reason) {
        static Value no(String reason) { return new Value(null, reason); }
        static Value yes(Kind kind) { return new Value(kind, ""); }
    }
    record Result(boolean safe, String reason) {}
    private final Map<String, Kind> parameters = new HashMap<>();
    private final Set<String> shadowed = new HashSet<>();
    private int visited;

    Result check(Expression condition) {
        Node context = condition;
        CallableDeclaration<?> callable = null;
        while ((context = context.getParentNode().orElse(null)) != null) {
            if (context instanceof LambdaExpr || context instanceof InitializerDeclaration
                    || context instanceof TypeDeclaration<?> || context instanceof ObjectCreationExpr)
                break;
            if (context instanceof CallableDeclaration<?> declaration) { callable = declaration; break; }
        }
        if (callable != null) {
            for (Parameter parameter : callable.getParameters()) {
                Kind type = parameter.isVarArgs() ? null : primitive(parameter.getType());
                String name = parameter.getNameAsString();
                if (parameters.containsKey(name)) shadowed.add(name);
                parameters.put(name, type);
            }
            // Консервативный отказ даже для одноимённого объявления во вложенном классе.
            for (VariableDeclarator variable : callable.findAll(VariableDeclarator.class))
                if (parameters.containsKey(variable.getNameAsString())) shadowed.add(variable.getNameAsString());
            for (Parameter parameter : callable.findAll(Parameter.class))
                if (callable.getParameters().stream().noneMatch(p -> p == parameter)
                        && parameters.containsKey(parameter.getNameAsString())) shadowed.add(parameter.getNameAsString());
        }
        Value value = type(condition, 0);
        if (value.kind() == Kind.BOOLEAN) return new Result(true,
                "Условие использует только безопасные операции над примитивными параметрами и литералами.");
        return new Result(false, value.reason().isBlank() ? "Условие не принадлежит поддерживаемому логическому подмножеству." : value.reason());
    }

    private Value type(Expression expression, int depth) {
        if (++visited > 1000 || depth > 60) return Value.no("Условие слишком большое для ограниченной проверки безопасности.");
        if (expression instanceof EnclosedExpr enclosed) return type(enclosed.getInner(), depth + 1);
        if (expression instanceof BooleanLiteralExpr) return Value.yes(Kind.BOOLEAN);
        if (expression instanceof IntegerLiteralExpr || expression instanceof LongLiteralExpr || expression instanceof CharLiteralExpr)
            return Value.yes(Kind.INTEGRAL);
        if (expression instanceof DoubleLiteralExpr) return Value.yes(Kind.FLOATING);
        if (expression instanceof NameExpr name) {
            if (shadowed.contains(name.getNameAsString())) return Value.no("Имя «" + name + "» затенено или объявлено повторно; безопасно разрешить его здесь нельзя.");
            Kind result = parameters.get(name.getNameAsString());
            return result == null ? Value.no("Имя «" + name + "» не является однозначным примитивным параметром текущего метода или конструктора.") : Value.yes(result);
        }
        if (expression instanceof UnaryExpr unary) {
            Value inner = type(unary.getExpression(), depth + 1);
            if (inner.kind() == null) return inner;
            return switch (unary.getOperator()) {
                case LOGICAL_COMPLEMENT -> inner.kind() == Kind.BOOLEAN ? inner : Value.no("Для ! требуется примитивное логическое значение.");
                case PLUS, MINUS -> numeric(inner.kind()) ? inner : Value.no("Для арифметики требуется числовой примитив.");
                case BITWISE_COMPLEMENT -> inner.kind() == Kind.INTEGRAL ? inner : Value.no("Для ~ требуется целочисленный примитив.");
                default -> Value.no("Условие изменяет значение (++ или --), поэтому удалять его нельзя.");
            };
        }
        if (expression instanceof BinaryExpr binary) {
            if (binary.getOperator() == BinaryExpr.Operator.DIVIDE || binary.getOperator() == BinaryExpr.Operator.REMAINDER)
                return Value.no("Деление и остаток исключены: вычисление условия может выбросить исключение.");
            Value left = type(binary.getLeft(), depth + 1), right = type(binary.getRight(), depth + 1);
            if (left.kind() == null) return left;
            if (right.kind() == null) return right;
            boolean numbers = numeric(left.kind()) && numeric(right.kind());
            boolean booleans = left.kind() == Kind.BOOLEAN && right.kind() == Kind.BOOLEAN;
            boolean integers = left.kind() == Kind.INTEGRAL && right.kind() == Kind.INTEGRAL;
            Kind promoted = left.kind() == Kind.FLOATING || right.kind() == Kind.FLOATING ? Kind.FLOATING : Kind.INTEGRAL;
            return switch (binary.getOperator()) {
                case OR, AND -> booleans ? Value.yes(Kind.BOOLEAN) : Value.no("Логические операнды не являются примитивными boolean.");
                case EQUALS, NOT_EQUALS -> numbers || booleans ? Value.yes(Kind.BOOLEAN) : Value.no("Типы сравнения не разрешены.");
                case LESS, LESS_EQUALS, GREATER, GREATER_EQUALS -> numbers ? Value.yes(Kind.BOOLEAN) : Value.no("Для сравнения нужны числовые примитивы.");
                case PLUS, MINUS, MULTIPLY -> numbers ? Value.yes(promoted) : Value.no("Арифметика может оказаться строковой или объектной.");
                case BINARY_AND, BINARY_OR, XOR -> integers ? Value.yes(Kind.INTEGRAL) : booleans ? Value.yes(Kind.BOOLEAN) : Value.no("Побитовые операнды имеют неподдержанный тип.");
                case LEFT_SHIFT, SIGNED_RIGHT_SHIFT, UNSIGNED_RIGHT_SHIFT -> integers ? Value.yes(Kind.INTEGRAL) : Value.no("Для сдвига нужны целочисленные примитивы.");
                default -> Value.no("Оператор не поддерживается безопасным удалением условия.");
            };
        }
        if (expression instanceof CastExpr cast) {
            Kind destination = primitive(cast.getType());
            Value value = type(cast.getExpression(), depth + 1);
            if (value.kind() == null) return value;
            return destination != null && (numeric(destination) && numeric(value.kind()) || destination == Kind.BOOLEAN && value.kind() == Kind.BOOLEAN)
                    ? Value.yes(destination) : Value.no("Приведение может обращаться к объекту или менять тип неподдержанным способом.");
        }
        if (expression instanceof ConditionalExpr conditional) {
            Value condition = type(conditional.getCondition(), depth + 1);
            Value yes = type(conditional.getThenExpr(), depth + 1), no = type(conditional.getElseExpr(), depth + 1);
            if (condition.kind() != Kind.BOOLEAN) return Value.no("Условие ?: не распознано как безопасное.");
            if (yes.kind() == null) return yes;
            if (no.kind() == null) return no;
            if (yes.kind() == no.kind()) return yes;
            return numeric(yes.kind()) && numeric(no.kind()) ? Value.yes(Kind.FLOATING) : Value.no("Ветви ?: имеют неподдержанные типы.");
        }
        return Value.no("Условие содержит вызов, поле, массив, объект, присваивание или другую неподдержанную операцию; удалять его автоматически нельзя.");
    }
    private static boolean numeric(Kind kind) { return kind == Kind.INTEGRAL || kind == Kind.FLOATING; }
    private static Kind primitive(com.github.javaparser.ast.type.Type type) {
        if (!type.isPrimitiveType()) return null;
        return switch (type.asPrimitiveType().getType()) {
            case BOOLEAN -> Kind.BOOLEAN;
            case FLOAT, DOUBLE -> Kind.FLOATING;
            default -> Kind.INTEGRAL;
        };
    }
}
