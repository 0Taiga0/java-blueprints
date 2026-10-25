package dev.blueprints.roundtrip;

import java.util.List;
import org.springframework.http.HttpStatus;
import static dev.blueprints.roundtrip.RoundTripModels.Diagnostic;

public class SourceException extends RuntimeException {
    private final HttpStatus status;
    private final List<Diagnostic> diagnostics;

    public SourceException(HttpStatus status, String message, List<Diagnostic> diagnostics) {
        super(message);
        this.status = status;
        this.diagnostics = List.copyOf(diagnostics);
    }

    public SourceException(HttpStatus status, String message) {
        this(status, message, List.of());
    }

    public HttpStatus status() { return status; }
    public List<Diagnostic> diagnostics() { return diagnostics; }
}
