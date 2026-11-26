package com.chargeflow.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** End-to-end through HTTP: generate, then allocate, then check errors. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    @DisplayName("generate -> allocate returns a consistent result with baseline comparison")
    void endToEnd() throws Exception {
        String hub = mvc.perform(get("/api/hub/default")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String gen = mvc.perform(post("/api/vehicles/generate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":120,\"seed\":5}")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        ObjectNode body = json.createObjectNode();
        body.set("hub", json.readTree(hub));
        body.set("vehicles", json.readTree(gen).get("vehicles"));
        body.set("options", json.readTree("{\"algorithm\":\"EDMONDS_KARP\",\"priorityFraction\":0.2,\"compareBaseline\":true,\"seed\":1}"));
        String res = mvc.perform(post("/api/allocate").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(res);
        assertEquals(120, r.at("/summary/vehicles").asInt());
        assertTrue(r.at("/summary/servedSlots").asLong() >= r.at("/baseline/servedSlots").asLong());
        assertEquals(r.at("/minCut/capacity").asLong(), r.at("/summary/servedSlots").asLong());
        assertEquals(120, r.at("/registry/checked").asLong());
    }

    @Test
    @DisplayName("empty vehicle list -> 422 with explanation")
    void emptyVehicles() throws Exception {
        String hub = mvc.perform(get("/api/hub/default")).andReturn().getResponse().getContentAsString();
        mvc.perform(post("/api/allocate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hub\":" + hub + ",\"vehicles\":[]}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("No vehicles")));
    }

    @Test
    @DisplayName("malformed JSON -> 400")
    void malformed() throws Exception {
        mvc.perform(post("/api/allocate").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("registry check reports Bloom answer and exact answer separately")
    void registryCheck() throws Exception {
        mvc.perform(get("/api/registry/check").param("plate", "ZZ99ZZ9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exactMember").value(false));
    }

    @Test
    @DisplayName("CSV upload with errors returns the errors, not a silent partial import")
    void csvErrors() throws Exception {
        mvc.perform(post("/api/vehicles/parse").contentType(MediaType.TEXT_PLAIN)
                        .content("plate,connector,arrival,departure,energy_kwh,battery_pct\nMH12AB1234,CCS9,09:00,10:00,10,20\n"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors[0]").value(org.hamcrest.Matchers.containsString("unknown connector")));
    }

    @Test
    @DisplayName("framework errors keep their 4xx status: 404 unknown path, 405 wrong method, 400 missing param, 415 wrong type")
    void clientErrorsAreNot500() throws Exception {
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());
        mvc.perform(get("/api/allocate")).andExpect(status().isMethodNotAllowed());
        mvc.perform(get("/api/registry/check")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/vehicles/parse").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("min-cut explanation names the limiting connector type (demo day: Type 2 AC, not CCS2)")
    void bottleneckIsExplained() throws Exception {
        String hub = mvc.perform(get("/api/hub/default")).andReturn().getResponse().getContentAsString();
        String gen = mvc.perform(post("/api/vehicles/generate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"count\":220,\"seed\":2026}")).andReturn().getResponse().getContentAsString();
        ObjectNode body = json.createObjectNode();
        body.set("hub", json.readTree(hub));
        body.set("vehicles", json.readTree(gen).get("vehicles"));
        String res = mvc.perform(post("/api/allocate").contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String expl = json.readTree(res).at("/minCut/explanation").asText();
        assertTrue(expl.contains("Bottleneck: Type 2 AC"), expl);
        assertTrue(expl.contains("Demand fully met for CCS2"), expl);
    }
}
