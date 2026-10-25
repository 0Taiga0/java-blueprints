package dev.blueprints.analysis;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import java.util.*;
import static dev.blueprints.analysis.AnalysisModels.*;

/**
 * Консервативная оценка верхней границы по структуре программы.
 * Здесь нет вывода типов и доказательства завершения произвольной Java-программы.
 */
final class ComplexityEstimator {
    private final LinkedHashMap<String, Variable> variables = new LinkedHashMap<>();
    private final LinkedHashSet<String> unknown = new LinkedHashSet<>();
    private final List<Node> costlyLoops = new ArrayList<>();
    private final List<Node> unclearLoops = new ArrayList<>();
    private int visited;
    private boolean limited;
    private record Bound(String key, String meaning, boolean constant) {}
    private record Step(boolean increasing, boolean logarithmic) {}
    record Result(Complexity complexity, List<Node> costlyLoops, List<Node> unclearLoops, boolean truncated) {}

    Result estimate(Node declaration, AnalysisAst.Scan scan) {
        BlockStmt body = AnalysisAst.body(declaration);
        if (body == null) return new Result(new Complexity(null, null,
                "У объявления нет тела: оценивать здесь нечего.", List.of(), List.of(),
                List.of("Реализация метода находится в другом месте.")), List.of(), List.of(), false);
        Cost traversal = cost(body, new HashSet<>(), 0);
        if (scan.truncated()) { traversal = null; limited = true; unknown.add("Достигнут лимит анализа AST."); }
        boolean unknownOperations = atomicUncertainty(declaration, scan);
        if (limited) unknown.add("Оценка остановлена по лимиту глубины или размера выражения сложности.");
        String loop = traversal == null ? null : traversal.format();
        String method = traversal != null && !unknownOperations && !limited ? loop : null;
        String explanation = traversal == null
                ? "Не удалось оценить число проходов: хотя бы один цикл или его граница не распознаны."
                : "Оценочная верхняя граница по обходам. Последовательные участки складываются, вложенные перемножаются; постоянные множители опускаются.";
        if (loop != null && method == null)
            explanation += " Стоимость отдельных операций неизвестна, поэтому это ещё не сложность всего метода.";
        if (method != null)
            explanation += " Для всего метода результат действует при перечисленных допущениях.";
        return new Result(new Complexity(loop, method, explanation, List.copyOf(variables.values()),
                List.of("n, m и следующие символы обозначают размеры входных данных; каждый размер не меньше 1.",
                        "Границы циклов остаются постоянными во время обхода; скрытые побочные эффекты и изменение через другие ссылки не учитываются.",
                        "Арифметика над примитивами и доступ к массиву стоят O(1); переполнения счётчиков нет.",
                        "Это оценка структуры кода, а не замер времени и не доказательство эквивалентности программы."),
                List.copyOf(unknown)), List.copyOf(costlyLoops), List.copyOf(unclearLoops), limited);
    }

    private Cost cost(Node node, Set<String> counters, int depth) {
        if (++visited > 20000 || depth > 100) { limited = true; return null; }
        if (AnalysisAst.boundary(node)) return Cost.one();
        if (node instanceof ForStmt loop) {
            Cost factor = forFactor(loop, counters);
            Set<String> nested = new HashSet<>(counters);
            counter(loop).ifPresent(nested::add);
            Cost inside = cost(loop.getBody(), nested, depth + 1);
            Cost result = multiply(factor, inside);
            if (result != null && result.hasQuadraticTerm()) costlyLoops.add(loop.getCompare().<Node>map(x -> x).orElse(loop));
            return result;
        }
        if (node instanceof ForEachStmt loop) {
            Bound bound = bound(loop.getIterable(), true, counters);
            Cost factor = bound == null ? unknownLoop(loop, "Для for-each не распознан размер перебираемых данных.")
                    : modified(loop.getBody(), names(loop.getIterable()), true)
                    ? unknownLoop(loop, "Перебираемые данные изменяются внутри for-each.") : factor(bound, false);
            Set<String> nested = new HashSet<>(counters);
            loop.getVariable().getVariables().forEach(v -> nested.add(v.getNameAsString()));
            Cost result = multiply(factor, cost(loop.getBody(), nested, depth + 1));
            if (result != null && result.hasQuadraticTerm()) costlyLoops.add(loop.getIterable());
            return result;
        }
        if (node instanceof WhileStmt loop) {
            if (isFalse(loop.getCondition())) return Cost.one();
            cost(loop.getBody(), counters, depth + 1);
            return unknownLoop(loop.getCondition(), "Для while не доказаны граница и число повторений.");
        }
        if (node instanceof DoStmt loop) {
            Cost inside = cost(loop.getBody(), counters, depth + 1);
            if (isFalse(loop.getCondition())) return inside;
            return unknownLoop(loop.getCondition(), "Для do/while не доказаны граница и число повторений.");
        }
        Cost total = Cost.one();
        for (Node child : node.getChildNodes()) {
            if (node instanceof ObjectCreationExpr && child instanceof BodyDeclaration<?>) continue;
            total = add(total, cost(child, counters, depth + 1));
        }
        return total;
    }

    private Cost forFactor(ForStmt loop, Set<String> outerCounters) {
        Optional<String> counter = counter(loop);
        if (counter.isEmpty() || loop.getCompare().isEmpty() || loop.getUpdate().size() != 1)
            return unknownLoop(loop.getCompare().<Node>map(x -> x).orElse(loop), "Нестандартные начальное значение, условие или шаг for.");
        String name = counter.get();
        var declaration = loop.getInitialization().get(0).asVariableDeclarationExpr().getVariable(0);
        if (!declaration.getType().isPrimitiveType()
                || !Set.of("int", "long").contains(declaration.getTypeAsString()))
            return unknownLoop(loop, "Для оценки for нужен целочисленный счётчик int или long.");
        Expression initial = declaration.getInitializer().orElseThrow();
        Expression expression = unwrap(loop.getCompare().get());
        if (!(expression instanceof BinaryExpr compare) || !isName(compare.getLeft(), name))
            return unknownLoop(expression, "Условие for должно сравнивать один счётчик с понятной границей.");
        Step step = step(loop.getUpdate().get(0), name);
        if (step == null) return unknownLoop(expression, "Шаг for не является положительным постоянным шагом или множителем.");
        boolean up = compare.getOperator() == BinaryExpr.Operator.LESS || compare.getOperator() == BinaryExpr.Operator.LESS_EQUALS;
        boolean down = compare.getOperator() == BinaryExpr.Operator.GREATER || compare.getOperator() == BinaryExpr.Operator.GREATER_EQUALS;
        if ((!up && !down) || up != step.increasing())
            return unknownLoop(expression, "Направление шага for не совпадает с направлением сравнения.");
        Expression size;
        Long initialNumber = number(initial);
        Long endNumber = number(compare.getRight());
        if (up) {
            if (initialNumber == null || initialNumber < 0 || (step.logarithmic() && initialNumber == 0))
                return unknownLoop(expression, "Начало возрастающего for должно быть постоянным неотрицательным числом (для умножения — положительным).");
            size = compare.getRight();
        } else {
            if (endNumber == null || endNumber < 0
                    || (step.logarithmic() && compare.getOperator() == BinaryExpr.Operator.GREATER_EQUALS && endNumber == 0))
                return unknownLoop(expression, "Конец убывающего for не распознан или деление может остановиться на нуле.");
            size = initial;
        }
        Bound bound = bound(size, false, outerCounters);
        if (bound == null) return unknownLoop(expression, "Граница for сложная или зависит от внешнего счётчика.");
        Set<String> protectedNames = new HashSet<>(names(size)); protectedNames.add(name);
        if (modified(loop.getBody(), protectedNames, true))
            return unknownLoop(expression, "Внутри for меняется счётчик, граница или размер перебираемой коллекции.");
        return factor(bound, step.logarithmic());
    }
    private Optional<String> counter(ForStmt loop) {
        if (loop.getInitialization().size() != 1 || !(loop.getInitialization().get(0) instanceof VariableDeclarationExpr declaration)
                || declaration.getVariables().size() != 1 || declaration.getVariable(0).getInitializer().isEmpty()) return Optional.empty();
        return Optional.of(declaration.getVariable(0).getNameAsString());
    }
    private Step step(Expression expression, String name) {
        if (expression instanceof UnaryExpr unary && isName(unary.getExpression(), name)) {
            return switch (unary.getOperator()) {
                case POSTFIX_INCREMENT, PREFIX_INCREMENT -> new Step(true, false);
                case POSTFIX_DECREMENT, PREFIX_DECREMENT -> new Step(false, false);
                default -> null;
            };
        }
        if (expression instanceof AssignExpr assign && isName(assign.getTarget(), name)) {
            Long value = number(assign.getValue());
            if (value == null || value <= 0) return null;
            return switch (assign.getOperator()) {
                case PLUS -> new Step(true, false);
                case MINUS -> new Step(false, false);
                case MULTIPLY -> value > 1 ? new Step(true, true) : null;
                case DIVIDE -> value > 1 ? new Step(false, true) : null;
                default -> null;
            };
        }
        return null;
    }
    private Bound bound(Expression expression, boolean iterable, Set<String> counters) {
        expression = unwrap(expression);
        if (!Collections.disjoint(names(expression), counters)) return null;
        if (number(expression) != null && !iterable) return new Bound("", "", true);
        if (expression instanceof NameExpr name)
            return new Bound((iterable ? "size:" : "value:") + name.getNameAsString(),
                    (iterable ? "число элементов " : "значение ") + name.getNameAsString(), false);
        if (!iterable && expression instanceof FieldAccessExpr field && field.getNameAsString().equals("length")
                && field.getScope() instanceof NameExpr name)
            return new Bound("size:" + name.getNameAsString(), "число элементов " + name.getNameAsString(), false);
        if (!iterable && expression instanceof MethodCallExpr call && call.getNameAsString().equals("size")
                && call.getArguments().isEmpty() && call.getScope().orElse(null) instanceof NameExpr name)
            return new Bound("size:" + name.getNameAsString(), "число элементов " + name.getNameAsString(), false);
        return null;
    }
    private Cost factor(Bound bound, boolean logarithmic) {
        if (bound.constant()) return Cost.one();
        Variable variable = variables.computeIfAbsent(bound.key(), key -> {
            String[] symbols = {"n", "m", "k", "p", "q", "r", "s", "t"};
            int index = variables.size();
            return new Variable(index < symbols.length ? symbols[index] : "n" + (index + 1), bound.meaning());
        });
        return Cost.factor(variable.symbol(), logarithmic);
    }
    private Cost unknownLoop(Node anchor, String reason) {
        if (unclearLoops.size() < 30) unclearLoops.add(anchor);
        unknown.add(reason + " Строка " + AnalysisAst.range(anchor).startLine() + ".");
        return null;
    }
    private Cost add(Cost a, Cost b) {
        if (a == null || b == null) return null;
        Cost result = a.add(b);
        if (result.terms.size() > 32) { limited = true; return null; }
        return result;
    }
    private Cost multiply(Cost a, Cost b) {
        if (a == null || b == null) return null;
        if (a.terms.size() * b.terms.size() > 64) { limited = true; return null; }
        Cost result = a.multiply(b);
        if (result.terms.size() > 32) { limited = true; return null; }
        return result;
    }
    private static boolean modified(Node body, Set<String> names, boolean collections) {
        AnalysisAst.Scan scan = AnalysisAst.scan(body);
        if (scan.truncated()) return true;
        Set<String> mutators = Set.of("add", "addAll", "remove", "removeAll", "removeIf", "clear", "retainAll",
                "put", "putAll", "compute", "computeIfAbsent", "computeIfPresent", "merge", "poll", "offer", "push", "pop");
        for (var item : scan.items()) {
            Node node = item.node();
            if (node instanceof AssignExpr assign && targetName(assign.getTarget(), names)) return true;
            if (node instanceof UnaryExpr unary && Set.of(UnaryExpr.Operator.POSTFIX_INCREMENT, UnaryExpr.Operator.PREFIX_INCREMENT,
                    UnaryExpr.Operator.POSTFIX_DECREMENT, UnaryExpr.Operator.PREFIX_DECREMENT).contains(unary.getOperator())
                    && targetName(unary.getExpression(), names)) return true;
            if (collections && node instanceof MethodCallExpr call && mutators.contains(call.getNameAsString())
                    && call.getScope().filter(s -> !Collections.disjoint(names(s), names)).isPresent()) return true;
        }
        return false;
    }
    private static boolean targetName(Expression target, Set<String> names) {
        return target instanceof NameExpr name && names.contains(name.getNameAsString())
                || target instanceof FieldAccessExpr field && names.contains(field.getNameAsString());
    }
    private static Set<String> names(Node node) {
        Set<String> result = new HashSet<>();
        for (var item : AnalysisAst.scan(node).items())
            if (item.node() instanceof NameExpr name) result.add(name.getNameAsString());
        return result;
    }
    private boolean atomicUncertainty(Node declaration, AnalysisAst.Scan scan) {
        boolean uncertain = false;
        for (var item : scan.items()) {
            Node node = item.node();
            if (node instanceof MethodCallExpr) {
                unknown.add("Стоимость вызовов методов не выводится без анализа их реализации (включая рекурсию, сортировки и библиотечные вызовы).");
                uncertain = true;
            }
            if (node instanceof ObjectCreationExpr || node instanceof ArrayCreationExpr) {
                unknown.add("Стоимость создания объектов и массивов здесь не оценивается.");
                uncertain = true;
            }
            if (node instanceof LambdaExpr || node instanceof MethodReferenceExpr) {
                unknown.add("Лямбды и ссылки на методы анализируются без вычисления стоимости их выполнения.");
                uncertain = true;
            }
            if (node instanceof StringLiteralExpr || node instanceof TextBlockLiteralExpr) {
                unknown.add("Стоимость строковых операций здесь не выводится.");
                uncertain = true;
            }
            if (node instanceof VariableDeclarator variable && !primitive(variable.getType())) {
                unknown.add("Операции с объектами и коллекциями требуют сведений о конкретных типах и реализациях.");
                uncertain = true;
            }
            if (node instanceof FieldAccessExpr access && !access.getNameAsString().equals("length")) {
                unknown.add("Операции с полями объектов требуют разрешения типов.");
                uncertain = true;
            }
        }
        if (declaration instanceof CallableDeclaration<?> callable) {
            if (callable.getParameters().stream().anyMatch(p -> !primitive(p.getType()))) {
                unknown.add("Для параметров объектных типов не определена стоимость их операций.");
                uncertain = true;
            }
            if (callable instanceof MethodDeclaration method && !method.getType().isVoidType() && !primitive(method.getType())) {
                unknown.add("Для результата объектного типа не определена стоимость операций.");
                uncertain = true;
            }
        }
        // Не выдаём O(1) для склейки строк из поля или неизвестного унаследованного поля.
        Set<String> locals = new HashSet<>();
        if (declaration instanceof CallableDeclaration<?> callable)
            callable.getParameters().forEach(p -> locals.add(p.getNameAsString()));
        scan.items().stream().map(AnalysisAst.Item::node).filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast).forEach(v -> locals.add(v.getNameAsString()));
        if (scan.items().stream().map(AnalysisAst.Item::node).filter(NameExpr.class::isInstance)
                .map(NameExpr.class::cast).anyMatch(n -> !locals.contains(n.getNameAsString()))) {
            unknown.add("Типы обращений к полям и внешним именам не разрешены.");
            uncertain = true;
        }
        return uncertain;
    }
    private static boolean primitive(com.github.javaparser.ast.type.Type type) {
        if (type.isPrimitiveType()) return true;
        return type.isArrayType() && primitive(type.asArrayType().getComponentType());
    }
    private static boolean isName(Expression expression, String name) {
        return unwrap(expression) instanceof NameExpr value && value.getNameAsString().equals(name);
    }
    private static Expression unwrap(Expression expression) {
        while (expression instanceof EnclosedExpr enclosed) expression = enclosed.getInner();
        return expression;
    }
    private static boolean isFalse(Expression expression) {
        return unwrap(expression) instanceof BooleanLiteralExpr value && !value.getValue();
    }
    private static Long number(Expression expression) {
        expression = unwrap(expression);
        try {
            if (expression instanceof IntegerLiteralExpr literal) return literal.asNumber().longValue();
            if (expression instanceof LongLiteralExpr literal) return literal.asNumber().longValue();
            if (expression instanceof UnaryExpr unary && unary.getOperator() == UnaryExpr.Operator.MINUS) {
                Long value = number(unary.getExpression()); return value == null ? null : -value;
            }
        } catch (NumberFormatException ignored) { /* Неизвестный числовой формат. */ }
        return null;
    }

    /** Полином верхней границы; одинаковые и доминируемые слагаемые отбрасываются. */
    private static final class Cost {
        private final List<Term> terms;
        private Cost(List<Term> source) {
            List<Term> result = new ArrayList<>();
            for (Term candidate : source) {
                if (result.stream().anyMatch(existing -> existing.dominates(candidate))) continue;
                result.removeIf(candidate::dominates);
                result.add(candidate);
            }
            terms = result;
        }
        static Cost one() { return new Cost(List.of(new Term(new TreeMap<>()))); }
        static Cost factor(String variable, boolean log) {
            TreeMap<String, Powers> powers = new TreeMap<>(); powers.put(variable, new Powers(log ? 0 : 1, log ? 1 : 0));
            return new Cost(List.of(new Term(powers)));
        }
        Cost add(Cost other) {
            List<Term> all = new ArrayList<>(terms); all.addAll(other.terms); return new Cost(all);
        }
        Cost multiply(Cost other) {
            List<Term> all = new ArrayList<>();
            for (Term a : terms) for (Term b : other.terms) all.add(a.multiply(b));
            return new Cost(all);
        }
        boolean hasQuadraticTerm() { return terms.stream().anyMatch(t -> t.powers.values().stream().mapToInt(Powers::linear).sum() >= 2); }
        String format() { return "O(" + String.join(" + ", terms.stream().map(Term::format).toList()) + ")"; }
    }
    private record Powers(int linear, int log) {}
    private record Term(SortedMap<String, Powers> powers) {
        boolean dominates(Term other) {
            for (var entry : other.powers.entrySet()) {
                Powers a = powers.getOrDefault(entry.getKey(), new Powers(0, 0)), b = entry.getValue();
                if (a.linear() < b.linear() || a.linear() == b.linear() && a.log() < b.log()) return false;
            }
            return true;
        }
        Term multiply(Term other) {
            TreeMap<String, Powers> result = new TreeMap<>(powers);
            other.powers.forEach((name, b) -> result.merge(name, b, (a, c) -> new Powers(a.linear() + c.linear(), a.log() + c.log())));
            return new Term(result);
        }
        String format() {
            if (powers.isEmpty()) return "1";
            List<String> parts = new ArrayList<>();
            powers.forEach((name, exponent) -> {
                if (exponent.linear() > 0) parts.add(power(name, exponent.linear()));
                if (exponent.log() > 0) parts.add(power("log " + name, exponent.log()));
            });
            return String.join(" · ", parts);
        }
        static String power(String name, int exponent) {
            return exponent == 1 ? name : name + switch (exponent) { case 2 -> "²"; case 3 -> "³"; default -> "^" + exponent; };
        }
    }
}
