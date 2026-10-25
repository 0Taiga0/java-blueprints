package dev.blueprints.flow;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.stmt.*;
import dev.blueprints.roundtrip.*;
import org.springframework.stereotype.Service;
import static dev.blueprints.flow.InsertableCode.*;

@Service
public class BlockInsertService {
    private final RoundTripService converter;
    public BlockInsertService(RoundTripService converter) { this.converter = converter; }
    public BlockEditModels.Result insert(BlockInsertModels.Request request) {
        if (request.methodId() == null || request.nodeId() == null || request.revision() == null
                || request.position() == null || request.kind() == null)
            throw new SourceException(org.springframework.http.HttpStatus.BAD_REQUEST, "Укажите метод, блок, место вставки и тип нового блока.");
        BlockEditService.validateText(request.code());
        var session = new FlowMutationSession(converter, request.fileName(), request.source(), request.revision(), request.methodId());
        var anchor = session.insertionTarget(session.block(request.nodeId()), request.position());
        if (request.kind() == null) throw EditableCode.invalid("Укажите тип нового блока.");
        Statement added = parseBlock(session.parser, request.kind(), request.code());
        StatementPlacement.insertAt(anchor, request.position(), added);
        return session.finish(added, true);
    }
    private static Statement parseBlock(JavaParser parser, String kind, String code) {
        var parsed = parser.parseStatement(code.trim());
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty())
            throw EditableCode.invalid("Нужен один законченный оператор Java. Проверьте скобки и точку с запятой.");
        Statement s = parsed.getResult().orElseThrow();
        boolean valid = switch (kind) {
            case "variable" -> s instanceof ExpressionStmt e && e.getExpression().isVariableDeclarationExpr();
            case "operation" -> s instanceof ExpressionStmt;
            case "condition" -> s instanceof IfStmt;
            case "loop" -> loopBody(s) != null;
            case "call" -> s instanceof ExpressionStmt e && e.getExpression().isMethodCallExpr();
            case "return" -> s instanceof ReturnStmt;
            case "break" -> s instanceof BreakStmt b && b.getLabel().isEmpty();
            case "continue" -> s instanceof ContinueStmt c && c.getLabel().isEmpty();
            default -> false;
        };
        if (!valid) throw EditableCode.invalid("Java-код не соответствует выбранному типу блока.");
        return s;
    }

}
