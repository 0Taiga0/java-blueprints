package dev.blueprints.flow;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/flow")
public class BlockMutationController {
    private final BlockDeleteService deletes;
    private final BlockMoveService moves;
    public BlockMutationController(BlockDeleteService deletes, BlockMoveService moves) {
        this.deletes = deletes; this.moves = moves;
    }
    @PostMapping("/delete")
    public BlockEditModels.Result delete(@RequestBody BlockDeleteModels.Request request) { return deletes.delete(request); }
    @PostMapping("/move")
    public BlockEditModels.Result move(@RequestBody BlockMoveModels.Request request) { return moves.move(request); }
}
