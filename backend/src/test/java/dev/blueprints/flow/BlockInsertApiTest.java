package dev.blueprints.flow;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.blueprints.roundtrip.RoundTripService;
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
class BlockInsertApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    private BlockInsertModels.Request request(String revision, String code) {
        String source = "class A {void f(){}}";
        var before = new RoundTripService().convert("A.java", source);
        var method = before.methods().getFirst();
        var start = method.nodes().stream().filter(n -> n.kind().equals("start")).findFirst().orElseThrow();
        return new BlockInsertModels.Request("A.java",source,revision == null ? before.revision() : revision,
                method.id(),start.id(),"body-start","variable",code);
    }
    @Test void returnsCodeAndRebuiltGraph() throws Exception {
        mvc.perform(post("/api/flow/insert").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request(null,"int count=1;"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.conversion.structureEquivalent").value(true))
            .andExpect(jsonPath("$.source").value(org.hamcrest.Matchers.containsString("int count = 1;")))
            .andExpect(jsonPath("$.selectedNodeId").isNotEmpty());
    }
    @Test void rejectsStaleAndInvalidRequests() throws Exception {
        mvc.perform(post("/api/flow/insert").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request("stale","int count=1;")))).andExpect(status().isConflict());
        mvc.perform(post("/api/flow/insert").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(request(null,"int count=;")))).andExpect(status().isUnprocessableEntity());
        mvc.perform(post("/api/flow/insert").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest());
    }
}
