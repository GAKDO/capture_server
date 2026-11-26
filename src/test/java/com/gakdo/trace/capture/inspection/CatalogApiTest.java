// 이 파일이 확인하는 것: 공개 흠집 요약 API(/api/v1/captures/catalog). 로그인 없이 시점별 흠집 개수·종류·등급을 보여 준다.

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

// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(CatalogController)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = CatalogController.class)
class CatalogApiTest extends WebTestBase {

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // (도구) @MockitoBean = 진짜 서비스 대신 가짜(목) 서비스를 스프링에 끼워 넣는다. API 입구만 따로 시험하려고
    @MockitoBean
    private CatalogQueryService service;

    // [상황] 로그인 없이 시점 ID 두 개를 쉼표로 이어 요약을 요청하고, 다음엔 형식이 틀린 ID("bad")로 요청한다.
    // [기대] 첫 요청은 200과 흠집 개수(2)·종류별 개수(찌그러짐 1 등)를 돌려주고, 틀린 ID는 400으로 거절한다.
    //        ※ 응답 코드: 200 = 정상 처리됨, 400 = 요청 내용이 형식에 맞지 않음
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("공개 흠집 요약은 로그인 없이 쉼표로 이은 시점 ID를 받고, 형식이 틀린 ID는 400")
    void publicSummaries() throws Exception {
        // (도구) when(가짜.메서드(조건)).thenReturn(결과) = "가짜에게 이 질문이 오면 이 결과를 돌려줘"라고 미리 정해 두는 것. anyList() = 아무 목록이나
        // 준비: 가짜 서비스가 요약을 달라고 하면 시점 1개(등급 B, 시각, 흠집 2개, 종류별 긁힘 1·찌그러짐 1·금 0·얼룩 0) 요약을 돌려주게 한다
        when(service.summaries(anyList())).thenReturn(List.of(new Summary("Q2xxYv8oP0aLkM3nB7cDe", "B",
                OffsetDateTime.parse("2026-11-12T18:05:00+09:00"), 2,
                Map.of("scratch", 1, "dent", 1, "crack", 0, "stain", 0))));
        // (도구) perform(get(주소)) = GET 요청을 보낸다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 로그인 없이 시점 ID 두 개를 쉼표로 이어 요약을 요청한다
        mvc.perform(get("/api/v1/captures/catalog?checkpointIds=Q2xxYv8oP0aLkM3nB7cDe,Kp9sT1uVw2Xy3Za4Bc5De"))
                // 확인: 응답 코드가 200이어야 한다 = 로그인 없이 열린다
                .andExpect(status().isOk())
                // (도구) jsonPath("$.items[0].칸") = 응답 JSON의 items 첫 항목에서 그 칸 값을 꺼낸다
                // 확인: 첫 항목의 흠집 개수가 2여야 한다
                .andExpect(jsonPath("$.items[0].defectCount").value(2))
                // 확인: 종류별 개수에서 찌그러짐(dent)이 1이어야 한다
                .andExpect(jsonPath("$.items[0].defectsByClass.dent").value(1));
        // 실행+확인: 형식이 틀린 시점 ID(bad)로 요청하면 400이어야 한다
        mvc.perform(get("/api/v1/captures/catalog?checkpointIds=bad")).andExpect(status().isBadRequest());
    }
}
