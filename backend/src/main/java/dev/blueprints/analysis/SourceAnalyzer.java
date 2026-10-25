package dev.blueprints.analysis;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.NoCommentEqualsVisitor;
import com.github.javaparser.ast.visitor.NoCommentHashCodeVisitor;
import dev.blueprints.flow.FlowModels.*;
import java.util.*;
import static dev.blueprints.analysis.AnalysisModels.*;

/** Читающий анализатор: рекомендации не меняют программу и не объявляют её ошибочной. */
public final class SourceAnalyzer {
    private static final int MAX_FINDINGS = 200;
    private final List<Finding> findings = new ArrayList<>();
    private boolean truncated;

    public Report analyze(CompilationUnit unit, List<MethodFlow> flows) {
        // Экземпляр допустимо использовать повторно, но он не является общим состоянием сервера.
        findings.clear(); truncated = false;
        Map<String, Node> declarations = declarations(unit);
        List<MethodAnalysis> methods = new ArrayList<>();
        for (MethodFlow flow : flows) {
            Node declaration = declarations.get(flow.range().startLine() + ":" + flow.range().startColumn());
            if (declaration == null) { truncated = true; continue; }
            BlockStmt body = AnalysisAst.body(declaration);
            AnalysisAst.Scan scan = body == null ? new AnalysisAst.Scan(List.of(), false) : AnalysisAst.scan(body);
            truncated |= scan.truncated();
            int statements = (int) scan.items().stream().map(AnalysisAst.Item::node)
                    .filter(n -> n instanceof Statement && !(n instanceof BlockStmt) && !(n instanceof EmptyStmt)
                            && !(n instanceof LocalClassDeclarationStmt) && !(n instanceof LocalRecordDeclarationStmt)).count();
            int nesting = scan.items().stream().mapToInt(AnalysisAst.Item::nesting).max().orElse(0);
            if (statements > 40) add(flow, declaration, "LONG_METHOD", "warning", "Большой метод",
                    "В методе " + statements + " операторов. Порог этой проверки — больше 40.",
                    "Разделите самостоятельные задачи на небольшие методы с понятными именами. После изменения сравните поведение тестами.");
            if (nesting >= 4) {
                Node deepest = scan.items().stream().filter(i -> i.nesting() >= 4 && AnalysisAst.isControl(i.node()))
                        .map(AnalysisAst.Item::node).findFirst().orElse(declaration);
                add(flow, anchor(deepest), "DEEP_NESTING", "warning", "Глубокая вложенность",
                        "Здесь начинается четвёртый вложенный уровень управления. Максимум в методе: " + nesting + ".",
                        "Проверьте, можно ли вынести отдельную задачу в метод или применить ранний return. Сначала убедитесь, что порядок действий сохранится.");
            }
            for (var item : scan.items()) {
                if (item.node() instanceof CatchClause catcher && empty(catcher.getBody()))
                    add(flow, catcher.getParameter(), "EMPTY_CATCH", "warning", "Пустой обработчик исключения",
                            "Исключение поймано, но catch не выполняет действий. Причина сбоя может остаться незаметной.",
                            "Уточните намерение: обработайте ситуацию, сохраните причину в журнале или передайте исключение дальше. Пустой catch иногда допустим, поэтому это предупреждение.");
                if (!scan.truncated() && item.node() instanceof IfStmt branch && branch.getElseStmt().isPresent()
                        && NoCommentEqualsVisitor.equals(unblock(branch.getThenStmt()), unblock(branch.getElseStmt().get())))
                    add(flow, branch.getCondition(), "IDENTICAL_BRANCHES", "warning", "Одинаковые ветки условия",
                            "Ветви «да» и «нет» содержат одинаковые действия; комментарии при сравнении не учитываются.",
                            "Возможно, условие лишнее или в одной ветви забыто другое действие. Не удаляйте его вслепую: вычисление условия может иметь побочные эффекты или выбрасывать исключение.");
            }
            if (!scan.truncated()) duplicates(flow, scan);
            var estimate = new ComplexityEstimator().estimate(declaration, scan);
            truncated |= estimate.truncated();
            if (!estimate.costlyLoops().isEmpty()) {
                Node loop = estimate.costlyLoops().getLast();
                add(flow, loop, "NESTED_TRAVERSAL", "warning", "Вложенные обходы данных",
                        (estimate.complexity().loopEstimate() == null
                                ? "Распознан участок с вложенными обходами. Для всех обходов метода общая оценка неизвестна."
                                : "Для структуры обходов получена верхняя оценка " + estimate.complexity().loopEstimate() + ".")
                                + " Работа этого участка может быстро расти вместе с размером данных.",
                        "Проверьте, можно ли избежать повторного поиска: например, подготовить индекс или множество. Вложенные циклы могут быть необходимы; сохраняйте порядок, повторы и результат и проверяйте тестами.");
            }
            if (!estimate.unclearLoops().isEmpty())
                add(flow, estimate.unclearLoops().getFirst(), "UNKNOWN_LOOP_BOUND", "info", "Граница цикла не определена",
                        "Для одного или нескольких циклов не удалось надёжно оценить число повторений.",
                        "Посмотрите условие, начальное значение и шаг. Панель не считает этот цикл бесконечным: ей просто недостаточно сведений для Big O.");
            methods.add(new MethodAnalysis(flow.id(), flow.signature(), flow.range(), statements, nesting, estimate.complexity()));
        }
        return new Report(List.copyOf(findings), List.copyOf(methods), List.of(
                "Это ограниченный статический анализ, а не доказательство отсутствия ошибок. Пороговые предупреждения требуют решения человека.",
                "Оценка Big O — условная верхняя граница для распознанных циклов. Стоимость библиотечных и пользовательских вызовов, динамических типов и скрытых побочных эффектов не выводится.",
                "Повторы ищутся среди трёх подряд идущих действий внутри одного метода. Похожие по смыслу фрагменты и переименованные переменные не сопоставляются.",
                "Не более 200 замечаний; на тело метода — до 20 000 узлов AST и 120 уровней. При достижении лимита отчёт помечается как неполный."),
                truncated);
    }

    private Map<String, Node> declarations(CompilationUnit unit) {
        Map<String, Node> result = new HashMap<>();
        Deque<Node> nodes = new ArrayDeque<>(); nodes.push(unit);
        int count = 0;
        while (!nodes.isEmpty()) {
            if (++count > 150000) { truncated = true; break; }
            Node node = nodes.pop();
            if (node instanceof MethodDeclaration || node instanceof ConstructorDeclaration || node instanceof InitializerDeclaration)
                result.put(AnalysisAst.key(node), node);
            for (Node child : node.getChildNodes()) nodes.push(child);
        }
        return result;
    }
    private static Node anchor(Node node) {
        if (node instanceof IfStmt i) return i.getCondition();
        if (node instanceof ForStmt f) return f.getCompare().<Node>map(x -> x).orElse(f);
        if (node instanceof ForEachStmt f) return f.getIterable();
        if (node instanceof WhileStmt w) return w.getCondition();
        if (node instanceof DoStmt d) return d.getCondition();
        if (node instanceof SwitchStmt s) return s.getSelector();
        return node;
    }
    private static Statement unblock(Statement statement) {
        if (statement instanceof BlockStmt block && block.getStatements().size() == 1) return block.getStatement(0);
        return statement;
    }
    private static boolean empty(BlockStmt block) {
        var scan = AnalysisAst.scan(block);
        return !scan.truncated() && scan.items().stream().map(AnalysisAst.Item::node).filter(Statement.class::isInstance)
                .allMatch(s -> s instanceof EmptyStmt || s instanceof BlockStmt);
    }
    private record Fragment(List<Statement> statements, int start, BlockStmt block) {}
    private void duplicates(MethodFlow flow, AnalysisAst.Scan scan) {
        Map<List<Integer>, List<Fragment>> seen = new HashMap<>();
        Set<Statement> reported = Collections.newSetFromMap(new IdentityHashMap<>());
        int windows = 0;
        for (var item : scan.items()) {
            if (!(item.node() instanceof BlockStmt block)) continue;
            var statements = block.getStatements();
            for (int i = 0; i + 2 < statements.size(); i++) {
                if (++windows > 1500) { truncated = true; return; }
                List<Statement> triple = List.of(statements.get(i), statements.get(i + 1), statements.get(i + 2));
                if (!triple.stream().allMatch(ExpressionStmt.class::isInstance)) continue;
                // Хеш и окончательное сравнение идут по AST: строки, содержащие //, не портятся.
                List<Integer> key = triple.stream().map(NoCommentHashCodeVisitor::hashCode).toList();
                List<Fragment> candidates = seen.computeIfAbsent(key, ignored -> new ArrayList<>());
                int currentIndex = i;
                Fragment match = candidates.stream().filter(f -> f.block() != block || Math.abs(currentIndex - f.start()) >= 3)
                        .filter(f -> equal(f.statements(), triple)).findFirst().orElse(null);
                if (match != null && !reported.contains(triple.getFirst())) {
                    Node first = triple.getFirst();
                    add(flow, first, "DUPLICATE_SEQUENCE", "info", "Повторяются три действия",
                            "Эти три последовательных действия совпадают с фрагментом на строке "
                                    + AnalysisAst.range(match.statements().getFirst()).startLine() + ".",
                            "Проверьте, являются ли фрагменты одной задачей. Если да, можно выделить метод; учитывайте доступные переменные и побочные эффекты.");
                    reported.addAll(triple);
                }
                // Храним лишь несколько первых непересекающихся представителей одного хеша.
                if (candidates.size() < 4) candidates.add(new Fragment(triple, i, block));
            }
        }
    }
    private static boolean equal(List<Statement> a, List<Statement> b) {
        for (int i = 0; i < 3; i++) if (!NoCommentEqualsVisitor.equals(a.get(i), b.get(i))) return false;
        return true;
    }
    private void add(MethodFlow flow, Node anchor, String rule, String severity, String title, String message, String recommendation) {
        if (findings.size() >= MAX_FINDINGS) { truncated = true; return; }
        SourceRange range = AnalysisAst.range(anchor);
        findings.add(new Finding("finding-" + (findings.size() + 1), rule, severity, title, message, recommendation,
                flow.id(), range, nodeIds(flow, range)));
    }
    /** Выбираем все копии самого узкого подходящего узла, в том числе копии finally. */
    private static List<String> nodeIds(MethodFlow flow, SourceRange range) {
        if (flow.nodes().isEmpty()) return List.of();
        if (range.equals(flow.range())) {
            var starts = flow.nodes().stream().filter(n -> n.kind().equals("start")).map(FlowNode::id).toList();
            if (!starts.isEmpty()) return starts;
        }
        List<FlowNode> exact = flow.nodes().stream().filter(n -> n.range().equals(range)).toList();
        if (!exact.isEmpty()) return exact.stream().map(FlowNode::id).toList();
        List<FlowNode> containing = flow.nodes().stream().filter(n -> AnalysisAst.contains(n.range(), range)).toList();
        if (!containing.isEmpty()) {
            long minimum = containing.stream().mapToLong(n -> span(n.range())).min().orElseThrow();
            return containing.stream().filter(n -> span(n.range()) == minimum).map(FlowNode::id).toList();
        }
        List<FlowNode> contained = flow.nodes().stream().filter(n -> AnalysisAst.contains(range, n.range())
                && !n.kind().equals("end") && !n.kind().equals("exception-end")).toList();
        if (!contained.isEmpty()) return contained.stream().map(FlowNode::id).toList();
        return flow.nodes().stream().filter(n -> n.kind().equals("opaque") || n.kind().equals("start"))
                .map(FlowNode::id).limit(1).toList();
    }
    private static long span(SourceRange range) {
        return (long) (range.endLine() - range.startLine()) * 100000 + range.endColumn() - range.startColumn();
    }
}
