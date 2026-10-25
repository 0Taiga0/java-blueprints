package dev.blueprints;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.apache.coyote.http11.Http11Nio2Protocol;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import static org.junit.jupiter.api.Assertions.*;

/** Реальный TCP-сервер: MockMvc не обнаруживает ошибки открытия Windows Selector/Pipe. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "spring.http.client.factory=simple"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StartupServerTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ServletWebServerApplicationContext context;

    @Test void startsRealLoopbackConnectorAndServesHealthAndRoundTrip() throws Exception {
        assertTrue(port > 0);
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            var server = assertInstanceOf(TomcatWebServer.class, context.getWebServer());
            assertInstanceOf(Http11Nio2Protocol.class, server.getTomcat().getConnector().getProtocolHandler());
        }
        var response = request("GET", "/api/health", null);
        assertEquals(200, response.status(), response.body());
        assertEquals("ok", json.readTree(response.body()).path("status").asText());

        byte[] payload = json.writeValueAsBytes(Map.of("fileName", "Hello.java",
                "source", "class Hello { String greeting(){return \"Привет\";} }"));
        response = request("POST", "/api/roundtrip", payload);
        assertEquals(200, response.status(), response.body());
        var result = json.readTree(response.body());
        assertTrue(result.path("reparsed").asBoolean());
        assertTrue(result.path("structureEquivalent").asBoolean());
        assertTrue(result.path("generatedSource").asText().contains("Привет"));
        assertEquals("greeting()", result.path("methods").get(0).path("signature").asText());
        assertEquals("flow", result.path("methods").get(0).path("mode").asText());
        assertFalse(result.path("revision").asText().isBlank());
    }

    private Response request(String method, String path, byte[] payload) throws Exception {
        // HttpClient сам открывает Selector. Здесь проверяем сервер независимо от него.
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + port + path).toURL().openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Connection", "close");
            if (payload != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(payload.length);
                try (var output = connection.getOutputStream()) { output.write(payload); }
            }
            int status = connection.getResponseCode();
            try (InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream()) {
                return new Response(status, input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
    private record Response(int status, String body) {}
}
