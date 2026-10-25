package dev.blueprints.roundtrip;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import static dev.blueprints.roundtrip.RoundTripModels.*;

@RestController
@RequestMapping("/api")
public class RoundTripController {
    private final RoundTripService service;

    public RoundTripController(RoundTripService service) {
        this.service = service;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "Java Blueprints", "languageLevel", "Java 21");
    }

    @PostMapping(value = "/roundtrip", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Response convert(@RequestBody Request request) {
        return service.convert(request.fileName(), request.source());
    }

    @PostMapping(value = "/roundtrip/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Response upload(@RequestParam("file") MultipartFile file) throws IOException {
        if (file.getSize() > RoundTripService.MAX_SOURCE_BYTES) {
            throw new SourceException(HttpStatus.PAYLOAD_TOO_LARGE, "Размер файла превышает 1 МБ.");
        }
        try {
            String source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(file.getBytes())).toString();
            return service.convert(file.getOriginalFilename(), source);
        } catch (CharacterCodingException exception) {
            throw new SourceException(HttpStatus.BAD_REQUEST,
                    "Не удалось прочитать файл. Сохраните его в кодировке UTF-8.");
        }
    }
}
