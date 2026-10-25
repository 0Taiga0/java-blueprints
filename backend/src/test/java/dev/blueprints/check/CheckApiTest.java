package dev.blueprints.check;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class CheckApiTest {
    @Autowired MockMvc mvc;
    private static final String BODY = "{\"fileName\":\"Example.java\",\"source\":\"class Example { int n = 1; }\"}";

    @Test void compilesFromLocalUi() throws Exception {
        mvc.perform(post("/api/check/compile").header("X-Blueprints-Local", "1").header("Origin", "http://localhost")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("compiled"));
    }
    @Test void rejectsForeignOriginsMissingHeaderAndForeignHost() throws Exception {
        mvc.perform(post("/api/check/compile").header("X-Blueprints-Local", "1").header("Origin", "https://example.org")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/check/compile").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("http://example.org/api/check/compile").header("X-Blueprints-Local", "1")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
    }
    @Test void rejectsNonJsonAndMissingTests() throws Exception {
        mvc.perform(post("/api/check/compile").header("X-Blueprints-Local", "1")
            .contentType(MediaType.TEXT_PLAIN).content(BODY)).andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/api/check/tests").header("X-Blueprints-Local", "1")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
    }

    @Test void comparisonRequiresSameLocalGuards() throws Exception {
        for (String origin : new String[]{"https://example.org", "http://localhost:1234"}) {
            mvc.perform(post("/api/check/compare").header("X-Blueprints-Local", "1").header("Origin", origin)
                .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/check/compare").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isForbidden());
        mvc.perform(post("/api/check/compare").header("X-Blueprints-Local", "1")
            .contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isBadRequest());
    }

    @Test void comparesViaJsonEndpoint() throws Exception {
        var body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new CheckModels.ComparisonRequest(
            "Example.java", "class Example {static int n(){return 1;}}", "class Example {static int n(){return 2;}}",
            "ExampleTest.java", "class ExampleTest {@org.junit.jupiter.api.Test void n(){org.junit.jupiter.api.Assertions.assertEquals(1, Example.n());}}"));
        mvc.perform(post("/api/check/compare").header("X-Blueprints-Local", "1")
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("changed"))
            .andExpect(jsonPath("$.before.passed").value(1))
            .andExpect(jsonPath("$.after.failed").value(1))
            .andExpect(jsonPath("$.regressions").value(1));
    }

}
