package dev.blueprints.flow;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.stmt.*;
import dev.blueprints.roundtrip.RoundTripService;
import org.springframework.stereotype.Service;

@Service
public class BlockDeleteService {
    private final RoundTripService converter;
    public BlockDeleteService(RoundTripService converter) { this.converter = converter; }
    public BlockEditModels.Result delete(BlockDeleteModels.Request request) {
        var session = new FlowMutationSession(converter, request.fileName(), request.source(), request.revision(), request.methodId());
        var block = session.block(request.nodeId());
        Statement target = session.mutationTarget(block);
        if (request.mode() == null || !block.mutation().deleteModes().contains(request.mode()))
            throw EditableCode.invalid("Этот способ удаления недоступен. Сохранение тела не должно терять локальные переменные или оставлять сломанные break/continue.");
        Node focus;
        if (request.mode().equals("delete")) focus = StatementPlacement.remove(target);
        else {
            Statement retained = switch (request.mode()) {
                case "keep-then" -> ((IfStmt)target).getThenStmt();
                case "keep-else" -> ((IfStmt)target).getElseStmt().orElseThrow();
                case "keep-body" -> InsertableCode.loopBody(target);
                default -> throw EditableCode.invalid("Неизвестный способ удаления.");
            };
            // Не разворачиваем { ... } в родительский список: локальные переменные сохраняют свою область.
            BlockStmt replacement = retained instanceof BlockStmt b ? b.clone() : new BlockStmt().addStatement(retained.clone());
            StatementPlacement.replace(target, replacement); focus = replacement;
        }
        return session.finish(focus, false);
    }
}
