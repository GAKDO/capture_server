// 이 파일이 확인하는 것: 사람 검수(11/8)를 처음부터 끝까지 실제 PostgreSQL·RabbitMQ로 돌려 보는 통합 시험.
// 흐름: 부스 빠른 검사 → AI가 흠집 후보를 넓게 표시 → 등급 보류 → 관리자 검수 대기 목록 → 흠집 맞음(확정)/아님(기각)
// → 검수 완료 → 기각을 뺀 흠집으로 등급 → "시점 등급 완료" 이벤트에 검수 기록(review)이 실린다.
// Docker가 필요해서 Claude 세션에서는 건너뛰고 GitHub Actions에서 돈다.

package com.gakdo.trace.capture.inspection;

import static com.gakdo.trace.capture.support.TestJwts.admin;
import static com.gakdo.trace.capture.support.TestJwts.booth;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.capture.support.FakePhotoStorage;

/**
 * 사람 검수(11/8) 끝까지: 부스 빠른 검사 → AI 후보 → 등급 보류 → 검수 대기 목록 → 확정·기각 → 검수 완료 → 기각을 뺀 등급과 이벤트 review.
 * 실제 PostgreSQL(Flyway V7)·RabbitMQ. Docker가 없으면 건너뛴다.
 */
// (도구) @SpringBootTest = 서버 전체(컨트롤러·서비스·DB 연결·메시지 큐)를 진짜로 띄우는 통합 테스트
// (도구) @AutoConfigureMockMvc = 그 서버에 요청을 보낼 MockMvc를 준비. @Testcontainers = 도커로 진짜 DB·RabbitMQ를 띄움(도커 없으면 건너뜀)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReviewFlowIntegrationTest {

    // 시험용 렌탈 ID (21자)
    private static final String RENTAL = "RvwRntLv6QRbDILPWOcz5";
    // 시험용 시점(체크포인트) ID
    private static final String CP = "RvwCpQOlTkKj6mOecAQt1";
    // 시험용 사진 해시값
    private static final String SHA = "7f2c4e0b7a1d3c5e8f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e";

    // (도구) @Container = 테스트 동안 도커 컨테이너를 띄웠다가 끝나면 내린다. @ServiceConnection = 서버가 이 컨테이너 주소로 자동 연결
    // 준비: PostgreSQL 16 DB를 trace라는 이름으로 띄운다
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    // 준비: RabbitMQ(메시지 큐)를 띄운다
    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    // 테스트 도구: MinIO 대신 메모리 사진 저장소를 쓰게 바꿔 끼운다.
    // (도구) @TestConfiguration = 테스트에서만 쓰는 스프링 설정. @Bean @Primary = 이 객체를 진짜 대신 우선 쓰게 한다
    @TestConfiguration
    static class Storage {
        @Bean
        @Primary
        FakePhotoStorage fakePhotoStorage() {
            // 메모리 사진 저장소를 만들어 돌려준다
            return new FakePhotoStorage();
        }
    }

    // (도구) @Autowired = 스프링이 만든 객체를 넣어 준다. MockMvc = 서버에 HTTP 요청을 흉내 내 보내는 도구
    @Autowired
    private MockMvc mvc;

    // JSON 글자를 읽는 도구
    @Autowired
    private ObjectMapper json;

    // 메모리 사진 저장소 (사진을 올린 것처럼 넣으려고)
    @Autowired
    private FakePhotoStorage storage;

    // RabbitMQ에서 메시지를 꺼내는 도구
    @Autowired
    private RabbitTemplate rabbit;

    // RabbitMQ에 큐를 만드는 관리 도구
    @Autowired
    private RabbitAdmin rabbitAdmin;

    // [상황] 부스 빠른 검사 세트를 올리고, 탐지 결과로 윗면 긁힘·바닥 찍힘 후보 2개가 온다(확신도 0.31로 낮음).
    //        관리자가 긁힘은 확정, 찍힘은 "테이프"(TAPE)로 기각한 뒤 검수를 완료한다.
    // [기대] - 검수 전: 등급이 없고 후보 2개가 검수 대기(PENDING)이며 대기 목록에 2건으로 나온다. 등급 이벤트는 나가지 않는다.
    //        - 대기가 남은 채 완료하면 409 REVIEW_PENDING_REMAINS(남은 2건). 사유 없는 기각은 400, 고객이 검수하려 하면 403.
    //        - 확정·기각을 저장해도 아직 등급은 없다(대기 0, 기각 1).
    //        - 검수 완료하면 기각한 찍힘을 빼고 긁힘 하나로 등급 B가 난다. 같은 키로 다시 보내면 200, 다른 키는 409 REVIEW_CLOSED.
    //        - 등급 이벤트(스키마 1.3)에 등급 B와 검수한 관리자·기각 1건이 실린다.
    //        - 고객에게는 기각한 찍힘이 보이지 않고 긁힘 하나만 보인다. 검수 완료 목록에도 나온다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 400 = 요청 내용이 형식에 맞지 않음,
    //          403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 200 = 정상 처리됨
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("AI 후보가 있는 빠른 검사는 검수 완료 뒤에만 등급이 나고, 기각한 찍힘은 빠져 긁힘 하나로 B, 이벤트에 검수 기록이 실린다")
    void reviewThenGrade() throws Exception {
        // 준비: 등급 이벤트를 받을 시험용 큐를 만든다
        Queue graded = new Queue("test.review.graded", false, false, false);
        // RabbitMQ에 그 큐를 등록한다
        rabbitAdmin.declareQueue(graded);
        // 전에 남은 메시지가 있으면 비운다 (다른 시험 영향 제거)
        rabbitAdmin.purgeQueue(graded.getName(), false);
        // "시점 등급 완료"(trace.checkpoint.graded) 메시지가 이 큐로 오게 연결한다
        rabbitAdmin.declareBinding(BindingBuilder.bind(graded).to(new TopicExchange("trace.events"))
                .with("trace.checkpoint.graded"));

        // 준비: 부스 빠른 검사 세트를 만들고 완료한다
        String setId = quickSet();
        // 준비: 윗면 긁힘·바닥 찍힘 후보 2개와 4장 모두 분석했다는 목록을 담은 탐지 결과 본문
        String detections = "{\"modelVersion\":\"aug-v002\",\"detections\":["
                + detection("TOP", "scratch", 400) + "," + detection("BOT", "dent", 600) + "],"
                + "\"analyzedPhotos\":[" + String.join(",", analyzed()) + "]}";
        // (도구) mvc.perform(post(주소)) = 서버에 POST 요청을 흉내 내 보낸다. with(booth()) = 부스 토큰을 붙인다
        // (도구) andExpect(status().isCreated()) = 응답이 201(생성됨)이어야 한다는 검사
        // 실행+확인: 부스가 탐지 결과를 보내면 201이어야 한다
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(detections))
                .andExpect(status().isCreated());

        // 등급 보류: grade null, 후보 2개 PENDING, 대기 목록에 나온다
        // 실행: 관리자로 검사 결과를 읽는다
        JsonNode held = inspection(admin());
        // (도구) assertThat(값).isTrue() = 값이 참이어야 한다는 검사
        // 확인: 등급이 아직 없어야(null) 한다 (검수 전이라 보류)
        assertThat(held.get("grade").isNull()).isTrue();
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 검수 대기가 2건이어야 한다
        assertThat(held.get("review").get("pending").asInt()).isEqualTo(2);
        // (도구) allMatch(조건) = 모든 항목이 조건을 만족해야 한다는 검사
        // 확인: 모든 후보가 검수 대기(PENDING)이고, 묶음 ID가 자기 흠집 ID여야 한다 (아직 묶이지 않음)
        assertThat(held.get("photoDefects")).allMatch(d -> "PENDING".equals(d.get("review").get("status").asText())
                && d.get("groupId").asText().equals(d.get("defectId").asText()));
        // (도구) get(주소) = 서버에 GET(조회) 요청을 흉내 낸다
        // 실행: 관리자로 검수 대기 목록을 조회한다
        mvc.perform(get("/api/v1/captures/reviews").with(admin()))
                // (도구) status().isOk() = 응답이 200(정상)이어야 한다는 검사
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // (도구) jsonPath("경로").value(값) = 응답 JSON에서 그 경로의 값이 이것이어야 한다는 검사. [?(@.칸 == 값)] = 조건에 맞는 항목만 고름
                // 확인: 목록에서 이 시점 항목의 대기 수가 2여야 한다
                .andExpect(jsonPath("$.items[?(@.checkpointId == '" + CP + "')].pending").value(2));
        // (도구) isNull() = 값이 비어 있어야(null) 한다는 검사
        // 실행+확인: 0.5초 기다려도 등급 이벤트가 오지 않아야 한다 (등급 보류 중)
        assertThat(rabbit.receive(graded.getName(), 500)).isNull();

        // PENDING이 남은 채로 완료하면 409
        // ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
        // (도구) header(이름, 값) = 요청 헤더를 붙인다. Idempotency-Key = 같은 요청을 두 번 보내도 한 번만 처리되게 하는 키
        // 실행: 대기가 남은 채로 관리자가 검수 완료를 요청한다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-" + CP))
                // (도구) status().isConflict() = 응답이 409여야 한다는 검사
                // 확인: 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 오류 코드는 "검수 대기 남음"이어야 한다
                .andExpect(jsonPath("$.code").value("REVIEW_PENDING_REMAINS"))
                // 확인: 남은 대기 수 2가 함께 와야 한다
                .andExpect(jsonPath("$.pending").value(2));

        // 준비: 긁힘 후보의 ID를 찾는다
        String scratch = idOf(held, "scratch");
        // 준비: 찍힘 후보의 ID를 찾는다
        String dent = idOf(held, "dent");
        // 기각에 사유가 없으면 400
        // ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
        // 실행: 관리자가 찍힘을 사유 없이 기각(DISMISSED)하려 한다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"defectId\":\"" + dent + "\",\"decision\":\"DISMISSED\"}]}"))
                // (도구) status().isBadRequest() = 응답이 400이어야 한다는 검사
                // 확인: 400이어야 한다
                .andExpect(status().isBadRequest());
        // 고객은 검수할 수 없다
        // 실행: 고객 토큰으로 찍힘을 확정하려 한다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews").with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"defectId\":\"" + dent + "\",\"decision\":\"CONFIRMED\"}]}"))
                // (도구) status().isForbidden() = 응답이 403이어야 한다는 검사
                // 확인: 403이어야 한다
                .andExpect(status().isForbidden());

        // 실행: 관리자가 긁힘은 확정, 찍힘은 "테이프(TAPE)" 사유로 기각해 저장한다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"defectId\":\"" + scratch + "\",\"decision\":\"CONFIRMED\"},"
                        + "{\"defectId\":\"" + dent + "\",\"decision\":\"DISMISSED\",\"reason\":\"TAPE\"}]}"))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // (도구) jsonPath("경로").isEmpty() = 그 값이 비어 있어야 한다는 검사
                // 확인: 아직 등급은 비어 있어야 한다 (검수 완료 전)
                .andExpect(jsonPath("$.grade").isEmpty())
                // 확인: 대기는 0건이어야 한다
                .andExpect(jsonPath("$.review.pending").value(0))
                // 확인: 기각은 1건이어야 한다
                .andExpect(jsonPath("$.review.dismissed").value(1));

        // 실행: 관리자가 요청 키 review-CP로 검수 완료를 요청한다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-" + CP))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 기각한 찍힘을 빼고 긁힘 하나로 등급 B가 나야 한다
                .andExpect(jsonPath("$.grade").value("B"))
                // 확인: 확정은 1건이어야 한다
                .andExpect(jsonPath("$.review.confirmed").value(1))
                // (도구) isNotEmpty() = 값이 비어 있지 않아야 한다는 검사
                // 확인: 검수 완료 시각이 기록돼야 한다
                .andExpect(jsonPath("$.review.completedAt").isNotEmpty());
        // 같은 키로 다시 보내도 200, 다른 키는 409
        // ※ 응답 코드: 200 = 정상 처리됨, 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
        // 실행: 같은 요청 키로 검수 완료를 한 번 더 보낸다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-" + CP))
                // 확인: 같은 요청의 재전송이므로 200이어야 한다
                .andExpect(status().isOk());
        // 실행: 다른 요청 키로 검수 완료를 보낸다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-other-" + CP))
                // 확인: 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 오류 코드는 "검수 이미 끝남"이어야 한다
                .andExpect(jsonPath("$.code").value("REVIEW_CLOSED"));

        // 실행: 등급 이벤트를 최대 10초 기다려 꺼낸다
        Message m = rabbit.receive(graded.getName(), 10_000);
        // (도구) as(설명) = 실패할 때 보여 줄 이름. isNotNull() = 비어 있지 않아야 함
        // 확인: 등급 이벤트가 와야 한다
        assertThat(m).as("trace.checkpoint.graded").isNotNull();
        // 메시지 본문을 JSON으로 읽는다
        JsonNode event = json.readTree(m.getBody());
        // 확인: 이벤트 스키마 버전은 1.3이어야 한다 (review 칸이 생긴 버전)
        assertThat(event.get("schemaVersion").asText()).isEqualTo("1.3");
        // 확인: 등급 B가 실려야 한다
        assertThat(event.get("grade").asText()).isEqualTo("B");
        // 확인: 검수한 관리자 ID가 실려야 한다
        assertThat(event.get("review").get("reviewedBy").asText()).isEqualTo("admin0000000000000001");
        // 확인: 기각 1건이 실려야 한다
        assertThat(event.get("review").get("dismissed").asInt()).isEqualTo(1);

        // 고객에게는 기각한 찍힘이 보이지 않는다
        // 실행: 고객으로 검사 결과를 읽는다
        JsonNode forCustomer = inspection(customer());
        // (도구) assertThat(목록).hasSize(n) = 목록 개수가 n이어야 한다는 검사
        // 확인: 고객에게는 흠집이 하나만 보여야 한다
        assertThat(forCustomer.get("photoDefects")).hasSize(1);
        // 확인: 그 하나는 확정한 긁힘이어야 한다 (기각한 찍힘은 안 보임)
        assertThat(forCustomer.get("photoDefects").get(0).get("defectId").asText()).isEqualTo(scratch);

        // 실행: 관리자로 검수 완료 목록을 조회한다
        mvc.perform(get("/api/v1/captures/reviews?status=CONFIRMED").with(admin()))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 목록의 이 시점 항목에 기각 1건이 보여야 한다
                .andExpect(jsonPath("$.items[?(@.checkpointId == '" + CP + "')].dismissed").value(1));
    }

    // 테스트 도구: 주어진 토큰(관리자·고객)으로 그 시점의 검사 결과를 읽는다.
    private JsonNode inspection(org.springframework.test.web.servlet.request.RequestPostProcessor token)
            throws Exception {
        // 받은 토큰으로 검사 결과를 조회해 200인지 확인하고, 응답을 JSON으로 읽어 돌려준다
        return json.readTree(mvc.perform(get("/api/v1/captures/checkpoints/" + CP + "/inspection").with(token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    // 테스트 도구: 검사 결과에서 그 종류(긁힘·찍힘) 흠집의 ID를 찾는다.
    private static String idOf(JsonNode view, String cls) {
        // 사진 위 흠집마다
        for (JsonNode d : view.get("photoDefects")) {
            // 종류가 찾는 것과 같으면
            if (cls.equals(d.get("class").asText())) {
                // 그 흠집 ID를 돌려준다
                return d.get("defectId").asText();
            }
        }
        // 못 찾으면 테스트를 실패시킨다
        throw new AssertionError("흠집이 없습니다: " + cls);
    }

    // 테스트 도구: 이 렌탈을 가진 일반 고객 토큰.
    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        // (도구) jwt() = 진짜 로그인 없이 테스트용 토큰을 만들어 붙이는 도구
        // 고객 ID, 역할 CUSTOMER, 이 렌탈을 가진 토큰을 만들어 돌려준다
        return jwt().jwt(j -> j.subject("cust00000000000000009").claim("role", "CUSTOMER")
                .claim("rentals", List.of(RENTAL)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // 테스트 도구: 부스 토큰으로 빠른 검사 세트(TOP·BOT × CAM1·CAM2)를 만들고, 사진을 올린 것처럼 해 둔 뒤 완료한다.
    private String quickSet() throws Exception {
        // 사진 항목(JSON 글자) 목록
        List<String> photos = new ArrayList<>();
        // 윗면·바닥마다
        for (String face : List.of("TOP", "BOT")) {
            // CAM1·CAM2마다
            for (String cam : List.of("CAM1", "CAM2")) {
                // 면·조명 L1·카메라·해시·jpg·촬영 시각을 담은 사진 항목을 넣는다
                photos.add("{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"" + cam + "\",\"sha256\":\""
                        + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}");
            }
        }
        // 렌탈·시점·부스·빠른 검사·사진 4장을 담은 세트 만들기 본문
        String body = "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"" + CP
                + "\",\"source\":\"BOOTH\",\"profile\":\"QUICK\",\"photos\":[" + String.join(",", photos) + "]}";
        // 부스 토큰으로 세트를 만들고 201인지 확인한 뒤 응답을 JSON으로 읽는다
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/captures/sets").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        // 만들어진 세트 ID를 꺼낸다
        String setId = created.get("captureSetId").asText();
        // 업로드할 사진마다
        for (JsonNode u : created.get("uploads")) {
            // 메모리 저장소에 그 파일 이름으로 사진이 올라간 것처럼 넣는다
            storage.put("captures/" + setId + "/" + u.get("face").asText() + "-" + u.get("light").asText() + "-"
                    + u.get("camera").asText() + ".jpg", SHA);
        }
        // 세트 완료를 요청하고 202(접수됨)인지 확인한다
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/complete").with(booth())
                .header("Idempotency-Key", "key-" + setId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        // 세트 ID를 돌려준다
        return setId;
    }

    // 테스트 도구: 4장 모두 분석했다는 "분석한 사진" 목록을 만든다.
    private static List<String> analyzed() {
        // "분석한 사진" 항목 목록
        List<String> out = new ArrayList<>();
        // 윗면·바닥마다
        for (String face : List.of("TOP", "BOT")) {
            // CAM1·CAM2마다
            for (String cam : List.of("CAM1", "CAM2")) {
                // 면·조명·카메라 항목을 넣는다
                out.add("{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"" + cam + "\"}");
            }
        }
        // 4개 목록을 돌려준다
        return out;
    }

    // 테스트 도구: 그 면 CAM1 사진의 탐지 하나(종류, x 위치의 가로 40px 상자, 확신도 0.31)를 JSON으로 만든다.
    private static String detection(String face, String cls, double x) {
        // 그 면 CAM1 사진 위 (x,x)~(x+40,x+8) 상자, 확신도 0.31인 탐지 JSON을 돌려준다
        return "{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"CAM1\",\"class\":\"" + cls
                + "\",\"polygon\":[[" + x + "," + x + "],[" + (x + 40) + "," + x + "],[" + (x + 40) + "," + (x + 8)
                + "],[" + x + "," + (x + 8) + "]],\"confidence\":0.31}";
    }
}
