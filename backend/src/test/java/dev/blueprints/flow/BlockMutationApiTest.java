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
import static org.hamcrest.Matchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class BlockMutationApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    final String source="class A{void f(){one();two();}}";
    @Test void deleteReturnsSourceGraphAndMutationContract() throws Exception {
        var conversion=new RoundTripService().convert("A.java",source);
        var method=conversion.methods().getFirst();
        var first=method.nodes().stream().filter(n->n.label().equals("one();")).findFirst().orElseThrow();
        var request=new BlockDeleteModels.Request("A.java",source,conversion.revision(),method.id(),first.id(),"delete");
        mvc.perform(post("/api/flow/delete").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(request)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source",not(containsString("one();"))))
                .andExpect(jsonPath("$.source",containsString("two();")))
                .andExpect(jsonPath("$.conversion.structureEquivalent").value(true))
                .andExpect(jsonPath("$.selectedNodeId").isNotEmpty())
                .andExpect(jsonPath("$.conversion.methods[0].nodes[?(@.label == 'two();')].mutation.movable",hasItem(true)));
    }
    @Test void moveReturnsNewCodeAndRejectsStaleAndInvalidTarget() throws Exception {
        var conversion=new RoundTripService().convert("A.java",source);
        var method=conversion.methods().getFirst();
        var first=method.nodes().stream().filter(n->n.label().equals("one();")).findFirst().orElseThrow();
        var last=method.nodes().stream().filter(n->n.label().equals("two();")).findFirst().orElseThrow();
        var valid=new BlockMoveModels.Request("A.java",source,conversion.revision(),method.id(),first.id(),last.id(),"after");
        mvc.perform(post("/api/flow/move").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.conversion.structureEquivalent").value(true))
                .andExpect(jsonPath("$.selectedNodeId").isNotEmpty());
        var stale=new BlockMoveModels.Request("A.java",source,"stale",method.id(),first.id(),last.id(),"after");
        mvc.perform(post("/api/flow/move").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(stale)))
                .andExpect(status().isConflict());
        var self=new BlockMoveModels.Request("A.java",source,conversion.revision(),method.id(),first.id(),first.id(),"before");
        mvc.perform(post("/api/flow/move").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(self)))
                .andExpect(status().isUnprocessableEntity());
    }
    @Test void incompleteMutationRequestsReturnValidationError() throws Exception {
        for(String endpoint:java.util.List.of("delete","move")) mvc.perform(post("/api/flow/"+endpoint)
                .contentType(MediaType.APPLICATION_JSON).content("{}")) .andExpect(status().isBadRequest());
    }
}
