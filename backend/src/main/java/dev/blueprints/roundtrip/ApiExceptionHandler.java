package dev.blueprints.roundtrip;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import static dev.blueprints.roundtrip.RoundTripModels.ApiError;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(SourceException.class)
    public ResponseEntity<ApiError> sourceError(SourceException exception) {
        return ResponseEntity.status(exception.status())
                .body(new ApiError(exception.getMessage(), exception.diagnostics()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge() {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ApiError("Размер файла превышает 1 МБ.", List.of()));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestPartException.class})
    public ResponseEntity<ApiError> invalidRequest() {
        return ResponseEntity.badRequest()
                .body(new ApiError("Передайте Java-файл или имя файла и его код.", List.of()));
    }
}
