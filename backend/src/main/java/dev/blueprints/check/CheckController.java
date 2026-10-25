package dev.blueprints.check;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import dev.blueprints.roundtrip.SourceException;
import static dev.blueprints.check.CheckModels.*;

@RestController
@RequestMapping("/api/check")
public class CheckController {
    private final CheckService service;
    public CheckController(CheckService service) { this.service = service; }

    @PostMapping(value = "/compile", consumes = "application/json")
    public Result compile(@RequestBody Request body, HttpServletRequest request) {
        checkLocalRequest(request); return service.check(body, false);
    }
    @PostMapping(value = "/tests", consumes = "application/json")
    public Result tests(@RequestBody Request body, HttpServletRequest request) {
        checkLocalRequest(request); return service.check(body, true);
    }

    @PostMapping(value = "/compare", consumes = "application/json")
    public Comparison compare(@RequestBody ComparisonRequest body, HttpServletRequest request) {
        checkLocalRequest(request); return service.compare(body);
    }

    // JSON + пользовательский заголовок требуют CORS preflight у чужого сайта.
    // Host и Origin проверяем дополнительно, в том числе против DNS rebinding.
    static void checkLocalRequest(HttpServletRequest request) {
        Set<String> local = Set.of("127.0.0.1", "localhost", "::1", "[::1]");
        if (!local.contains(request.getServerName()) ||
            !Set.of("127.0.0.1", "::1", "0:0:0:0:0:0:0:1").contains(request.getRemoteAddr()) ||
            !"1".equals(request.getHeader("X-Blueprints-Local")))
            throw new SourceException(HttpStatus.FORBIDDEN, "Запуск доступен только из локального приложения.");
        String origin = request.getHeader("Origin");
        if (origin == null) return; // Локальные CLI-клиенты не отправляют Origin.
        try {
            URI uri = URI.create(origin);
            int port = uri.getPort() == -1 ? 80 : uri.getPort();
            boolean sameOrigin = uri.getHost() != null && uri.getHost().equals(request.getServerName()) && port == request.getServerPort();
            boolean vite = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
            if (!"http".equals(uri.getScheme()) || uri.getRawUserInfo() != null ||
                !(sameOrigin || (vite && port == 5173))) throw new IllegalArgumentException();
        } catch (IllegalArgumentException exception) {
            throw new SourceException(HttpStatus.FORBIDDEN, "Откройте приложение по локальному адресу сервера.");
        }
    }
}
