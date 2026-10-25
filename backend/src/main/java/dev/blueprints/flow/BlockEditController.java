package dev.blueprints.flow;

import org.springframework.web.bind.annotation.*;
import static dev.blueprints.flow.BlockEditModels.*;

@RestController
@RequestMapping("/api/flow")
public class BlockEditController {
    private final BlockEditService service;
    public BlockEditController(BlockEditService service) { this.service = service; }
    @PostMapping("/edit")
    public Result edit(@RequestBody Request request) { return service.edit(request); }
}
