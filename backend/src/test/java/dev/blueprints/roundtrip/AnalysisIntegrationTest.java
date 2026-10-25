package dev.blueprints.roundtrip;

import dev.blueprints.check.CheckModels;
import dev.blueprints.check.CheckService;
import dev.blueprints.flow.BlockEditModels;
import dev.blueprints.flow.BlockEditService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AnalysisIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired RoundTripService converter;

    private static final String SOURCE = """
        class Sample {
            int twice(int value) {
                if (value > 0) {
                    return value * 2;
                } else {
                    return value * 2;
                }
            }
        }
        """;

    @Test void editorAndUploadExposeAnalysisWithoutChangingSource() throws Exception {
        var request = Map.of("fileName", "Sample.java", "source", SOURCE);
        var editor = mvc.perform(post("/api/roundtrip").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.analysis.findings[0].rule").value("IDENTICAL_BRANCHES"))
            .andExpect(jsonPath("$.analysis.methods[0].complexity").exists())
            .andReturn().getResponse().getContentAsByteArray();
        var upload = mvc.perform(multipart("/api/roundtrip/file").file(
            new MockMultipartFile("file", "Sample.java", "text/plain", SOURCE.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertEquals(json.readTree(editor).get("analysis"), json.readTree(upload).get("analysis"));
        assertTrue(converter.convert("Sample.java", SOURCE).structureEquivalent());
    }

    @Test void changingOneBranchRefreshesFindingsAndSourceCoordinates() throws Exception {
        var before = converter.convert("Sample.java", SOURCE);
        var method = before.methods().getFirst();
        var target = method.nodes().stream()
            .filter(n -> n.edit() != null && n.edit().kind().equals("return"))
            .findFirst().orElseThrow();
        var request = new BlockEditModels.Request("Sample.java", SOURCE, before.revision(),
            method.id(), target.id(), "return value * 3;");
        var payload = mvc.perform(post("/api/flow/edit").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsBytes(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.conversion.analysis.findings").isEmpty())
            .andReturn().getResponse().getContentAsByteArray();
        var edited = json.readTree(payload);
        assertNotEquals(before.revision(), edited.path("conversion").path("revision").asText());
        var fresh = converter.convert("Sample.java", edited.get("source").asText());
        assertEquals(json.valueToTree(fresh.analysis()), edited.path("conversion").path("analysis"));
        assertTrue(fresh.structureEquivalent());
    }

    @Test void everyDemoFindingPointsToAnExistingMethodNodeAndSourceLine() throws Exception {
        String source = Files.readString(Path.of("../examples/AnalysisExamples.java"));
        var result = converter.convert("AnalysisExamples.java", source);
        assertFalse(result.analysis().truncated());
        assertTrue(result.analysis().findings().size() >= 4, result.analysis().toString());
        int lines = source.split("\\R", -1).length;
        for (var finding : result.analysis().findings()) {
            var method = result.methods().stream().filter(m -> m.id().equals(finding.methodId())).findFirst().orElseThrow();
            assertFalse(finding.nodeIds().isEmpty(), finding.toString());
            for (String id : finding.nodeIds()) assertTrue(method.nodes().stream().anyMatch(n -> n.id().equals(id)), finding.toString());
            assertTrue(finding.range().startLine() >= 1 && finding.range().endLine() <= lines, finding.toString());
            assertFalse(finding.recommendation().isBlank());
        }
    }

    @Test void analysisDemoAndRestoredCodeBothPassTheSameEightTests() throws Exception {
        String source = Files.readString(Path.of("../examples/AnalysisExamples.java"));
        String tests = Files.readString(Path.of("../examples/AnalysisExamplesTest.java"));
        var converted = converter.convert("AnalysisExamples.java", source);
        var report = new CheckService().compare(new CheckModels.ComparisonRequest(
            "AnalysisExamples.java", source, converted.generatedSource(), "AnalysisExamplesTest.java", tests));
        assertEquals("matched", report.status(), report.toString());
        assertEquals(8, report.before().passed());
        assertEquals(8, report.after().passed());
    }

    @Test void emptyCatchFindingTargetsHandlerInsteadOfEnclosingTry() {
        var result = converter.convert("Sample.java", "class Sample { void f() { try { throw new Exception(); } catch (Exception e) {} } }");
        var finding = result.analysis().findings().stream().filter(f -> f.rule().equals("EMPTY_CATCH")).findFirst().orElseThrow();
        var flow = result.methods().stream().filter(m -> m.id().equals(finding.methodId())).findFirst().orElseThrow();
        assertFalse(finding.nodeIds().isEmpty());
        for (String id : finding.nodeIds()) {
            assertEquals("catch", flow.nodes().stream().filter(n -> n.id().equals(id)).findFirst().orElseThrow().kind());
        }
    }
}
