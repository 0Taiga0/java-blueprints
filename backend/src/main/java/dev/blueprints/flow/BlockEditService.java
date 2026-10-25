package dev.blueprints.flow;

import com.github.javaparser.ast.*;
import com.github.javaparser.ast.comments.Comment;
import dev.blueprints.roundtrip.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import static dev.blueprints.flow.BlockEditModels.*;

@Service
public class BlockEditService {
    private final RoundTripService converter;
    public BlockEditService(RoundTripService converter) { this.converter = converter; }
    public Result edit(Request request) {
        if (request.methodId() == null || request.nodeId() == null || request.revision() == null)
            throw new SourceException(HttpStatus.BAD_REQUEST, "Укажите метод, блок и версию исходного кода.");
        validateText(request.replacement());
        var session = new FlowMutationSession(converter, request.fileName(), request.source(), request.revision(), request.methodId());
        var block = session.block(request.nodeId());
        Node target = session.editTarget(block);
        Node replacement = EditableCode.parseReplacement(session.parser, block.edit().kind(), request.replacement().trim());
        target.getComment().ifPresent(comment -> {
            if (replacement.getComment().isEmpty()) replacement.setComment(comment.clone());
        });
        if (!target.replace(replacement)) throw EditableCode.invalid("Не удалось заменить выбранный блок.");
        return session.finish(replacement, true);
    }
    static void validateText(String text) {
        if (text == null || text.isBlank()) throw EditableCode.invalid("Введите Java-код блока.");
        if (text.getBytes(StandardCharsets.UTF_8).length > 20_000)
            throw new SourceException(HttpStatus.PAYLOAD_TOO_LARGE, "Текст одного блока ограничен 20 КБ.");
    }
    static List<Comment> comments(CompilationUnit unit) {
        List<Comment> result = new ArrayList<>(unit.getAllContainedComments());
        unit.getComment().ifPresent(result::add);
        return result;
    }
    static String commentKey(Comment c) { return c.getClass().getSimpleName() + ":" + (c instanceof com.github.javaparser.ast.comments.JavadocComment j
            ? j.parse().toText().strip() : c.getContent().strip()); }
    static Map<String, Integer> commentCounts(List<Comment> comments) {
        Map<String, Integer> counts = new HashMap<>();
        comments.forEach(c -> counts.merge(commentKey(c), 1, Integer::sum));
        return counts;
    }
}
