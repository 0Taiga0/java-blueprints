package dev.blueprints.flow;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/visual")
public class VisualController {
    private final VisualEditService service;
    public VisualController(VisualEditService service) { this.service=service; }
    @PostMapping("/describe") public VisualModels.Description describe(@RequestBody VisualModels.Context request) {
        return service.describe(request);
    }
    @PostMapping("/apply") public BlockEditModels.Result apply(@RequestBody VisualModels.Apply request) {
        return service.apply(request);
    }
}
