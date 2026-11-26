// 이 파일이 확인하는 것: 렌탈 한 건의 출고 → 수령 → 반납 검사·등급 흐름을 실제 PostgreSQL·RabbitMQ로 끝까지 돌려 보는 통합 시험.
// 출고(부스) 흠집이 기준선이 되고, 수령(폰)은 출고와, 반납(부스)은 수령과 비교해 원래 있던 흠집(EXISTING)과 새 흠집(NEW)을 가른다.
// 면 펼치기를 하는 inspect-worker 대신 시험이 "검사 완료" 이벤트를 직접 보낸다. 사진 저장소만 메모리로 바꿨다.
// 사람 검수는 끈 설정(검수 흐름은 ReviewFlowIntegrationTest). Docker가 필요해서 Claude 세션에서는 건너뛰고 GitHub Actions에서 돈다.

package com.gakdo.trace.capture.inspection;

import static com.gakdo.trace.capture.support.TestJwts.admin;
import static com.gakdo.trace.capture.support.TestJwts.booth;
import static com.gakdo.trace.capture.support.TestJwts.capture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.capture.captureset.domain.Photo;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.inspection.infra.InspectEventListener;
import com.gakdo.trace.capture.support.FakePhotoStorage;

/**
 * 실제 PostgreSQL(Flyway V1·V2)·RabbitMQ로 출고 → 수령 → 반납을 돌린다. 사진 저장소만 메모리(사전 서명은 CaptureIntegrationTest).
 * inspect-worker 대신 시험이 trace.inspect.completed를 trace.events에 낸다. Docker가 없으면 건너뛴다.
 */
// (도구) @SpringBootTest = 서버 전체(컨트롤러·서비스·DB 연결·메시지 큐)를 진짜로 띄우는 통합 테스트. properties = 이 시험에서만 쓸 설정
// (도구) @AutoConfigureMockMvc = 그 서버에 요청을 보낼 MockMvc를 준비. @Testcontainers = 도커로 진짜 DB·RabbitMQ를 띄움(도커 없으면 건너뜀)
@SpringBootTest(properties = "app.review.required=false") // 검수 없이 바로 등급(검수 흐름은 ReviewFlowIntegrationTest)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InspectionFlowIntegrationTest {

    // 기다릴 때 다시 확인하는 간격 (0.1초)
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    // 시험용 렌탈 ID
    private static final String RENTAL = "Rnt0HLv6QRbDILPWOcz5I";
    // 다른 시험에 쓸 두 번째 렌탈 ID
    private static final String OTHER_RENTAL = "Rnt1HLv6QRbDILPWOcz5I";
    // 출고 시점 ID
    private static final String CP_OUT = "CpOutQOlTkKj6mOecAQt1";
    // 수령 시점 ID
    private static final String CP_RCV = "CpRcvQOlTkKj6mOecAQt2";
    // 반납 시점 ID
    private static final String CP_RET = "CpRetQOlTkKj6mOecAQt3";
    // 시험용 사진 해시값
    private static final String SHA = "9f2c4e0b7a1d3c5e8f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e";
    // 부스 필수 8면
    private static final List<String> BOOTH_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "KB", "SCR0");
    // 폰 필수 7면
    private static final List<String> PHONE_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "SCR0");
    /** 사진 px → 표준 좌표 px: 절반으로 줄인다. */
    // 좌표를 절반으로 줄이는 3×3 변환 행렬
    private static final List<List<Double>> HALF = List.of(List.of(0.5, 0.0, 0.0), List.of(0.0, 0.5, 0.0),
            List.of(0.0, 0.0, 1.0));

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

    // JSON 글자를 읽고 쓰는 도구
    @Autowired
    private ObjectMapper json;

    // 메모리 사진 저장소 (사진을 올린 것처럼 넣으려고)
    @Autowired
    private FakePhotoStorage storage;

    // RabbitMQ로 메시지를 보내고 꺼내는 도구
    @Autowired
    private RabbitTemplate rabbit;

    // RabbitMQ에 큐를 만드는 관리 도구
    @Autowired
    private RabbitAdmin rabbitAdmin;

    // 세트 저장소 (DB에서 직접 확인하려고)
    @Autowired
    private CaptureSetRepository sets;

    // DB 트랜잭션 관리자 (시험 코드에서 DB를 읽을 때 쓴다)
    @Autowired
    private PlatformTransactionManager txManager;

    // [상황] 한 렌탈을 출고(부스) → 수령(폰) → 반납(부스) 순서로 찍고, 마지막에 시험 데이터 지우기까지 한다.
    // [기대] 단계마다 아래 도우미(shipOut, receiveByPhone, returnToBooth, checkAccessAndMedia, purgeAll)에 적은 결과가 나온다.
    //        요약: 출고는 비교 없이 B, 수령은 같은 긁힘이라 EXISTING으로 B, 반납은 새 긴 긁힘이 NEW로 잡혀 C.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("출고는 기준선, 수령은 출고와·반납은 수령과 비교해 원래 흠집은 EXISTING·새 흠집은 NEW, 등급과 규칙 버전이 남고 이벤트가 나간다")
    void outReceiveReturn() throws Exception {
        // receive()가 소비자를 붙였다 떼므로 자동 삭제 큐를 쓰면 두 번째 receive에서 큐가 없다
        // 준비: 등급 이벤트를 받을 시험용 큐를 만든다
        Queue graded = new Queue("test.checkpoint.graded", false, false, false);
        // RabbitMQ에 그 큐를 등록한다
        rabbitAdmin.declareQueue(graded);
        // 전에 남은 메시지가 있으면 비운다
        rabbitAdmin.purgeQueue(graded.getName(), false);
        // "시점 등급 완료"(trace.checkpoint.graded) 메시지가 이 큐로 오게 연결한다
        rabbitAdmin.declareBinding(BindingBuilder.bind(graded).to(new TopicExchange("trace.events"))
                .with("trace.checkpoint.graded"));

        // 실행+확인: 출고 단계를 돌리고, 수령과 짝지을 윗면 긁힘 ID들을 받는다
        List<String> outTopIds = shipOut(graded.getName());
        // 실행+확인: 수령(폰) 단계를 돌린다
        receiveByPhone(graded.getName(), outTopIds);
        // 실행+확인: 반납(부스) 단계를 돌리고, 반납 세트 ID와 탐지 본문을 받는다
        Returned returned = returnToBooth(graded.getName());
        // 실행+확인: 등급 뒤 재전송·고객 권한·3D 텍스처를 확인한다
        checkAccessAndMedia(returned.setId(), returned.detections());
        // 실행+확인: 시험 렌탈을 지우고 남은 것이 없는지 확인한다
        purgeAll();
    }

    /** 시험 렌탈 지우기(trace.checkpoint.purged): 세 시점을 지우면 매칭·흠집까지 실제 DB에서 모두 사라지고 /inspection은 404. */
    // [단계] 시험 렌탈 지우기: 세 시점에 "시점 지움" 이벤트(TEST- 시리얼)를 보낸다.
    // [기대] 세 시점의 검사 결과가 모두 404가 되고, DB에도 이 렌탈의 세트가 하나도 남지 않는다(짝·흠집까지 지워짐).
    //        ※ 응답 코드: 404 = 찾을 수 없음(없는 주소나 데이터)
    private void purgeAll() throws Exception {
        // 준비: 지울 세 시점
        List<String> checkpoints = List.of(CP_OUT, CP_RCV, CP_RET);
        // 시점마다
        for (String cp : checkpoints) {
            // "시점 지움" 이벤트 내용(시점·렌탈·기기·TEST- 시리얼 등)을 만든다
            Map<String, Object> event = Map.of("schemaVersion", "1.0", "eventId", ("pg" + cp).substring(0, 21),
                    "occurredAt", "2026-11-12T13:06:00+09:00", "rentalId", RENTAL, "checkpointId", cp,
                    "checkpointType", "OUT", "deviceId", "Nq8sUe2ZpW4cYb6Td1MfA", "serial", "TEST-0002");
            // 메시지 속성을 만들고
            MessageProperties props = new MessageProperties();
            // 내용이 JSON이라고 적는다
            props.setContentType("application/json");
            // 실행: trace.events로 "시점 지움" 메시지를 보낸다
            rabbit.send("trace.events", "trace.checkpoint.purged", new Message(json.writeValueAsBytes(event), props));
        }
        // 시점마다
        for (String cp : checkpoints) {
            // 검사 결과 조회가 404가 될 때까지 최대 100번(약 10초)
            for (int i = 0; i < 100 && mvc.perform(get("/api/v1/captures/checkpoints/" + cp + "/inspection")
                    .with(admin())).andReturn().getResponse().getStatus() != 404; i++) {
                // 0.1초 쉬었다가 다시 확인한다
                pause();
            }
            // (도구) mvc.perform(get(주소)) = 서버에 GET 요청을 흉내 낸다. with(admin()) = 관리자 토큰을 붙인다
            // 실행: 그 시점의 검사 결과를 조회한다
            mvc.perform(get("/api/v1/captures/checkpoints/" + cp + "/inspection").with(admin()))
                    // (도구) andExpect(status().isNotFound()) = 응답이 404여야 한다는 검사
                    // 확인: 404여야 한다 (지워짐)
                    .andExpect(status().isNotFound());
        }
        // (도구) TransactionTemplate = 코드 한 덩어리를 DB 트랜잭션 안에서 실행하는 도구
        // (도구) noneMatch(조건) = 조건에 맞는 항목이 하나도 없어야 한다는 검사
        // 확인: DB의 모든 세트 중 이 렌탈의 세트가 하나도 없어야 한다
        new TransactionTemplate(txManager).executeWithoutResult(t ->
                assertThat(sets.findAll()).noneMatch(set -> RENTAL.equals(set.getRentalId())));
    }

    /** 반납 세트 ID와 그 세트에 보낸 탐지 요청 본문. */
    // 테스트 도구: 반납 세트 ID와 그 세트에 보낸 탐지 요청 본문을 묶어 다음 단계로 넘긴다.
    private record Returned(String setId, String detections) {
    }

    /** 출고: 기준선 등급을 확인하고, 수령과 짝지을 수 있는 TOP 긁힘 ID 두 개를 돌려준다. */
    // [단계] 출고(부스 8면 × 두 카메라). 탐지: 윗면 긁힘을 CAM1·CAM2가 같이 봤고 바닥엔 오염. 앞 옆면(SF) CAM2 사진은 면 펼치기 실패.
    // [기대] 등급 B(규칙 grade-rules-v1). 근거에 "출고 기준선(비교할 앞 시점 없음)", "오염 1개는 등급에서 뺌", "판정 불가 면 SF…"가 있다.
    //        흠집 위치는 표준 좌표로 옮겨지고(400px × 0.5 = 200) 길이 10.05mm, 모델 버전이 남으며, 출고라 짝은 없다.
    //        등급 이벤트: 시점 출고(OUT), 새 흠집 없음, 비교 안 함, 판정 불가 면 SF, 사진 16장. 속도·성능 표도 확인한다.
    //        수령과 짝지을 윗면 긁힘 ID 두 개(CAM1·CAM2)를 돌려준다.
    private List<String> shipOut(String gradedQueue) throws Exception {
        // ── 출고: 부스. 탐지 먼저(TOP 긁힘을 CAM1·CAM2가 같이 봄 + BOT 오염), 정합 뒤 등급
        // 준비: 출고 부스 세트를 만들고 완료한다
        JsonNode out = boothSet(RENTAL, CP_OUT);
        // 출고 세트 ID를 꺼낸다
        String outSet = out.get("captureSetId").asText();
        // 준비: 윗면 긁힘(CAM1·CAM2)과 바닥 오염, 부스 탐지 시간 28.4초·전체 91.5초를 담은 탐지 본문
        String outDetections = "{\"modelVersion\":\"yolo11s-seg-v001-trt-fp16\",\"detections\":["
                + detection("TOP", "CAM1", "scratch", 400, 400, 80) + ","
                + detection("TOP", "CAM2", "scratch", 402, 401, 80) + ","
                + detection("BOT", "CAM1", "stain", 600, 600, 60) + "],\"inferenceMs\":28400,\"boothTotalMs\":91500}";
        // (도구) mvc.perform(post(주소)) = 서버에 POST 요청을 흉내 낸다. with(booth()) = 부스 토큰을 붙인다
        // 실행: 부스가 출고 탐지 결과를 보낸다
        mvc.perform(post("/api/v1/captures/sets/" + outSet + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(outDetections))
                // (도구) status().isCreated() = 응답이 201(생성됨)이어야 한다는 검사
                // 확인: 201이어야 한다
                .andExpect(status().isCreated());
        // SF의 CAM2 사진은 정합 실패 → SF 판정 불가
        // 실행: worker 흉내로 "검사 완료"를 보낸다 (SF CAM2 사진만 정합 실패, worker 흠집 없음)
        publishInspected(RENTAL, CP_OUT, outSet, photoIds(outSet, "SF-CAM2"), List.of());
        // 등급이 날 때까지 기다려 출고 검사 결과를 받는다
        JsonNode outResult = awaitGrade(CP_OUT);
        // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
        // 확인: 출고 등급은 B여야 한다
        assertThat(outResult.get("grade").asText()).isEqualTo("B");
        // 확인: 규칙 버전이 남아야 한다
        assertThat(outResult.get("ruleVersion").asText()).isEqualTo("grade-rules-v1");
        // 확인: 속도·성능 표도 확인한다
        checkBenchmark(outSet);
        // (도구) contains(...) = 이 값들이 들어 있어야 함. anyMatch(조건) = 하나라도 조건에 맞아야 함
        // 확인: 근거에 출고 기준선·오염 제외·판정 불가 면 SF 문장이 있어야 한다
        assertThat(texts(outResult.get("reasons"))).contains("출고 기준선(비교할 앞 시점 없음)", "오염 1개는 등급에서 뺌")
                .anyMatch(r -> r.startsWith("판정 불가 면 SF"));
        // 출고 첫 흠집(윗면 긁힘)을 꺼낸다
        JsonNode outTop = outResult.get("defects").get(0);
        // 확인: 위치가 표준 좌표로 옮겨져 x가 200이어야 한다
        assertThat(outTop.get("polygon").get(0).get(0).asDouble()).isEqualTo(200.0);   // 400 px × 0.5
        // 확인: 길이는 10.05mm여야 한다
        assertThat(outTop.get("lengthMm").asDouble()).isEqualTo(10.05);
        // 확인: 탐지 모델 버전이 남아야 한다
        assertThat(outTop.get("modelVersion").asText()).isEqualTo("yolo11s-seg-v001-trt-fp16");
        // (도구) isTrue() / isFalse() = 값이 참 / 거짓이어야 한다는 검사
        // 확인: 출고라 짝(match)이 없어야 한다
        assertThat(outTop.get("match").isNull()).isTrue();   // 기준선은 짝을 만들지 않는다
        // 출고 등급 이벤트를 받는다
        JsonNode outEvent = receive(gradedQueue);
        // 확인: 시점 종류는 출고(OUT)여야 한다
        assertThat(outEvent.get("checkpointType").asText()).isEqualTo("OUT");
        // (도구) isEmpty() = 비어 있어야 한다는 검사
        // 확인: 새 흠집 목록이 비어 있어야 한다
        assertThat(outEvent.get("newDefectIds")).isEmpty();
        // 확인: 비교하지 않았다는 표시여야 한다
        assertThat(outEvent.get("compared").asBoolean()).isFalse(); // 출고는 비교 대상이 없다
        // (도구) containsExactly(...) = 정확히 이 값들만 이 순서로 있어야 한다는 검사
        // 확인: 판정 불가 면은 SF 하나여야 한다
        assertThat(texts(outEvent.get("unjudgeableFaces"))).containsExactly("SF");
        // (도구) hasSize(n) = 개수가 n이어야 한다는 검사
        // 확인: 사진 16장이 실려야 한다
        assertThat(outEvent.get("photos")).hasSize(BOOTH_FACES.size() * 2);
        // 같은 긁힘을 CAM1·CAM2가 같이 봤으므로 둘 중 어느 쪽과 짝지어도 맞다
        // 윗면 긁힘 두 개(CAM1·CAM2)의 ID를 돌려준다
        return List.of(outTop.get("defectId").asText(),
                outResult.get("defects").get(1).get("defectId").asText());
    }

    /** 성능 그래프: 부스가 보낸 탐지 시간과 완료→등급 시간이 세트 행 하나로 나온다(관리자만). */
    // [단계] 관리자 화면 속도·성능 표를 확인한다.
    // [기대] 출고 세트 한 줄에 부스가 보낸 탐지 시간(28.4초)·부스 전체 시간(91.5초)·모델 버전·사진 16장·완료·등급 시각이 나온다.
    //        부스 토큰은 403, 한 번에 101개 이상 요청하면 400이다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 400 = 요청 내용이 형식에 맞지 않음
    private void checkBenchmark(String setId) throws Exception {
        // (도구) status().isOk() = 응답이 200이어야 한다는 검사
        // 실행: 관리자로 부스 성능 표(최대 100줄)를 조회해 200인지 확인하고 줄 목록을 꺼낸다
        JsonNode items = json.readTree(mvc.perform(get("/api/v1/captures/benchmarks?source=BOOTH&limit=100")
                .with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("items");
        // 출고 세트의 줄을 담을 자리
        JsonNode row = null;
        // 줄마다
        for (JsonNode item : items) {
            // 확인: 모두 부스 줄이어야 한다 (출처 거르기)
            assertThat(item.get("source").asText()).isEqualTo("BOOTH");
            // 출고 세트의 줄이면
            if (setId.equals(item.get("captureSetId").asText())) {
                // 그 줄을 기억한다
                row = item;
            }
        }
        // (도구) isNotNull() = 비어 있지 않아야 한다는 검사
        // 확인: 출고 세트 줄이 있어야 한다
        assertThat(row).isNotNull();
        // 확인: 탐지 시간 28.4초
        assertThat(row.get("inferenceMs").asInt()).isEqualTo(28400);
        // 확인: 부스 전체 시간 91.5초
        assertThat(row.get("boothTotalMs").asInt()).isEqualTo(91500);
        // 확인: 모델 버전
        assertThat(row.get("modelVersion").asText()).isEqualTo("yolo11s-seg-v001-trt-fp16");
        // 확인: 사진 16장
        assertThat(row.get("photoCount").asInt()).isEqualTo(BOOTH_FACES.size() * 2);
        // 확인: 완료 시각이 있어야 한다
        assertThat(row.get("completedAt").isNull()).isFalse();
        // 확인: 등급 시각이 있어야 한다
        assertThat(row.get("gradedAt").isNull()).isFalse();
        // (도구) isNotNegative() = 0 이상이어야 한다는 검사
        // 확인: 완료→등급 걸린 시간이 음수가 아니어야 한다
        assertThat(row.get("completedToGradedMs").asLong()).isNotNegative();
        // (도구) status().isForbidden() = 응답이 403이어야 한다는 검사
        // 실행+확인: 부스 토큰으로 성능 표를 보면 403이어야 한다
        mvc.perform(get("/api/v1/captures/benchmarks").with(booth())).andExpect(status().isForbidden());
        // (도구) status().isBadRequest() = 응답이 400이어야 한다는 검사
        // 실행+확인: 101줄을 요청하면 400이어야 한다
        mvc.perform(get("/api/v1/captures/benchmarks?limit=101").with(admin())).andExpect(status().isBadRequest());
    }

    /** 수령: 출고와 같은 자리 긁힘은 EXISTING. */
    // [단계] 수령(폰 7면). worker가 출고 때와 같은 자리에서 윗면 긁힘을 찾았다.
    // [기대] 등급 B, 그 긁힘은 출고 흠집과 짝지어진 EXISTING(원래 있던 것)이다. 이벤트는 수령(RCV), 새 흠집 없음.
    private void receiveByPhone(String gradedQueue, List<String> outTopIds) throws Exception {
        // ── 수령: 폰. worker가 표준 좌표로 같은 자리 긁힘을 탐지 → EXISTING
        // 준비: 수령 폰 세트를 만들고 완료한다
        String rcvSet = phoneSet(CP_RCV);
        // 준비: 윗면 폰 사진 ID
        String topPhoto = photoIds(rcvSet, "TOP-PHONE").get(0);
        // 실행: worker 흉내로 출고와 같은 자리(201,201) 윗면 긁힘을 담은 "검사 완료"를 보낸다
        publishInspected(RENTAL, CP_RCV, rcvSet, List.of(), List.of(Map.of("defectId", "dRcvTopScratch0000001",
                "photoId", topPhoto, "class", "scratch",
                "polygon", rect(201, 201, 40, 4), "confidence", 0.9, "lengthMm", 10.0)));
        // 등급이 날 때까지 기다려 수령 검사 결과를 받는다
        JsonNode rcvResult = awaitGrade(CP_RCV);
        // 확인: 수령 등급은 B여야 한다
        assertThat(rcvResult.get("grade").asText()).isEqualTo("B");
        // 수령 첫 흠집을 꺼낸다
        JsonNode rcvTop = rcvResult.get("defects").get(0);
        // 확인: 원래 있던 흠집(EXISTING)이어야 한다
        assertThat(rcvTop.get("match").get("status").asText()).isEqualTo("EXISTING");
        // (도구) isIn(목록) = 값이 목록 안에 있어야 한다는 검사
        // 확인: 짝은 출고 윗면 긁힘 둘 중 하나여야 한다
        assertThat(rcvTop.get("match").get("prevDefectId").asText()).isIn(outTopIds);
        // 수령 등급 이벤트를 받는다
        JsonNode rcvEvent = receive(gradedQueue);
        // 확인: 시점 종류는 수령(RCV)이어야 한다
        assertThat(rcvEvent.get("checkpointType").asText()).isEqualTo("RCV");
        // 확인: 새 흠집이 없어야 한다
        assertThat(rcvEvent.get("newDefectIds")).isEmpty();
    }

    /** 반납: 새 긴 긁힘으로 C. 세트 ID와 탐지 요청 본문을 돌려준다. */
    // [단계] 반납(부스). 면 펼치기 결과가 먼저 오고(INSPECTED, 아직 등급 없음), 그 뒤 탐지가 온다:
    //        수령 때와 같은 자리 긁힘 + 새 긴 긁힘(약 42.5mm).
    // [기대] 탐지가 오자 바로 등급 C, 근거 "신규 긁힘 1개(43mm)". 첫 긁힘은 수령 흠집과 짝지어진 EXISTING, 긴 긁힘은 NEW다.
    //        이벤트(스키마 1.3): 반납(RET), 등급 C, 새 흠집 ID = 긴 긁힘, 비교함, 검수 끈 설정이라 review 없음.
    //        핀은 흠집 묶음마다 하나(2개, 모두 윗면)이고 새 흠집 핀의 ID는 새 흠집 ID 목록과 같다.
    private Returned returnToBooth(String gradedQueue) throws Exception {
        // ── 반납: 부스. 정합이 먼저 오면 탐지를 기다리고, 탐지가 오면 바로 등급. 새 긴 긁힘(42.5 mm) → C
        // 준비: 반납 부스 세트를 만들고 완료한다
        JsonNode ret = boothSet(RENTAL, CP_RET);
        // 반납 세트 ID를 꺼낸다
        String retSet = ret.get("captureSetId").asText();
        // 실행: 탐지보다 먼저 "검사 완료"(정합만, worker 흠집 없음)를 보낸다
        publishInspected(RENTAL, CP_RET, retSet, List.of(), List.of());
        // 세트가 INSPECTED가 될 때까지 기다린다
        awaitStatus(retSet, "INSPECTED");
        // 실행+확인: 반납 검사 결과를 조회하면 아직 등급이 비어 있어야 한다 (탐지를 기다림)
        assertThat(json.readTree(mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/inspection")
                .with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("grade").isNull()).isTrue();
        // 준비: 수령과 같은 자리 긁힘 + 새 긴 긁힘(340px)을 담은 탐지 본문
        String retDetections = "{\"modelVersion\":\"yolo11s-seg-v001-trt-fp16\",\"detections\":["
                + detection("TOP", "CAM1", "scratch", 401, 400, 80) + ","
                + detection("TOP", "CAM1", "scratch", 1000, 1000, 340) + "]}";
        // 실행: 부스가 반납 탐지 결과를 보낸다
        mvc.perform(post("/api/v1/captures/sets/" + retSet + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(retDetections))
                // 확인: 201이어야 한다
                .andExpect(status().isCreated());
        // 등급이 날 때까지 기다려 반납 검사 결과를 받는다
        JsonNode retResult = awaitGrade(CP_RET);
        // 확인: 반납 등급은 C여야 한다
        assertThat(retResult.get("grade").asText()).isEqualTo("C");
        // 확인: 근거에 "신규 긁힘 1개(43mm)"가 있어야 한다
        assertThat(texts(retResult.get("reasons"))).contains("신규 긁힘 1개(43mm)");
        // 흠집별 비교 결과를 모을 목록
        List<String> statuses = new ArrayList<>();
        // 흠집마다 비교 결과(EXISTING/NEW)를 모은다
        retResult.get("defects").forEach(d -> statuses.add(d.get("match").get("status").asText()));
        // 확인: 첫 긁힘은 EXISTING, 긴 긁힘은 NEW여야 한다
        assertThat(statuses).containsExactly("EXISTING", "NEW");
        // 확인: 첫 긁힘의 짝은 수령 흠집이어야 한다
        assertThat(retResult.get("defects").get(0).get("match").get("prevDefectId").asText())
                .isEqualTo("dRcvTopScratch0000001");
        // 반납 등급 이벤트를 받는다
        JsonNode retEvent = receive(gradedQueue);
        // 확인: 시점 종류는 반납(RET)이어야 한다
        assertThat(retEvent.get("checkpointType").asText()).isEqualTo("RET");
        // 확인: 등급 C가 실려야 한다
        assertThat(retEvent.get("grade").asText()).isEqualTo("C");
        // 확인: 새 흠집 ID는 긴 긁힘 하나여야 한다
        assertThat(texts(retEvent.get("newDefectIds")))
                .containsExactly(retResult.get("defects").get(1).get("defectId").asText());
        // 1.1 핀: 묶음마다 하나, 신규 핀의 defectId는 newDefectIds와 같다
        // 확인: 스키마 버전은 1.3이어야 한다
        assertThat(retEvent.get("schemaVersion").asText()).isEqualTo("1.3");
        // 검수를 끈 설정(app.review.required=false)에서는 review를 싣지 않는다
        // 확인: review 칸이 없어야 한다
        assertThat(retEvent.has("review")).isFalse();
        // 확인: 앞 시점과 비교했다는 표시여야 한다
        assertThat(retEvent.get("compared").asBoolean()).isTrue();
        // 확인: 핀은 2개여야 한다
        assertThat(retEvent.get("pins")).hasSize(2);
        // 새 흠집 핀 ID를 모을 목록
        List<String> newPins = new ArrayList<>();
        // 핀마다
        retEvent.get("pins").forEach(pin -> {
            // 확인: 모두 윗면이어야 한다
            assertThat(pin.get("face").asText()).isEqualTo("TOP");
            // 새 흠집 핀이면
            if (pin.get("isNew").asBoolean()) {
                // 그 ID를 모은다
                newPins.add(pin.get("defectId").asText());
            }
        });
        // (도구) containsExactlyElementsOf(목록) = 다른 목록과 정확히 같은 값·순서여야 한다는 검사
        // 확인: 새 흠집 핀 ID들이 새 흠집 ID 목록과 같아야 한다
        assertThat(newPins).containsExactlyElementsOf(texts(retEvent.get("newDefectIds")));
        // 반납 세트 ID와 탐지 본문을 묶어 돌려준다
        return new Returned(retSet, retDetections);
    }

    /** 등급 뒤 재전송·고객 범위·3D 트윈 텍스처. */
    // [단계] 등급 뒤 재전송·고객 권한·3D 텍스처를 확인한다.
    // [기대] 등급이 난 세트에 탐지를 다시 보내면 409. 본인 렌탈 고객은 검사 결과를 보고, 남의 렌탈 고객은 403.
    //        미디어 조회는 윗면 텍스처 1개를 가공 이미지 버킷(trace-derived) 서명 URL로 주고, 남의 고객은 403, 없는 시점은 404.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등), 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일),
    //          404 = 찾을 수 없음(없는 주소나 데이터)
    private void checkAccessAndMedia(String retSet, String retDetections) throws Exception {
        // 등급이 매겨진 세트에 다시 보내면 409, 본인 렌탈이 아닌 고객은 403
        // 실행: 등급이 난 반납 세트에 탐지를 다시 보낸다
        mvc.perform(post("/api/v1/captures/sets/" + retSet + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(retDetections))
                // (도구) status().isConflict() = 응답이 409여야 한다는 검사
                // 확인: 409여야 한다
                .andExpect(status().isConflict());
        // 실행: 본인 렌탈 고객이 반납 검사 결과를 본다
        mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/inspection").with(customer(RENTAL)))
                // 확인: 200이어야 한다
                .andExpect(status().isOk());
        // 실행: 남의 렌탈 고객이 본다
        mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/inspection").with(customer("Other0HLv6QRbDILPWOcz")))
                // 확인: 403이어야 한다
                .andExpect(status().isForbidden());

        // 3D 트윈 텍스처: 정합 이벤트의 textureKeys가 trace-derived 사전 서명 GET URL로 나간다
        // 실행: 본인 고객이 반납 시점의 미디어(텍스처)를 조회해 200인지 확인하고 JSON으로 읽는다
        JsonNode media = json.readTree(mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/media")
                .with(customer(RENTAL))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // 확인: 시점 ID가 맞아야 한다
        assertThat(media.get("checkpointId").asText()).isEqualTo(CP_RET);
        // 확인: URL 만료 시각이 있어야 한다
        assertThat(media.get("expiresAt").isNull()).isFalse();
        // 확인: 텍스처는 1개여야 한다
        assertThat(media.get("textures")).hasSize(1);
        // 확인: 그 텍스처는 윗면이어야 한다
        assertThat(media.get("textures").get(0).get("face").asText()).isEqualTo("TOP");
        // (도구) startsWith(글자) = 그 글자로 시작해야 한다는 검사
        // 확인: URL이 가공 이미지 버킷(trace-derived)의 서명 주소로 시작해야 한다
        assertThat(media.get("textures").get(0).get("url").asText())
                .startsWith("https://minio.test/trace-derived/textures/" + retSet + "/TOP.jpg?");
        // 확인: 잘라낸 이미지(crops)는 없어야 한다
        assertThat(media.get("crops")).isEmpty();
        // 실행: 남의 고객이 미디어를 조회한다
        mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/media").with(customer("Other0HLv6QRbDILPWOcz")))
                // 확인: 403이어야 한다
                .andExpect(status().isForbidden());
        // 실행: 없는 시점의 미디어를 조회한다
        mvc.perform(get("/api/v1/captures/checkpoints/CpNoneQOlTkKj6mOecAQt/media").with(admin()))
                // 확인: 404여야 한다
                .andExpect(status().isNotFound());
    }

    // [상황] 같은 부스 세트에 탐지를 두 번 보낸다(처음 2개, 다음엔 키보드 깨짐 1개). 세트에 없는 흰 화면(SCR1) 사진을 가리키는 탐지도 보내고,
    //        다른 부스(booth-99) 토큰으로도 보낸다.
    // [기대] 두 번째 요청이 첫 번째를 통째로 바꾼다(받은 수 2 → 1). 없는 사진을 가리키면 400, 다른 부스는 403이다.
    //        면 펼치기 뒤 등급이 나면 마지막 요청의 깨짐 하나만 남아 D다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("부스 탐지는 같은 세트에 다시 오면 통째로 바뀌고, 세트에 없는 사진을 가리키면 400, 다른 부스는 403이다")
    void detectionsReplaceAndValidate() throws Exception {
        // 준비: 두 번째 렌탈로 부스 세트를 만들고 세트 ID를 꺼낸다
        String setId = boothSet(OTHER_RENTAL, "CpDetQOlTkKj6mOecAQt4").get("captureSetId").asText();
        // 준비: 윗면 긁힘·바닥 찍힘 2개짜리 탐지 본문
        String two = "{\"modelVersion\":\"m1\",\"detections\":[" + detection("TOP", "CAM1", "scratch", 10, 10, 40)
                + "," + detection("BOT", "CAM2", "dent", 10, 10, 40) + "]}";
        // 준비: 키보드 깨짐 1개짜리 탐지 본문
        String one = "{\"modelVersion\":\"m2\",\"detections\":[" + detection("KB", "CAM2", "crack", 10, 10, 40) + "]}";
        // 실행+확인: 2개짜리를 보내면 받은 수가 2여야 한다
        assertThat(accepted(setId, two)).isEqualTo(2);
        // 실행+확인: 1개짜리를 다시 보내면 받은 수가 1이어야 한다 (통째로 바뀜)
        assertThat(accepted(setId, one)).isEqualTo(1);

        // 준비: 세트에 없는 SCR1 사진을 가리키는 탐지 본문
        String missing = "{\"modelVersion\":\"m3\",\"detections\":["
                + detection("SCR1", "CAM1", "scratch", 10, 10, 40) + "]}";
        // 실행: 그 본문을 보낸다
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(missing))
                // 확인: 400이어야 한다
                .andExpect(status().isBadRequest());
        // 실행: 다른 부스(booth-99) 토큰으로 보낸다
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth("booth-99"))
                .contentType(MediaType.APPLICATION_JSON).content(one))
                // 확인: 403이어야 한다
                .andExpect(status().isForbidden());

        // 정합 뒤 등급이 나면 마지막 요청(균열 1개)만 남아 D
        // 실행: worker 흉내로 "검사 완료"를 보낸다
        publishInspected(OTHER_RENTAL, "CpDetQOlTkKj6mOecAQt4", setId, List.of(), List.of());
        // 등급이 날 때까지 기다려 결과를 받는다
        JsonNode result = awaitGrade("CpDetQOlTkKj6mOecAQt4");
        // 확인: 흠집은 1개만 남아야 한다
        assertThat(result.get("defects")).hasSize(1);
        // 확인: 그 흠집은 깨짐이어야 한다
        assertThat(result.get("defects").get(0).get("class").asText()).isEqualTo("crack");
        // 확인: 등급은 D여야 한다
        assertThat(result.get("grade").asText()).isEqualTo("D");
    }

    // [상황] inspect-worker의 "검사 완료" 이벤트로 ① 없는 세트 ② JSON이 아닌 메시지 ③ 3×3이 아닌 변환 행렬을 보낸다.
    //        그다음 정상 이벤트로 등급을 내고, 같은 세트에 이벤트를 한 번 더 보낸다.
    // [기대] ①②③은 다시 시도하지 않고 바로 실패함(DLX)으로 보낸다.
    //        정상 이벤트로 등급 A가 나고, 이미 처리한 세트에 다시 온 이벤트는 무시한다.
    @Test
    @DisplayName("없는 세트·3×3이 아닌 행렬의 검사 완료 이벤트는 재시도하지 않고 DLX로 보내고, 이미 처리한 세트에 다시 오면 무시한다")
    void invalidAndDuplicateEvents() throws Exception {
        // 준비: 없는 세트 ID를 가리키는 "검사 완료" 이벤트 본문 (모르는 칸 extraField도 있음)
        String missing = "{\"schemaVersion\":\"1.0\",\"eventId\":\"evMissing000000000001\",\"rentalId\":\""
                + OTHER_RENTAL + "\",\"checkpointId\":\"CpMisQOlTkKj6mOecAQt5\",\"captureSetId\":\"NoSuchSet000000000001\","
                + "\"modelVersion\":\"m\",\"alignments\":[],\"defects\":[],\"textureKeys\":{},\"extraField\":1}";
        // 글자를 바이트로 바꾼다
        byte[] missingBody = missing.getBytes();
        // 준비: JSON이 아닌 메시지
        byte[] notJson = "not json".getBytes();
        // (도구) assertThatThrownBy(() -> 코드).isInstanceOf(종류) = 그 코드를 실행하면 이 종류의 예외가 나야 한다는 검사
        // 실행+확인: ① 없는 세트 이벤트는 "다시 시도하지 말고 버림" 예외가 나야 한다 (실패함으로 감)
        assertThatThrownBy(() -> listener.handle(missingBody))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        // 실행+확인: ② JSON이 아닌 메시지도 같은 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(notJson))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        // 준비: 이 시험용 시점 ID
        String checkpoint = "CpDupQOlTkKj6mOecAQt6";
        // 준비: 폰 세트를 만들고 완료한다
        String setId = phoneSetIn(OTHER_RENTAL, checkpoint);
        // 준비: 첫 사진 ID
        String photo = allPhotoIds(setId).get(0);
        // 준비: 2×2 행렬이 든 "검사 완료" 이벤트 본문
        String badMatrix = "{\"schemaVersion\":\"1.0\",\"eventId\":\"evBadMatrix0000000001\",\"rentalId\":\""
                + OTHER_RENTAL + "\",\"checkpointId\":\"" + checkpoint + "\",\"captureSetId\":\"" + setId + "\","
                + "\"modelVersion\":\"m\",\"alignments\":[{\"photoId\":\"" + photo + "\",\"method\":\"CONTOUR\","
                + "\"homography\":[[1,0],[0,1]],\"errorPx\":null,\"rectifiedKey\":\"r.jpg\"}],\"defects\":[],"
                + "\"textureKeys\":{}}";
        // 글자를 바이트로 바꾼다
        byte[] badMatrixBody = badMatrix.getBytes();
        // 실행+확인: ③ 3×3이 아닌 행렬 이벤트도 같은 예외가 나야 한다
        assertThatThrownBy(() -> listener.handle(badMatrixBody))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        // 실행: 정상 "검사 완료" 이벤트를 보낸다
        publishInspected(OTHER_RENTAL, checkpoint, setId, List.of(), List.of());
        // 등급이 날 때까지 기다린다
        JsonNode first = awaitGrade(checkpoint);
        // 확인: 흠집이 없으니 등급 A여야 한다
        assertThat(first.get("grade").asText()).isEqualTo("A");
        // 준비: 같은 세트에 대한 이벤트를 하나 더 만든다
        String again = "{\"schemaVersion\":\"1.0\",\"eventId\":\"evAgain00000000000001\",\"rentalId\":\""
                + OTHER_RENTAL + "\",\"checkpointId\":\"" + checkpoint + "\",\"captureSetId\":\"" + setId + "\","
                + "\"modelVersion\":\"m\",\"alignments\":[],\"defects\":[],\"textureKeys\":{}}";
        // 실행+확인: 다시 처리하면 "바뀐 것 없음(false)"이어야 한다 (이미 처리한 세트는 무시)
        assertThat(listener.handle(again.getBytes())).isFalse();
    }

    // ── 도우미

    // "검사 완료" 이벤트를 받는 리스너 (직접 불러 보려고)
    @Autowired
    private InspectEventListener listener;

    // 테스트 도구: 그 렌탈·시점으로 폰 7면 세트를 만들고 올린 뒤 완료한다.
    private String phoneSetIn(String rental, String checkpointId) throws Exception {
        // 폰 7면 사진 항목(실내 조명)을 만든다
        List<String> photos = PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList();
        // 촬영 토큰으로 세트를 만들고 완료한 뒤 세트 ID를 돌려준다
        return createAndComplete(rental, checkpointId, "PHONE", photos, capture(checkpointId, rental))
                .get("captureSetId").asText();
    }

    // 테스트 도구: 부스 탐지를 보내고 서버가 받은 탐지 수를 돌려준다.
    private int accepted(String setId, String body) throws Exception {
        // 탐지를 보내 201인지 확인하고 응답의 accepted(받은 수)를 돌려준다
        return json.readTree(mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("accepted").asInt();
    }

    // 테스트 도구: 그 렌탈을 가진 일반 고객 토큰.
    private static RequestPostProcessor customer(String rental) {
        // (도구) jwt() = 진짜 로그인 없이 테스트용 토큰을 만들어 붙이는 도구
        // 고객 ID, 역할 CUSTOMER, 받은 렌탈을 가진 토큰을 만들어 돌려준다
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(rental)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** 부스 세트(면 8개 × CAM1·CAM2, 조명 L1)를 만들고 올린 뒤 완료한다. */
    // 테스트 도구: 부스 8면 × CAM1·CAM2(L1) 세트를 만들고 올린 뒤 완료한다.
    private JsonNode boothSet(String rental, String checkpointId) throws Exception {
        // 사진 항목 목록
        List<String> photos = new ArrayList<>();
        // 부스 8면마다
        for (String face : BOOTH_FACES) {
            // CAM1·CAM2마다
            for (String cam : List.of("CAM1", "CAM2")) {
                // 조명 L1 사진 항목을 넣는다
                photos.add(photo(face, "L1", cam));
            }
        }
        // 부스 토큰으로 세트를 만들고 완료한다
        return createAndComplete(rental, checkpointId, "BOOTH", photos, booth());
    }

    // 테스트 도구: 기본 렌탈로 폰 7면 세트를 만들고 올린 뒤 완료한다.
    private String phoneSet(String checkpointId) throws Exception {
        // 폰 7면 사진 항목을 만든다
        List<String> photos = PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList();
        // 기본 렌탈로 세트를 만들고 완료한 뒤 세트 ID를 돌려준다
        return createAndComplete(RENTAL, checkpointId, "PHONE", photos, capture(checkpointId, RENTAL))
                .get("captureSetId").asText();
    }

    // 테스트 도구: 세트를 만들고, 사진이 올라간 것처럼 메모리 저장소에 넣은 뒤, 완료 요청까지 보낸다.
    private JsonNode createAndComplete(String rental, String checkpointId, String source, List<String> photos,
            RequestPostProcessor token) throws Exception {
        // 렌탈·시점·출처·사진 목록을 담은 세트 만들기 본문
        String body = "{\"rentalId\":\"" + rental + "\",\"checkpointId\":\"" + checkpointId + "\",\"source\":\""
                + source + "\",\"photos\":[" + String.join(",", photos) + "]}";
        // 받은 토큰으로 세트를 만들고 201인지 확인한 뒤 응답을 JSON으로 읽는다
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/captures/sets").with(token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        // 세트 ID를 꺼낸다
        String setId = created.get("captureSetId").asText();
        // 업로드할 사진마다
        for (JsonNode u : created.get("uploads")) {
            // 메모리 저장소에 그 파일 이름으로 사진이 올라간 것처럼 넣는다
            storage.put("captures/" + setId + "/" + u.get("face").asText() + "-" + u.get("light").asText() + "-"
                    + u.get("camera").asText() + ".jpg", SHA);
        }
        // 세트 완료를 요청하고 202(접수됨)인지 확인한다
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/complete").with(token)
                .header("Idempotency-Key", "key-" + setId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        // 세트 만들기 응답을 돌려준다
        return created;
    }

    // 테스트 도구: 세트를 만들 때 알리는 사진 한 장(면·조명·카메라·해시) JSON.
    private static String photo(String face, String light, String camera) {
        // 면·조명·카메라·해시·jpg·촬영 시각을 담은 사진 항목 JSON을 돌려준다
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\",\"sha256\":\""
                + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}";
    }

    // 테스트 도구: 부스 탐지 하나(면·카메라·종류, (x, y)에서 가로 w·세로 8px 상자, 확신도 0.88) JSON.
    private static String detection(String face, String camera, String cls, double x, double y, double w) {
        // (x, y)부터 가로 w·세로 8px 상자, 확신도 0.88인 탐지 JSON을 돌려준다
        return "{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"" + camera + "\",\"class\":\"" + cls
                + "\",\"polygon\":[[" + x + "," + y + "],[" + (x + w) + "," + y + "],[" + (x + w) + "," + (y + 8)
                + "],[" + x + "," + (y + 8) + "]],\"confidence\":0.88}";
    }

    // 테스트 도구: 표준 좌표 직사각형 모양.
    private static List<List<Double>> rect(double x, double y, double w, double h) {
        // 네 꼭짓점 목록을 돌려준다
        return List.of(List.of(x, y), List.of(x + w, y), List.of(x + w, y + h), List.of(x, y + h));
    }

    /** "SF-CAM2"처럼 면-카메라로 고른 사진 ID. */
    // 테스트 도구: "SF-CAM2"처럼 면-카메라로 고른 사진들의 ID를 찾는다.
    private List<String> photoIds(String setId, String... faceCamera) throws Exception {
        // 관리자로 세트를 조회해 JSON으로 읽는다
        JsonNode set = json.readTree(mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                .andReturn().getResponse().getContentAsString());
        // 찾을 "면-카메라" 목록
        List<String> wanted = List.of(faceCamera);
        // 찾은 사진 ID 목록
        List<String> ids = new ArrayList<>();
        // 세트 사진마다
        for (JsonNode p : set.get("photos")) {
            // "면-카메라"가 찾는 목록에 있으면
            if (wanted.contains(p.get("face").asText() + "-" + p.get("camera").asText())) {
                // 그 사진 ID를 DB에서 찾아 넣는다
                ids.add(photoId(setId, p.get("face").asText(), p.get("light").asText(), p.get("camera").asText()));
            }
        }
        // 찾은 ID 목록을 돌려준다
        return ids;
    }

    // 테스트 도구: 면·조명·카메라가 맞는 사진 하나의 ID를 DB에서 찾는다.
    private String photoId(String setId, String face, String light, String camera) {
        // 트랜잭션 안에서 세트를 읽어 면·조명·카메라가 맞는 첫 사진의 ID를 돌려준다
        return new TransactionTemplate(txManager).execute(s ->
                sets.findByPublicId(setId).orElseThrow().getPhotos().stream()
                        .filter(p -> p.getFace().name().equals(face) && p.getLight().name().equals(light)
                                && p.getCamera().name().equals(camera))
                        .findFirst().orElseThrow().getPublicId());
    }

    // 테스트 도구: 세트의 모든 사진 ID를 DB에서 읽는다.
    private List<String> allPhotoIds(String setId) {
        // 트랜잭션 안에서 세트를 읽어 모든 사진 ID 목록을 돌려준다
        return new TransactionTemplate(txManager).execute(s ->
                sets.findByPublicId(setId).orElseThrow().getPhotos().stream().map(Photo::getPublicId).toList());
    }

    /** inspect-worker 흉내: failed를 뺀 모든 사진을 HALF로 정합하고 worker 탐지(defects)를 싣는다. */
    // 테스트 도구: inspect-worker 흉내. 실패로 정한 사진을 뺀 모든 사진을 "절반 축소" 변환으로 면 맞춤 했다고 하고,
    //        worker가 찾은 흠집과 윗면 텍스처를 실어 "검사 완료" 이벤트를 보낸다.
    private void publishInspected(String rental, String checkpointId, String setId, List<String> failed,
            List<Map<String, Object>> defects) throws Exception {
        // 면 맞춤 결과 목록
        List<Map<String, Object>> alignments = new ArrayList<>();
        // 세트의 사진마다
        for (String id : allPhotoIds(setId)) {
            // 실패로 정한 사진이 아니면
            if (!failed.contains(id)) {
                // 윤곽 방식·절반 축소 행렬·오차 1.5px·펼친 이미지 경로로 성공 결과를 넣는다
                alignments.add(Map.of("photoId", id, "method", "CONTOUR", "homography", HALF, "errorPx", 1.5,
                        "rectifiedKey", "rectified/" + setId + "/" + id + ".jpg"));
            }
        }
        // 이벤트 내용 (넣은 순서를 지키는 맵)
        Map<String, Object> event = new LinkedHashMap<>();
        // 스키마 버전
        event.put("schemaVersion", "1.0");
        // 이벤트 ID ("ev" + 세트 ID 앞부분, 21자)
        event.put("eventId", ("ev" + setId).substring(0, 21));
        // 일어난 시각
        event.put("occurredAt", "2026-11-12T13:06:00+09:00");
        // 렌탈 ID
        event.put("rentalId", rental);
        // 시점 ID
        event.put("checkpointId", checkpointId);
        // 세트 ID
        event.put("captureSetId", setId);
        // 정합 모델 버전
        event.put("modelVersion", "contour-v1");
        // 면 맞춤 결과
        event.put("alignments", alignments);
        // worker가 찾은 흠집
        event.put("defects", defects);
        // 윗면 텍스처 파일 경로
        event.put("textureKeys", Map.of("TOP", "textures/" + setId + "/TOP.jpg"));
        // 실패한 사진과 이유("윤곽 못 찾음")
        event.put("failedPhotos", failed.stream().map(id -> Map.of("photoId", id, "reason", "윤곽 못 찾음")).toList());
        // 메시지 속성을 만들고
        MessageProperties props = new MessageProperties();
        // 내용이 JSON이라고 적는다
        props.setContentType("application/json");
        // trace.events로 "검사 완료" 메시지를 보낸다
        rabbit.send("trace.events", "trace.inspect.completed", new Message(json.writeValueAsBytes(event), props));
    }

    /** 비동기 처리를 기다리는 폴링 간격(100 ms). */
    // 테스트 도구: 비동기 처리를 기다리며 0.1초 쉰다.
    private static void pause() {
        // 0.1초 동안 멈춘다
        LockSupport.parkNanos(POLL_INTERVAL.toNanos());
    }

    // 테스트 도구: 그 시점에 등급이 날 때까지 최대 약 10초 기다려 검사 결과를 돌려준다.
    private JsonNode awaitGrade(String checkpointId) throws Exception {
        // 최대 100번(약 10초)
        for (int i = 0; i < 100; i++) {
            // 관리자로 검사 결과를 조회한다
            var res = mvc.perform(get("/api/v1/captures/checkpoints/" + checkpointId + "/inspection").with(admin()))
                    .andReturn().getResponse();
            // 200이면
            if (res.getStatus() == 200) {
                // 응답을 JSON으로 읽고
                JsonNode node = json.readTree(res.getContentAsString());
                // 등급이 있으면
                if (!node.get("grade").isNull()) {
                    // 그 결과를 돌려준다
                    return node;
                }
            }
            // 0.1초 쉬고 다시 본다
            pause();
        }
        // 끝까지 등급이 안 나면 테스트를 실패시킨다
        throw new AssertionError("등급이 나지 않았습니다: " + checkpointId);
    }

    // 테스트 도구: 세트 상태가 기대한 값이 될 때까지 최대 약 10초 기다린다.
    private void awaitStatus(String setId, String expected) throws Exception {
        // 최대 100번(약 10초)
        for (int i = 0; i < 100; i++) {
            // 관리자로 세트를 조회해 JSON으로 읽는다
            JsonNode set = json.readTree(mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                    .andReturn().getResponse().getContentAsString());
            // 상태가 기대한 값이면
            if (expected.equals(set.get("status").asText())) {
                // 기다리기를 끝낸다
                return;
            }
            // 0.1초 쉬고 다시 본다
            pause();
        }
        // 끝까지 안 바뀌면 테스트를 실패시킨다
        throw new AssertionError("세트 상태가 " + expected + "가 되지 않았습니다: " + setId);
    }

    // 테스트 도구: 큐에서 "시점 등급 완료" 이벤트 하나를 최대 10초 기다려 받는다.
    private JsonNode receive(String queue) throws Exception {
        // 큐에서 메시지를 최대 10초 기다려 꺼낸다
        Message m = rabbit.receive(queue, 10_000);
        // (도구) as(설명) = 실패할 때 보여 줄 이름
        // 확인: 메시지가 와야 한다
        assertThat(m).as("trace.checkpoint.graded").isNotNull();
        // 본문을 JSON으로 읽어 돌려준다
        return json.readTree(m.getBody());
    }

    // 테스트 도구: JSON 배열을 문자열 목록으로 바꾼다.
    private static List<String> texts(JsonNode array) {
        // 빈 목록을 만든다
        List<String> out = new ArrayList<>();
        // 배열 항목마다 글자로 바꿔 넣는다
        array.forEach(n -> out.add(n.asText()));
        // 목록을 돌려준다
        return out;
    }
}
