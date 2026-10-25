package dev.blueprints.optimization;

import org.springframework.web.bind.annotation.*;
import static dev.blueprints.optimization.OptimizationModels.*;

@RestController
@RequestMapping("/api/optimization")
public class OptimizationController {
    private final OptimizationService service;
    public OptimizationController(OptimizationService service) { this.service = service; }

    @PostMapping("/options")
    public Options options(@RequestBody OptionsRequest request) { return service.options(request); }

    @PostMapping("/preview")
    public Preview preview(@RequestBody PreviewRequest request) { return service.preview(request); }
}
