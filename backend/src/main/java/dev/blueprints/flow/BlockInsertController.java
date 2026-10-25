package dev.blueprints.flow;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/flow")
public class BlockInsertController {
    private final BlockInsertService service;
    public BlockInsertController(BlockInsertService service) { this.service = service; }
    @PostMapping("/insert")
    public BlockEditModels.Result insert(@RequestBody BlockInsertModels.Request request) {
        return service.insert(request);
    }
}
