package dev.blueprints.roundtrip;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest
@AutoConfigureMockMvc
class RoundTripApiTest {
    @Autowired MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired RoundTripService converter;

    @Test
    void reportsHealth() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"));
    }

    @Test
    void transformsEditorJson() throws Exception {
        mvc.perform(post("/api/roundtrip").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"Hello.java\",\"source\":\"class Hello { int sum(){return 2+3;} }\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.methods[0].signature").value("sum()"))
                .andExpect(jsonPath("$.methods[0].mode").value("flow"))
                .andExpect(jsonPath("$.methods[0].nodes[0].range.startLine").value(1))
                .andExpect(jsonPath("$.structureEquivalent").value(true))
                .andExpect(jsonPath("$.generatedSource").value(containsString("return 2 + 3;")));
    }

    @Test
    void uploadsRealMultipartWithRussianText() throws Exception {
        var file = new MockMultipartFile("file", "Hello.java", "text/plain",
                "class Hello { String message = \"Привет\"; }".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/roundtrip/file").file(file))
                .andExpect(status().isOk()).andExpect(jsonPath("$.generatedSource").value(containsString("Привет")))
                .andExpect(jsonPath("$.structureEquivalent").value(true));
    }

    @Test
    void returnsActionableSyntaxError() throws Exception {
        var file = new MockMultipartFile("file", "Broken.java", "text/plain",
                "class Broken { int x = ; }".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/roundtrip/file").file(file))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.diagnostics[0].line").value(1))
                .andExpect(jsonPath("$.generatedSource").doesNotExist());
    }

    @Test
    void rejectsInvalidUtf8() throws Exception {
        var file = new MockMultipartFile("file", "Hello.java", "text/plain", new byte[] {(byte) 0xff});
        mvc.perform(multipart("/api/roundtrip/file").file(file))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("UTF-8")));
    }

    @Test
    void rejectsMissingFile() throws Exception {
        mvc.perform(multipart("/api/roundtrip/file")).andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedFile() throws Exception {
        var file = new MockMultipartFile("file", "Large.java", "text/plain",
                new byte[RoundTripService.MAX_SOURCE_BYTES + 1]);
        mvc.perform(multipart("/api/roundtrip/file").file(file)).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void editsBlockThroughHttpAndReturnsNewGraph() throws Exception {
        String source = "class A { int f(int x) { if(x>0) return 1; return 0; } }";
        var before = converter.convert("A.java", source);
        var method = before.methods().getFirst();
        var node = method.nodes().stream().filter(n -> n.edit()!=null && n.edit().kind().equals("condition")).findFirst().orElseThrow();
        var request = new dev.blueprints.flow.BlockEditModels.Request("A.java", source,
                before.revision(), method.id(), node.id(), "x >= 10");
        mvc.perform(post("/api/flow/edit").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value(containsString("x >= 10")))
                .andExpect(jsonPath("$.conversion.structureEquivalent").value(true))
                .andExpect(jsonPath("$.conversion.revision").isString())
                .andExpect(jsonPath("$.selectedMethodId").isString());
    }

    @Test
    void invalidBlockEditReturns422WithoutAReplacement() throws Exception {
        String source = "class A { void f(){work();} }";
        var before = converter.convert("A.java", source);
        var method = before.methods().getFirst();
        var node = method.nodes().stream().filter(n -> n.edit()!=null).findFirst().orElseThrow();
        var request = new dev.blueprints.flow.BlockEditModels.Request("A.java", source,
                before.revision(), method.id(), node.id(), "x = ;");
        mvc.perform(post("/api/flow/edit").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.source").doesNotExist());
    }

    @Test
    void staleBlockEditReturns409() throws Exception {
        var request = new dev.blueprints.flow.BlockEditModels.Request("A.java", "class A {void f(){work();}}",
                "stale", "method-1-10", "n1", "other();");
        mvc.perform(post("/api/flow/edit").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(request)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.source").doesNotExist());
    }
}
