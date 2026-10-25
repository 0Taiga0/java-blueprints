package dev.blueprints.optimization;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.comments.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.NoCommentEqualsVisitor;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import dev.blueprints.analysis.AnalysisModels.Finding;
import dev.blueprints.flow.FlowModels.*;
import dev.blueprints.roundtrip.RoundTripModels.Response;
import dev.blueprints.roundtrip.RoundTripService;
import dev.blueprints.roundtrip.SourceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static dev.blueprints.optimization.OptimizationModels.*;

/** Две локальные AST-правки. Сервер возвращает предложение и ничего не применяет сам. */
@Service
public class OptimizationService {
    private static final int MAX_CANDIDATES = 100;
    private final RoundTripService converter;
    public OptimizationService(RoundTripService converter) { this.converter = converter; }
    private record State(Response conversion, CompilationUnit unit) {}
    private record Choice(Candidate candidate, IfStmt target, IfStmt inner) {}
    private record Replacement(Statement statement, BlockStmt comments) {}

    public Options options(OptionsRequest request) {
        if (request == null) throw badRequest("Передайте исходный код и его версию.");
        State state = state(request.fileName(), request.source(), request.revision());
        return new Options(state.conversion().revision(), catalogue(state).stream().map(Choice::candidate).toList(),
                List.of("Доступны только две локальные правки: одинаковые ветви с безопасным условием и два вложенных if без else.",
                        "Примитивные параметры распознаются без разрешения внешних типов. Поля, массивы, вызовы, деление, остаток и неоднозначные имена запрещены при удалении условия.",
                        "Слияние if сохраняет порядок условий и короткое замыкание &&. Pattern matching пока не поддерживается.",
                        "Это упрощение структуры кода; улучшение Big O или скорости не обещается. Другие рекомендации выполняются вручную.",
                        "Каталог ограничен 100 предложениями. Предварительный просмотр не запускает программу, не запускает тесты и не изменяет исходный файл."));
    }

    public Preview preview(PreviewRequest request) {
        if (request == null || request.candidateId() == null || request.candidateId().isBlank())
            throw badRequest("Выберите предложение для предварительного просмотра.");
        State state = state(request.fileName(), request.source(), request.revision());
        Choice choice = catalogue(state).stream().filter(c -> c.candidate().id().equals(request.candidateId())).findFirst()
                .orElseThrow(() -> invalid("Предложение больше не найдено. Обновите список для текущего исходника."));
        if (!choice.candidate().available() || choice.target() == null)
            throw invalid("Автоматическая правка недоступна: " + choice.candidate().reason());
        IfStmt target = choice.target();
        String beforeFragment = target.toString();
        List<Comment> originalComments = comments(state.unit()).stream().map(Comment::clone).toList();
        Replacement replacement;
        if (choice.candidate().rule().equals("IDENTICAL_BRANCHES")) {
            // Сохраняем область видимости общей ветви и привязку внешних else/меток.
            BlockStmt block = asBlock(target.getThenStmt().clone());
            replacement = new Replacement(block, block);
        } else if (choice.candidate().rule().equals("MERGE_NESTED_IF") && choice.inner() != null) {
            BlockStmt body = asBlock(choice.inner().getThenStmt().clone());
            Expression condition = new BinaryExpr(new EnclosedExpr(target.getCondition().clone()),
                    new EnclosedExpr(choice.inner().getCondition().clone()), BinaryExpr.Operator.AND);
            IfStmt merged = new IfStmt(condition, body, null);
            replacement = new Replacement(merged, body);
        } else throw invalid("Эта рекомендация требует ручного изменения.");
        if (!target.replace(replacement.statement())) throw invalid("Не удалось заменить узел AST. Исходный код не изменён.");
        restoreMissingComments(state.unit(), originalComments, replacement.comments());
        List<Integer> replacementPath = path(state.unit(), replacement.statement());
        String proposed = new DefaultPrettyPrinter().print(state.unit()).replace("\r\n", "\n");
        if (!proposed.endsWith("\n")) proposed += "\n";
        var parsed = parser().parse(proposed);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()
                || !NoCommentEqualsVisitor.equals(state.unit(), parsed.getResult().orElseThrow()))
            throw invalid("Не удалось восстановить валидный AST после правки. Исходный код не изменён.");
        CompilationUnit reparsed = parsed.getResult().orElseThrow();
        if (!commentCounts(originalComments).equals(commentCounts(comments(reparsed))))
            throw invalid("Не удалось сохранить все комментарии и их количество. Выполните эту правку вручную.");
        Node newTarget = followPath(reparsed, replacementPath);
        if (!(newTarget instanceof Statement)) throw invalid("Не удалось найти обновлённый участок кода.");
        Node newAnchor = navigationAnchor(newTarget);
        Response conversion = converter.convert(state.conversion().fileName(), proposed);
        int methodIndex = -1;
        for (int i = 0; i < state.conversion().methods().size(); i++)
            if (state.conversion().methods().get(i).id().equals(choice.candidate().methodId())) { methodIndex = i; break; }
        if (methodIndex < 0 || methodIndex >= conversion.methods().size())
            throw invalid("Не удалось восстановить связь с методом после правки.");
        MethodFlow selected = conversion.methods().get(methodIndex);
        List<String> nodes = nodeIds(selected, range(newAnchor));
        String summary = choice.candidate().rule().equals("IDENTICAL_BRANCHES")
                ? "Одинаковые ветви заменены общим блоком; безопасное условие удалено."
                : "Два вложенных if соединены через && с сохранением порядка и короткого замыкания.";
        return new Preview(choice.candidate(), state.conversion().revision(), proposed, conversion, selected.id(),
                nodes.isEmpty() ? "" : nodes.getFirst(), summary,
                List.of("Это предложение. Исходный код остаётся прежним до отдельного применения.",
                        "Все комментарии сохранены с прежним количеством; комментарии удалённых частей могут быть перенесены внутрь общего блока.",
                        "Перепарсинг проверяет форму AST. Поведение нужно отдельно проверить одинаковыми тестами до и после.",
                        "Правка упрощает структуру; она не обещает улучшения асимптотической сложности или времени работы."),
                beforeFragment, newTarget.toString());
    }

    private State state(String fileName, String source, String revision) {
        if (revision == null || revision.isBlank()) throw badRequest("Укажите версию исходного кода.");
        Response converted = converter.convert(fileName, source);
        if (!converted.revision().equals(revision))
            throw new SourceException(HttpStatus.CONFLICT, "Исходный код изменился. Постройте схему и обновите предложения.");
        String normalized = source.startsWith("\uFEFF") ? source.substring(1) : source;
        CompilationUnit unit = parser().parse(normalized).getResult().orElseThrow();
        return new State(converted, unit);
    }

    private List<Choice> catalogue(State state) {
        List<Choice> choices = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int examined = 0;
        for (IfStmt branch : state.unit().findAll(IfStmt.class)) {
            if (++examined > 2000) break;
            MethodFlow method = owner(state.conversion().methods(), branch);
            if (method == null) continue;
            boolean manageable = bounded(branch, 10000, 100);
            if (branch.getElseStmt().isPresent() && manageable
                    && NoCommentEqualsVisitor.equals(unblock(branch.getThenStmt()), unblock(branch.getElseStmt().orElseThrow()))) {
                SafeCondition.Result safe = new SafeCondition().check(branch.getCondition());
                boolean identitySensitive = identitySensitive(branch.getThenStmt()) || identitySensitive(branch.getElseStmt().orElseThrow());
                String refusal = identitySensitive
                        ? "Ветви содержат локальные/анонимные типы, лямбды или ссылки на методы. Слияние может изменить идентичность типов или мест создания объектов."
                        : safe.reason();
                Candidate candidate = candidate(state, method, branch, "IDENTICAL_BRANCHES", "Убрать одинаковые ветви",
                        "Оставить общее действие в отдельном блоке. Удалить условие можно только после проверки, что его вычисление ничего не меняет и не бросает исключений.",
                        safe.safe() && !identitySensitive, refusal);
                choices.add(new Choice(candidate, branch, null)); keys.add(key(candidate.rule(), candidate.range()));
            }
            IfStmt inner = inner(branch);
            if (inner != null) {
                String reason = !manageable ? "Участок слишком большой или глубокий для ограниченной автоматической правки."
                        : branch.getElseStmt().isPresent() || inner.getElseStmt().isPresent()
                        ? "Для слияния обе проверки должны быть без else; иначе изменится выбор ветвей."
                        : hasPattern(branch.getCondition()) || hasPattern(inner.getCondition())
                        ? "Условия с pattern matching требуют отдельной проверки области видимости и пока не поддерживаются."
                        : "";
                Candidate candidate = candidate(state, method, branch, "MERGE_NESTED_IF", "Соединить вложенные условия",
                        "Заменить if (a) { if (b) { … } } на if ((a) && (b)) { … }. Второе условие вычисляется только после истинного первого.",
                        reason.isEmpty(), reason.isEmpty() ? "Промежуточных действий и else нет; порядок вычислений и короткое замыкание сохраняются." : reason);
                choices.add(new Choice(candidate, branch, inner)); keys.add(key(candidate.rule(), candidate.range()));
            }
        }
        for (Finding finding : state.conversion().analysis().findings()) {
            if (keys.contains(key(finding.rule(), finding.range()))) continue;
            Candidate candidate = new Candidate(id(state.conversion().revision(), finding.rule(), finding.range()),
                    finding.rule(), finding.title(), finding.message() + " " + finding.recommendation(),
                    finding.methodId(), finding.range(), finding.nodeIds(), false, manualReason(finding.rule()));
            choices.add(new Choice(candidate, null, null));
            keys.add(key(candidate.rule(), candidate.range()));
        }
        return choices.stream().sorted(Comparator.comparing((Choice c) -> !c.candidate().available())
                        .thenComparingLong(c -> start(c.candidate().range())).thenComparing(c -> c.candidate().rule()))
                .limit(MAX_CANDIDATES).toList();
    }
    private Candidate candidate(State state, MethodFlow method, IfStmt branch, String rule, String title,
                                String description, boolean available, String reason) {
        SourceRange range = range(branch.getCondition());
        return new Candidate(id(state.conversion().revision(), rule, range), rule, title, description, method.id(),
                range, nodeIds(method, range), available, reason);
    }
    private static String manualReason(String rule) {
        return switch (rule) {
            case "LONG_METHOD" -> "Выделение методов требует выбора границ, параметров и возвращаемых значений; пока выполняется вручную.";
            case "DEEP_NESTING" -> "Автоматически соединяются только прямые пары if без else и промежуточных действий. Другую вложенность нужно изменить вручную.";
            case "EMPTY_CATCH" -> "Нужно решить, как программа должна обрабатывать ошибку. Автоматическая замена могла бы изменить поведение.";
            case "DUPLICATE_SEQUENCE" -> "Нужно проверить области видимости, порядок действий и параметры будущего метода. Автоматическое выделение пока не поддерживается.";
            case "NESTED_TRAVERSAL" -> "Замена алгоритма требует понимания данных, порядка и повторов. Универсальная безопасная оптимизация вложенных циклов отсутствует.";
            case "UNKNOWN_LOOP_BOUND" -> "Сначала уточните границу и условие завершения цикла. Неизвестная сложность сама по себе не задаёт безопасную правку.";
            case "IDENTICAL_BRANCHES" -> "Участок не прошёл ограниченную проверку безопасного удаления условия. Измените его вручную после проверки эффектов и исключений.";
            default -> "Для этой рекомендации автоматическое AST-преобразование пока не реализовано.";
        };
    }
    private static JavaParser parser() {
        return new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setCharacterEncoding(StandardCharsets.UTF_8));
    }
    private static IfStmt inner(IfStmt branch) {
        Statement body = branch.getThenStmt();
        if (body instanceof IfStmt inner) return inner;
        if (body instanceof BlockStmt block && block.getStatements().size() == 1 && block.getStatement(0) instanceof IfStmt inner)
            return inner;
        return null;
    }
    private static Statement unblock(Statement statement) {
        return statement instanceof BlockStmt block && block.getStatements().size() == 1 ? block.getStatement(0) : statement;
    }
    private static BlockStmt asBlock(Statement statement) {
        return statement instanceof BlockStmt block ? block : new BlockStmt().addStatement(statement);
    }
    private static boolean identitySensitive(Statement statement) {
        return statement.stream().anyMatch(node -> node instanceof LocalClassDeclarationStmt
                || node instanceof LocalRecordDeclarationStmt || node instanceof LambdaExpr || node instanceof MethodReferenceExpr
                || node instanceof ObjectCreationExpr creation && creation.getAnonymousClassBody().isPresent());
    }
    private static boolean hasPattern(Expression expression) {
        return expression.stream().anyMatch(node -> node instanceof PatternExpr);
    }
    private static boolean bounded(Node root, int size, int depth) {
        record Pending(Node node, int level) {}
        Deque<Pending> stack = new ArrayDeque<>(); stack.push(new Pending(root, 0));
        int count = 0;
        while (!stack.isEmpty()) {
            Pending pending = stack.pop();
            if (++count > size || pending.level() > depth) return false;
            for (Node child : pending.node().getChildNodes()) stack.push(new Pending(child, pending.level() + 1));
        }
        return true;
    }
    private static MethodFlow owner(List<MethodFlow> flows, Node node) {
        SourceRange range = range(node);
        return flows.stream().filter(method -> contains(method.range(), range))
                .min(Comparator.comparingLong(method -> span(method.range()))).orElse(null);
    }
    private static Node navigationAnchor(Node node) {
        if (node instanceof IfStmt branch) return branch.getCondition();
        if (node instanceof BlockStmt block && !block.getStatements().isEmpty()) return navigationAnchor(block.getStatement(0));
        return node;
    }
    private static List<Integer> path(Node root, Node node) {
        List<Integer> result = new ArrayList<>();
        for (Node current = node; current != root;) {
            Node parent = current.getParentNode().orElseThrow(() -> invalid("Узел правки потерял связь с AST."));
            List<Node> children = codeChildren(parent);
            int index = -1;
            for (int i = 0; i < children.size(); i++) if (children.get(i) == current) { index = i; break; }
            if (index < 0) throw invalid("Не удалось построить путь к обновлённому узлу.");
            result.add(index); current = parent;
        }
        Collections.reverse(result);
        return result;
    }
    private static Node followPath(Node root, List<Integer> path) {
        Node result = root;
        for (Integer index : path) {
            List<Node> children = codeChildren(result);
            if (index >= children.size()) throw invalid("Путь к обновлённому узлу изменился после восстановления кода.");
            result = children.get(index);
        }
        return result;
    }
    private static List<Node> codeChildren(Node node) {
        // getChildNodes хранит порядок прикрепления: replace может переместить узел в конец.
        // Свойства AST и порядок NodeList сохраняют синтаксический путь после перепарсинга.
        List<Node> children = new ArrayList<>();
        for (var property : node.getMetaModel().getAllPropertyMetaModels()) {
            Object value = property.getValue(node);
            if (value instanceof Optional<?> optional) value = optional.orElse(null);
            if (value instanceof Node child && !(child instanceof Comment)) children.add(child);
            else if (value instanceof NodeList<?> list) {
                for (Object item : list) if (item instanceof Node child && !(child instanceof Comment)) children.add(child);
            }
        }
        return children;
    }

    private static List<Comment> comments(CompilationUnit unit) {
        Set<Comment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Comment> result = new ArrayList<>();
        for (Comment comment : unit.getAllContainedComments()) if (seen.add(comment)) result.add(comment);
        unit.getComment().filter(seen::add).ifPresent(result::add);
        return result;
    }
    private static String commentKey(Comment comment) {
        String content = comment instanceof JavadocComment javadoc ? javadoc.parse().toText() : comment.getContent();
        return comment.getClass().getSimpleName() + ":" + content.replace("\r\n", "\n").strip();
    }
    private static Map<String, Integer> commentCounts(List<Comment> comments) {
        Map<String, Integer> result = new HashMap<>();
        comments.forEach(comment -> result.merge(commentKey(comment), 1, Integer::sum));
        return result;
    }
    private static void restoreMissingComments(CompilationUnit unit, List<Comment> originals, BlockStmt destination) {
        Map<String, Integer> remaining = commentCounts(comments(unit));
        for (Comment original : originals) {
            String key = commentKey(original);
            int count = remaining.getOrDefault(key, 0);
            if (count > 0) remaining.put(key, count - 1);
            else {
                Comment restored = original.clone();
                restored.setRange(null);
                destination.addOrphanComment(restored);
            }
        }
    }
    private static List<String> nodeIds(MethodFlow flow, SourceRange range) {
        var exact = flow.nodes().stream().filter(node -> node.range().equals(range)).map(FlowNode::id).toList();
        if (!exact.isEmpty()) return exact;
        var containing = flow.nodes().stream().filter(node -> contains(node.range(), range)).toList();
        if (!containing.isEmpty()) {
            long minimum = containing.stream().mapToLong(node -> span(node.range())).min().orElseThrow();
            return containing.stream().filter(node -> span(node.range()) == minimum).map(FlowNode::id).toList();
        }
        var inside = flow.nodes().stream().filter(node -> contains(range, node.range())
                && !node.kind().equals("end") && !node.kind().equals("exception-end")).map(FlowNode::id).toList();
        if (!inside.isEmpty()) return inside;
        return flow.nodes().stream().filter(node -> node.kind().equals("start") || node.kind().equals("opaque"))
                .limit(1).map(FlowNode::id).toList();
    }
    private static SourceRange range(Node node) {
        return node.getRange().map(r -> new SourceRange(r.begin.line, r.begin.column, r.end.line, r.end.column))
                .orElseThrow(() -> invalid("Для узла отсутствует исходный диапазон."));
    }
    private static long start(SourceRange range) { return ((long) range.startLine() << 32) + range.startColumn(); }
    private static long end(SourceRange range) { return ((long) range.endLine() << 32) + range.endColumn(); }
    private static boolean contains(SourceRange outer, SourceRange inner) { return start(outer) <= start(inner) && end(outer) >= end(inner); }
    private static long span(SourceRange range) { return (long) (range.endLine() - range.startLine()) * 100000 + range.endColumn() - range.startColumn(); }
    private static String key(String rule, SourceRange range) { return rule + ":" + range; }
    private static String id(String revision, String rule, SourceRange range) {
        try {
            byte[] value = (revision + ":" + key(rule, range)).getBytes(StandardCharsets.UTF_8);
            return "opt-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
    private static SourceException invalid(String message) { return new SourceException(HttpStatus.UNPROCESSABLE_ENTITY, message); }
    private static SourceException badRequest(String message) { return new SourceException(HttpStatus.BAD_REQUEST, message); }
}
