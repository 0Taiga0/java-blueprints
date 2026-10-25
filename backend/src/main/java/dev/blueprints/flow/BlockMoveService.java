package dev.blueprints.flow;

import dev.blueprints.roundtrip.RoundTripService;
import org.springframework.stereotype.Service;

@Service
public class BlockMoveService {
    private final RoundTripService converter;
    public BlockMoveService(RoundTripService converter) { this.converter = converter; }
    public BlockEditModels.Result move(BlockMoveModels.Request request) {
        var session = new FlowMutationSession(converter, request.fileName(), request.source(), request.revision(), request.methodId());
        var block = session.block(request.nodeId());
        var source = session.mutationTarget(block);
        if (!block.mutation().movable()) throw EditableCode.invalid("Этот блок нельзя перемещать отдельно.");
        var target = session.insertionTarget(session.block(request.targetNodeId()), request.position());
        if (MutableCode.isAncestor(source, target))
            throw EditableCode.invalid("Нельзя переместить блок в самого себя или внутрь его собственного подграфа.");
        StatementPlacement.remove(source);
        StatementPlacement.insertAt(target, request.position(), source);
        return session.finish(source, true);
    }
}
