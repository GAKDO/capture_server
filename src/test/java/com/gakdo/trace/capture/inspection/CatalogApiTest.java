package com.gakdo.trace.capture.inspection;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.capture.inspection.api.CatalogController;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService;
import com.gakdo.trace.capture.inspection.application.CatalogQueryService.Summary;
import com.gakdo.trace.capture.support.WebTestBase;

@WebMvcTest(controllers = CatalogController.class)
class CatalogApiTest extends WebTestBase {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private CatalogQueryService service;

    @Test
    @DisplayName("공개 흠집 요약은 로그인 없이 쉼표로 이은 시점 ID를 받고, 형식이 틀린 ID는 400")
    void publicSummaries() throws Exception {
        when(service.summaries(anyList())).thenReturn(List.of(new Summary("Q2xxYv8oP0aLkM3nB7cDe", "B",
                OffsetDateTime.parse("2026-11-12T18:05:00+09:00"), 2,
                Map.of("scratch", 1, "dent", 1, "crack", 0, "stain", 0))));
        mvc.perform(get("/api/v1/captures/catalog?checkpointIds=Q2xxYv8oP0aLkM3nB7cDe,Kp9sT1uVw2Xy3Za4Bc5De"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].defectCount").value(2))
                .andExpect(jsonPath("$.items[0].defectsByClass.dent").value(1));
        mvc.perform(get("/api/v1/captures/catalog?checkpointIds=bad")).andExpect(status().isBadRequest());
    }
}
