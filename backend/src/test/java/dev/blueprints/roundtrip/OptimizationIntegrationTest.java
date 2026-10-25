package dev.blueprints.roundtrip;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.blueprints.check.CheckModels.ComparisonRequest;
import dev.blueprints.check.CheckService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class OptimizationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RoundTripService converter;

    private String source() throws Exception { return Files.readString(Path.of("../examples/OptimizationExamples.java")); }
    private JsonNode options(String source) throws Exception {
        String revision = converter.convert("OptimizationExamples.java", source).revision();
        return json.readTree(mvc.perform(post("/api/optimization/options").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("fileName", "OptimizationExamples.java", "source", source, "revision", revision))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.candidates").isArray()).andReturn().getResponse().getContentAsByteArray());
    }
    private JsonNode candidate(JsonNode options, String rule, int startLine) {
        for (JsonNode c : options.get("candidates")) {
            if (c.path("rule").asText().equals(rule) && c.path("available").asBoolean()
                    && (startLine < 0 || c.path("range").path("startLine").asInt() == startLine)) return c;
        }
        throw new AssertionError("No candidate: " + rule + " @ " + startLine + " in " + options);
    }
    private JsonNode preview(String source, JsonNode candidate) throws Exception {
        String revision = converter.convert("OptimizationExamples.java", source).revision();
        return json.readTree(mvc.perform(post("/api/optimization/preview").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("fileName", "OptimizationExamples.java", "source", source,
                "revision", revision, "candidateId", candidate.path("id").asText()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.conversion.analysis").exists())
            .andReturn().getResponse().getContentAsByteArray());
    }
    private void assertSameSixTests(String before, JsonNode preview) throws Exception {
        var report = new CheckService().compare(new ComparisonRequest("OptimizationExamples.java", before,
            preview.path("source").asText(), "OptimizationExamplesTest.java",
            Files.readString(Path.of("../examples/OptimizationExamplesTest.java"))));
        assertEquals("matched", report.status(), report.toString());
        assertEquals(6, report.before().passed()); assertEquals(6, report.after().passed());
    }

    @Test void safeBranchPreviewKeepsCommentsAndPassesTheSameTestsWithoutMutatingOriginal() throws Exception {
        String source = source();
        var options = options(source);
        var preview = preview(source, candidate(options, "IDENTICAL_BRANCHES", -1));
        assertEquals(options.path("revision").asText(), preview.path("revision").asText());
        assertNotEquals(source, preview.path("source").asText());
        assertTrue(preview.path("source").asText().contains("Объяснение первой ветки"));
        assertTrue(preview.path("source").asText().contains("Объяснение второй ветки"));
        assertEquals(options, options(source), "Preview must not change source or catalog state");
        var fresh = converter.convert("OptimizationExamples.java", preview.path("source").asText());
        assertEquals(json.readTree(json.writeValueAsBytes(fresh)), preview.get("conversion"));
        assertTrue(fresh.methods().stream().anyMatch(m -> m.id().equals(preview.path("selectedMethodId").asText())));
        assertSameSixTests(source, preview);
    }

    @Test void mergingEffectfulConditionsPreservesEvaluationOrderAndShortCircuit() throws Exception {
        String source = source();
        var options = options(source);
        var flow = converter.convert("OptimizationExamples.java", source).methods().stream()
            .filter(m -> m.signature().startsWith("orderedChecks(")).findFirst().orElseThrow();
        JsonNode selected = null;
        for (JsonNode c : options.get("candidates")) {
            if (c.path("rule").asText().equals("MERGE_NESTED_IF") && c.path("available").asBoolean()
                    && c.path("methodId").asText().equals(flow.id())) { selected = c; break; }
        }
        assertNotNull(selected, options.toPrettyString());
        var preview = preview(source, selected);
        assertTrue(preview.path("afterFragment").asText().contains("&&"));
        assertSameSixTests(source, preview);
    }

    @Test void changedSourceAndUnknownCandidateCannotReuseAnOldPreviewRequest() throws Exception {
        String source = source();
        var options = options(source);
        var candidate = candidate(options, "IDENTICAL_BRANCHES", -1);
        mvc.perform(post("/api/optimization/preview").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("fileName", "OptimizationExamples.java", "source", source + "\n",
                "revision", options.path("revision").asText(), "candidateId", candidate.path("id").asText()))))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.source").doesNotExist());
        mvc.perform(post("/api/optimization/preview").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("fileName", "OptimizationExamples.java", "source", source,
                "revision", options.path("revision").asText(), "candidateId", "made-up-candidate"))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test void unsafeEqualBranchesAreVisibleButCannotBePreviewed() throws Exception {
        String source = source();
        var options = options(source);
        var flow = converter.convert("OptimizationExamples.java", source).methods().stream()
            .filter(m -> m.signature().startsWith("effectfulCondition(")).findFirst().orElseThrow();
        JsonNode unavailable = null;
        for (JsonNode c : options.get("candidates")) {
            if (c.path("rule").asText().equals("IDENTICAL_BRANCHES") && c.path("methodId").asText().equals(flow.id())) {
                unavailable = c; break;
            }
        }
        assertNotNull(unavailable, options.toPrettyString());
        assertFalse(unavailable.path("available").asBoolean());
        assertFalse(unavailable.path("reason").asText().isBlank());
        mvc.perform(post("/api/optimization/preview").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(Map.of("fileName", "OptimizationExamples.java", "source", source,
                "revision", options.path("revision").asText(), "candidateId", unavailable.path("id").asText()))))
            .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.source").doesNotExist());
    }
}
