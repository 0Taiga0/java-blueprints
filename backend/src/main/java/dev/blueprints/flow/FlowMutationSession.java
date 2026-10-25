package dev.blueprints.flow;

import com.github.javaparser.*;
import com.github.javaparser.ast.*;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.stmt.*;
import com.github.javaparser.ast.visitor.NoCommentEqualsVisitor;
import com.github.javaparser.printer.DefaultPrettyPrinter;
import dev.blueprints.roundtrip.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import static dev.blueprints.flow.FlowModels.*;

/** Один атомарный запрос: исходник → проверенный AST → мутация → печать → перепарсинг → новая схема. */
final class FlowMutationSession {
    final JavaParser parser = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21).setCharacterEncoding(StandardCharsets.UTF_8));
    final CompilationUnit unit;
    final RoundTripModels.Response before;
    final MethodFlow method;
    final Node declaration;
    final BlockStmt body;
    private final RoundTripService converter;
    private final List<Comment> originalComments;

    FlowMutationSession(RoundTripService converter, String fileName, String source, String revision, String methodId) {
        if (revision == null || methodId == null) throw new SourceException(HttpStatus.BAD_REQUEST,
                "Укажите метод и версию исходного кода.");
        this.converter = converter;
        before = converter.convert(fileName, source);
        if (!before.revision().equals(revision)) throw conflict();
        method = before.methods().stream().filter(m -> m.id().equals(methodId)).findFirst().orElseThrow(FlowMutationSession::conflict);
        if (!method.mode().equals("flow")) throw EditableCode.invalid("Для этого метода доступен только просмотр. Измените Java-код в редакторе.");
        unit = parser.parse(source.startsWith("\uFEFF") ? source.substring(1) : source).getResult().orElseThrow();
        declaration = unit.stream().filter(n -> n instanceof CallableDeclaration<?> || n instanceof InitializerDeclaration)
                .filter(n -> InsertableCode.range(n).equals(method.range())).findFirst().orElseThrow(FlowMutationSession::conflict);
        body = declaration instanceof MethodDeclaration m ? m.getBody().orElseThrow()
                : declaration instanceof ConstructorDeclaration c ? c.getBody() : ((InitializerDeclaration) declaration).getBody();
        originalComments = BlockEditService.comments(unit).stream().map(Comment::clone).toList();
    }
    FlowNode block(String id) {
        if (id == null) throw new SourceException(HttpStatus.BAD_REQUEST, "Укажите блок схемы.");
        return method.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow(FlowMutationSession::conflict);
    }
    Node editTarget(FlowNode block) {
        if (block.edit() == null) throw EditableCode.invalid("Этот блок доступен только для просмотра.");
        return unique(unit.stream().filter(n -> {
            var edit = EditableCode.describe(n);
            return edit != null && edit.kind().equals(block.edit().kind()) && edit.range().equals(block.edit().range());
        }).toList());
    }
    Statement mutationTarget(FlowNode block) {
        if (block.mutation() == null) throw EditableCode.invalid("Этот узел нельзя удалять или перемещать отдельно.");
        return (Statement) unique(unit.findAll(Statement.class).stream()
                .filter(n -> MutableCode.statement(n) == n && InsertableCode.range(n).equals(block.mutation().range()))
                .map(n -> (Node)n).toList());
    }
    Node insertionTarget(FlowNode block, String position) {
        var insert = block.insert();
        if (insert == null || position == null || !insert.positions().contains(position))
            throw EditableCode.invalid("Выбранное место не поддерживает такую вставку. Выберите доступную позицию на схеме.");
        return unique(unit.stream().filter(n -> n.getRange().isPresent() && InsertableCode.range(n).equals(insert.range()))
                .filter(n -> insert.role().equals("body") ? n instanceof BlockStmt
                        : insert.role().equals("case") ? n instanceof SwitchEntry : n instanceof Statement && !(n instanceof BlockStmt))
                .toList());
    }
    private Node unique(List<Node> nodes) {
        if (nodes.size() != 1 || !MutableCode.isAncestor(declaration, nodes.getFirst())) throw conflict();
        return nodes.getFirst();
    }
    BlockEditModels.Result finish(Node focus, boolean requireVisible) {
        MutationValidation.validate(declaration);
        // Удалённые записки не теряются: они становятся свободными комментариями тела метода.
        Map<String,Integer> remaining = BlockEditService.commentCounts(BlockEditService.comments(unit));
        for (Comment comment : originalComments) {
            String key = BlockEditService.commentKey(comment);
            int count = remaining.getOrDefault(key, 0);
            if (count > 0) remaining.put(key, count - 1); else body.addOrphanComment(comment.clone());
        }
        List<Node> nodes = orderedNodes(unit);
        int declarationIndex = identityIndex(nodes, declaration);
        int focusIndex = focus == null ? -1 : identityIndex(nodes, focus);
        if (declarationIndex < 0 || requireVisible && focusIndex < 0) throw conflict();
        var expectedComments = BlockEditService.commentCounts(BlockEditService.comments(unit));
        String updated = new DefaultPrettyPrinter().print(unit).replace("\r\n", "\n");
        if (!updated.endsWith("\n")) updated += "\n";
        var parsed = parser.parse(updated);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()
                || !NoCommentEqualsVisitor.equals(unit, parsed.getResult().orElseThrow()))
            throw EditableCode.invalid("Не удалось безопасно восстановить Java-код. Исходник не изменён.");
        CompilationUnit restored = parsed.getResult().orElseThrow();
        var actualComments = BlockEditService.commentCounts(BlockEditService.comments(restored));
        if (expectedComments.entrySet().stream().anyMatch(e -> actualComments.getOrDefault(e.getKey(), 0) < e.getValue()))
            throw EditableCode.invalid("Не удалось сохранить все комментарии. Исходник не изменён.");
        var restoredNodes = orderedNodes(restored);
        if (nodes.size() != restoredNodes.size()) throw EditableCode.invalid("Структура дерева после печати изменилась. Исходник не изменён.");
        Node restoredDeclaration = restoredNodes.get(declarationIndex);
        SourceRange selectedRange = focusIndex < 0 ? null : InsertableCode.range(restoredNodes.get(focusIndex));
        var conversion = converter.convert(before.fileName(), updated);
        var selectedMethod = conversion.methods().stream().filter(m -> m.range().equals(InsertableCode.range(restoredDeclaration)))
                .findFirst().orElseThrow(FlowMutationSession::conflict);
        if (!selectedMethod.mode().equals("flow"))
            throw EditableCode.invalid("После изменения схема не может показать все переходы. Исходник не изменён.");
        Optional<FlowNode> selected = selectedMethod.nodes().stream().filter(n -> matches(n, selectedRange)).findFirst();
        if (selected.isEmpty() && requireVisible)
            throw EditableCode.invalid("Изменённый блок недостижим или не отображается на схеме. Исходник не изменён.");
        if (selected.isEmpty() && selectedRange != null) selected = selectedMethod.nodes().stream()
                .filter(n -> contains(selectedRange, n.range()) && !n.kind().equals("exception-end"))
                .min(Comparator.comparingInt((FlowNode n) -> n.range().startLine()).thenComparingInt(n -> n.range().startColumn()));
        String selectedId = selected.map(FlowNode::id).orElseGet(() -> selectedMethod.nodes().stream()
                .filter(n -> n.kind().equals("start")).map(FlowNode::id).findFirst().orElse(""));
        return new BlockEditModels.Result(updated, conversion, selectedMethod.id(), selectedId);
    }
    private static boolean matches(FlowNode node, SourceRange range) {
        return range != null && (node.edit() != null && node.edit().range().equals(range)
                || node.insert() != null && node.insert().range().equals(range)
                || node.mutation() != null && node.mutation().range().equals(range));
    }
    private static boolean contains(SourceRange outer, SourceRange inner) {
        return (outer.startLine() < inner.startLine() || outer.startLine() == inner.startLine() && outer.startColumn() <= inner.startColumn())
                && (outer.endLine() > inner.endLine() || outer.endLine() == inner.endLine() && outer.endColumn() >= inner.endColumn());
    }
    private static int identityIndex(List<Node> nodes, Node target) {
        for (int i=0;i<nodes.size();i++) if (nodes.get(i) == target) return i;
        return -1;
    }
    private static List<Node> orderedNodes(Node root) {
        List<Node> result = new ArrayList<>(); collect(root, result); return result;
    }
    // Порядок метамодели, а не порядок присоединения в getChildNodes(), сохраняется после печати.
    private static void collect(Node node, List<Node> result) {
        if (node instanceof Comment) return;
        result.add(node);
        for (var property : node.getMetaModel().getAllPropertyMetaModels()) {
            Object value = property.getValue(node);
            if (value instanceof Optional<?> optional) value = optional.orElse(null);
            if (value instanceof Node child) collect(child, result);
            else if (value instanceof NodeList<?> list) for (Node child : list) collect(child, result);
        }
    }
    static SourceException conflict() {
        return new SourceException(HttpStatus.CONFLICT, "Исходный код или выбранный блок изменился. Постройте схему заново.");
    }
}
