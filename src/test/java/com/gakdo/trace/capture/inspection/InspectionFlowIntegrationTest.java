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
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class InspectionFlowIntegrationTest {

    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    private static final String RENTAL = "Rnt0HLv6QRbDILPWOcz5I";
    private static final String OTHER_RENTAL = "Rnt1HLv6QRbDILPWOcz5I";
    private static final String CP_OUT = "CpOutQOlTkKj6mOecAQt1";
    private static final String CP_RCV = "CpRcvQOlTkKj6mOecAQt2";
    private static final String CP_RET = "CpRetQOlTkKj6mOecAQt3";
    private static final String SHA = "9f2c4e0b7a1d3c5e8f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e";
    private static final List<String> BOOTH_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "KB", "SCR0");
    private static final List<String> PHONE_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "SCR0");
    /** 사진 px → 표준 좌표 px: 절반으로 줄인다. */
    private static final List<List<Double>> HALF = List.of(List.of(0.5, 0.0, 0.0), List.of(0.0, 0.5, 0.0),
            List.of(0.0, 0.0, 1.0));

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    @TestConfiguration
    static class Storage {
        @Bean
        @Primary
        FakePhotoStorage fakePhotoStorage() {
            return new FakePhotoStorage();
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private FakePhotoStorage storage;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private CaptureSetRepository sets;

    @Autowired
    private PlatformTransactionManager txManager;

    @Test
    @DisplayName("출고는 기준선, 수령은 출고와·반납은 수령과 비교해 원래 흠집은 EXISTING·새 흠집은 NEW, 등급과 규칙 버전이 남고 이벤트가 나간다")
    void outReceiveReturn() throws Exception {
        // receive()가 소비자를 붙였다 떼므로 자동 삭제 큐를 쓰면 두 번째 receive에서 큐가 없다
        Queue graded = new Queue("test.checkpoint.graded", false, false, false);
        rabbitAdmin.declareQueue(graded);
        rabbitAdmin.purgeQueue(graded.getName(), false);
        rabbitAdmin.declareBinding(BindingBuilder.bind(graded).to(new TopicExchange("trace.events"))
                .with("trace.checkpoint.graded"));

        List<String> outTopIds = shipOut(graded.getName());
        receiveByPhone(graded.getName(), outTopIds);
        Returned returned = returnToBooth(graded.getName());
        checkAccessAndMedia(returned.setId(), returned.detections());
        purgeAll();
    }

    /** 시험 렌탈 지우기(trace.checkpoint.purged): 세 시점을 지우면 매칭·흠집까지 실제 DB에서 모두 사라지고 /inspection은 404. */
    private void purgeAll() throws Exception {
        List<String> checkpoints = List.of(CP_OUT, CP_RCV, CP_RET);
        for (String cp : checkpoints) {
            Map<String, Object> event = Map.of("schemaVersion", "1.0", "eventId", ("pg" + cp).substring(0, 21),
                    "occurredAt", "2026-11-12T13:06:00+09:00", "rentalId", RENTAL, "checkpointId", cp,
                    "checkpointType", "OUT", "deviceId", "Nq8sUe2ZpW4cYb6Td1MfA", "serial", "TEST-0002");
            MessageProperties props = new MessageProperties();
            props.setContentType("application/json");
            rabbit.send("trace.events", "trace.checkpoint.purged", new Message(json.writeValueAsBytes(event), props));
        }
        for (String cp : checkpoints) {
            for (int i = 0; i < 100 && mvc.perform(get("/api/v1/captures/checkpoints/" + cp + "/inspection")
                    .with(admin())).andReturn().getResponse().getStatus() != 404; i++) {
                pause();
            }
            mvc.perform(get("/api/v1/captures/checkpoints/" + cp + "/inspection").with(admin()))
                    .andExpect(status().isNotFound());
        }
        new TransactionTemplate(txManager).executeWithoutResult(t ->
                assertThat(sets.findAll()).noneMatch(set -> RENTAL.equals(set.getRentalId())));
    }

    /** 반납 세트 ID와 그 세트에 보낸 탐지 요청 본문. */
    private record Returned(String setId, String detections) {
    }

    /** 출고: 기준선 등급을 확인하고, 수령과 짝지을 수 있는 TOP 긁힘 ID 두 개를 돌려준다. */
    private List<String> shipOut(String gradedQueue) throws Exception {
        // ── 출고: 부스. 탐지 먼저(TOP 긁힘을 CAM1·CAM2가 같이 봄 + BOT 오염), 정합 뒤 등급
        JsonNode out = boothSet(RENTAL, CP_OUT);
        String outSet = out.get("captureSetId").asText();
        String outDetections = "{\"modelVersion\":\"yolo11s-seg-v001-trt-fp16\",\"detections\":["
                + detection("TOP", "CAM1", "scratch", 400, 400, 80) + ","
                + detection("TOP", "CAM2", "scratch", 402, 401, 80) + ","
                + detection("BOT", "CAM1", "stain", 600, 600, 60) + "],\"inferenceMs\":28400}";
        mvc.perform(post("/api/v1/captures/sets/" + outSet + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(outDetections))
                .andExpect(status().isCreated());
        // SF의 CAM2 사진은 정합 실패 → SF 판정 불가
        publishInspected(RENTAL, CP_OUT, outSet, photoIds(outSet, "SF-CAM2"), List.of());
        JsonNode outResult = awaitGrade(CP_OUT);
        assertThat(outResult.get("grade").asText()).isEqualTo("B");
        assertThat(outResult.get("ruleVersion").asText()).isEqualTo("grade-rules-v1");
        checkBenchmark(outSet);
        assertThat(texts(outResult.get("reasons"))).contains("출고 기준선(비교할 앞 시점 없음)", "오염 1개는 등급에서 뺌")
                .anyMatch(r -> r.startsWith("판정 불가 면 SF"));
        JsonNode outTop = outResult.get("defects").get(0);
        assertThat(outTop.get("polygon").get(0).get(0).asDouble()).isEqualTo(200.0);   // 400 px × 0.5
        assertThat(outTop.get("lengthMm").asDouble()).isEqualTo(10.05);
        assertThat(outTop.get("modelVersion").asText()).isEqualTo("yolo11s-seg-v001-trt-fp16");
        assertThat(outTop.get("match").isNull()).isTrue();   // 기준선은 짝을 만들지 않는다
        JsonNode outEvent = receive(gradedQueue);
        assertThat(outEvent.get("checkpointType").asText()).isEqualTo("OUT");
        assertThat(outEvent.get("newDefectIds")).isEmpty();
        assertThat(outEvent.get("compared").asBoolean()).isFalse(); // 출고는 비교 대상이 없다
        assertThat(texts(outEvent.get("unjudgeableFaces"))).containsExactly("SF");
        assertThat(outEvent.get("photos")).hasSize(BOOTH_FACES.size() * 2);
        // 같은 긁힘을 CAM1·CAM2가 같이 봤으므로 둘 중 어느 쪽과 짝지어도 맞다
        return List.of(outTop.get("defectId").asText(),
                outResult.get("defects").get(1).get("defectId").asText());
    }

    /** 성능 그래프: 부스가 보낸 탐지 시간과 완료→등급 시간이 세트 행 하나로 나온다(관리자만). */
    private void checkBenchmark(String setId) throws Exception {
        JsonNode items = json.readTree(mvc.perform(get("/api/v1/captures/benchmarks?source=BOOTH&limit=100")
                .with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("items");
        JsonNode row = null;
        for (JsonNode item : items) {
            assertThat(item.get("source").asText()).isEqualTo("BOOTH");
            if (setId.equals(item.get("captureSetId").asText())) {
                row = item;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("inferenceMs").asInt()).isEqualTo(28400);
        assertThat(row.get("modelVersion").asText()).isEqualTo("yolo11s-seg-v001-trt-fp16");
        assertThat(row.get("photoCount").asInt()).isEqualTo(BOOTH_FACES.size() * 2);
        assertThat(row.get("completedAt").isNull()).isFalse();
        assertThat(row.get("gradedAt").isNull()).isFalse();
        assertThat(row.get("completedToGradedMs").asLong()).isGreaterThanOrEqualTo(0);
        mvc.perform(get("/api/v1/captures/benchmarks").with(booth())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures/benchmarks?limit=101").with(admin())).andExpect(status().isBadRequest());
    }

    /** 수령: 출고와 같은 자리 긁힘은 EXISTING. */
    private void receiveByPhone(String gradedQueue, List<String> outTopIds) throws Exception {
        // ── 수령: 폰. worker가 표준 좌표로 같은 자리 긁힘을 탐지 → EXISTING
        String rcvSet = phoneSet(CP_RCV);
        String topPhoto = photoIds(rcvSet, "TOP-PHONE").get(0);
        publishInspected(RENTAL, CP_RCV, rcvSet, List.of(), List.of(Map.of("defectId", "dRcvTopScratch0000001",
                "photoId", topPhoto, "class", "scratch",
                "polygon", rect(201, 201, 40, 4), "confidence", 0.9, "lengthMm", 10.0)));
        JsonNode rcvResult = awaitGrade(CP_RCV);
        assertThat(rcvResult.get("grade").asText()).isEqualTo("B");
        JsonNode rcvTop = rcvResult.get("defects").get(0);
        assertThat(rcvTop.get("match").get("status").asText()).isEqualTo("EXISTING");
        assertThat(rcvTop.get("match").get("prevDefectId").asText()).isIn(outTopIds);
        JsonNode rcvEvent = receive(gradedQueue);
        assertThat(rcvEvent.get("checkpointType").asText()).isEqualTo("RCV");
        assertThat(rcvEvent.get("newDefectIds")).isEmpty();
    }

    /** 반납: 새 긴 긁힘으로 C. 세트 ID와 탐지 요청 본문을 돌려준다. */
    private Returned returnToBooth(String gradedQueue) throws Exception {
        // ── 반납: 부스. 정합이 먼저 오면 탐지를 기다리고, 탐지가 오면 바로 등급. 새 긴 긁힘(42.5 mm) → C
        JsonNode ret = boothSet(RENTAL, CP_RET);
        String retSet = ret.get("captureSetId").asText();
        publishInspected(RENTAL, CP_RET, retSet, List.of(), List.of());
        awaitStatus(retSet, "INSPECTED");
        assertThat(json.readTree(mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/inspection")
                .with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("grade").isNull()).isTrue();
        String retDetections = "{\"modelVersion\":\"yolo11s-seg-v001-trt-fp16\",\"detections\":["
                + detection("TOP", "CAM1", "scratch", 401, 400, 80) + ","
                + detection("TOP", "CAM1", "scratch", 1000, 1000, 340) + "]}";
        mvc.perform(post("/api/v1/captures/sets/" + retSet + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(retDetections))
                .andExpect(status().isCreated());
        JsonNode retResult = awaitGrade(CP_RET);
        assertThat(retResult.get("grade").asText()).isEqualTo("C");
        assertThat(texts(retResult.get("reasons"))).contains("신규 긁힘 1개(43mm)");
        List<String> statuses = new ArrayList<>();
        retResult.get("defects").forEach(d -> statuses.add(d.get("match").get("status").asText()));
        assertThat(statuses).containsExactly("EXISTING", "NEW");
        assertThat(retResult.get("defects").get(0).get("match").get("prevDefectId").asText())
                .isEqualTo("dRcvTopScratch0000001");
        JsonNode retEvent = receive(gradedQueue);
        assertThat(retEvent.get("checkpointType").asText()).isEqualTo("RET");
        assertThat(retEvent.get("grade").asText()).isEqualTo("C");
        assertThat(texts(retEvent.get("newDefectIds")))
                .containsExactly(retResult.get("defects").get(1).get("defectId").asText());
        // 1.1 핀: 묶음마다 하나, 신규 핀의 defectId는 newDefectIds와 같다
        assertThat(retEvent.get("schemaVersion").asText()).isEqualTo("1.2");
        assertThat(retEvent.get("compared").asBoolean()).isTrue();
        assertThat(retEvent.get("pins")).hasSize(2);
        List<String> newPins = new ArrayList<>();
        retEvent.get("pins").forEach(pin -> {
            assertThat(pin.get("face").asText()).isEqualTo("TOP");
            if (pin.get("isNew").asBoolean()) {
                newPins.add(pin.get("defectId").asText());
            }
        });
        assertThat(newPins).containsExactlyElementsOf(texts(retEvent.get("newDefectIds")));
        return new Returned(retSet, retDetections);
    }

    /** 등급 뒤 재전송·고객 범위·3D 트윈 텍스처. */
    private void checkAccessAndMedia(String retSet, String retDetections) throws Exception {
        // 등급이 매겨진 세트에 다시 보내면 409, 본인 렌탈이 아닌 고객은 403
        mvc.perform(post("/api/v1/captures/sets/" + retSet + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(retDetections))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/inspection").with(customer(RENTAL)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/inspection").with(customer("Other0HLv6QRbDILPWOcz")))
                .andExpect(status().isForbidden());

        // 3D 트윈 텍스처: 정합 이벤트의 textureKeys가 trace-derived 사전 서명 GET URL로 나간다
        JsonNode media = json.readTree(mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/media")
                .with(customer(RENTAL))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(media.get("checkpointId").asText()).isEqualTo(CP_RET);
        assertThat(media.get("expiresAt").isNull()).isFalse();
        assertThat(media.get("textures")).hasSize(1);
        assertThat(media.get("textures").get(0).get("face").asText()).isEqualTo("TOP");
        assertThat(media.get("textures").get(0).get("url").asText())
                .startsWith("https://minio.test/trace-derived/textures/" + retSet + "/TOP.jpg?");
        assertThat(media.get("crops")).isEmpty();
        mvc.perform(get("/api/v1/captures/checkpoints/" + CP_RET + "/media").with(customer("Other0HLv6QRbDILPWOcz")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures/checkpoints/CpNoneQOlTkKj6mOecAQt/media").with(admin()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("부스 탐지는 같은 세트에 다시 오면 통째로 바뀌고, 세트에 없는 사진을 가리키면 400, 다른 부스는 403이다")
    void detectionsReplaceAndValidate() throws Exception {
        String setId = boothSet(OTHER_RENTAL, "CpDetQOlTkKj6mOecAQt4").get("captureSetId").asText();
        String two = "{\"modelVersion\":\"m1\",\"detections\":[" + detection("TOP", "CAM1", "scratch", 10, 10, 40)
                + "," + detection("BOT", "CAM2", "dent", 10, 10, 40) + "]}";
        String one = "{\"modelVersion\":\"m2\",\"detections\":[" + detection("KB", "CAM2", "crack", 10, 10, 40) + "]}";
        assertThat(accepted(setId, two)).isEqualTo(2);
        assertThat(accepted(setId, one)).isEqualTo(1);

        String missing = "{\"modelVersion\":\"m3\",\"detections\":["
                + detection("SCR1", "CAM1", "scratch", 10, 10, 40) + "]}";
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(missing))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth("booth-99"))
                .contentType(MediaType.APPLICATION_JSON).content(one))
                .andExpect(status().isForbidden());

        // 정합 뒤 등급이 나면 마지막 요청(균열 1개)만 남아 D
        publishInspected(OTHER_RENTAL, "CpDetQOlTkKj6mOecAQt4", setId, List.of(), List.of());
        JsonNode result = awaitGrade("CpDetQOlTkKj6mOecAQt4");
        assertThat(result.get("defects")).hasSize(1);
        assertThat(result.get("defects").get(0).get("class").asText()).isEqualTo("crack");
        assertThat(result.get("grade").asText()).isEqualTo("D");
    }

    @Test
    @DisplayName("없는 세트·3×3이 아닌 행렬의 검사 완료 이벤트는 재시도하지 않고 DLX로 보내고, 이미 처리한 세트에 다시 오면 무시한다")
    void invalidAndDuplicateEvents() throws Exception {
        String missing = "{\"schemaVersion\":\"1.0\",\"eventId\":\"evMissing000000000001\",\"rentalId\":\""
                + OTHER_RENTAL + "\",\"checkpointId\":\"CpMisQOlTkKj6mOecAQt5\",\"captureSetId\":\"NoSuchSet000000000001\","
                + "\"modelVersion\":\"m\",\"alignments\":[],\"defects\":[],\"textureKeys\":{},\"extraField\":1}";
        byte[] missingBody = missing.getBytes();
        byte[] notJson = "not json".getBytes();
        assertThatThrownBy(() -> listener.handle(missingBody))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);
        assertThatThrownBy(() -> listener.handle(notJson))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        String checkpoint = "CpDupQOlTkKj6mOecAQt6";
        String setId = phoneSetIn(OTHER_RENTAL, checkpoint);
        String photo = allPhotoIds(setId).get(0);
        String badMatrix = "{\"schemaVersion\":\"1.0\",\"eventId\":\"evBadMatrix0000000001\",\"rentalId\":\""
                + OTHER_RENTAL + "\",\"checkpointId\":\"" + checkpoint + "\",\"captureSetId\":\"" + setId + "\","
                + "\"modelVersion\":\"m\",\"alignments\":[{\"photoId\":\"" + photo + "\",\"method\":\"CONTOUR\","
                + "\"homography\":[[1,0],[0,1]],\"errorPx\":null,\"rectifiedKey\":\"r.jpg\"}],\"defects\":[],"
                + "\"textureKeys\":{}}";
        byte[] badMatrixBody = badMatrix.getBytes();
        assertThatThrownBy(() -> listener.handle(badMatrixBody))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class);

        publishInspected(OTHER_RENTAL, checkpoint, setId, List.of(), List.of());
        JsonNode first = awaitGrade(checkpoint);
        assertThat(first.get("grade").asText()).isEqualTo("A");
        String again = "{\"schemaVersion\":\"1.0\",\"eventId\":\"evAgain00000000000001\",\"rentalId\":\""
                + OTHER_RENTAL + "\",\"checkpointId\":\"" + checkpoint + "\",\"captureSetId\":\"" + setId + "\","
                + "\"modelVersion\":\"m\",\"alignments\":[],\"defects\":[],\"textureKeys\":{}}";
        assertThat(listener.handle(again.getBytes())).isFalse();
    }

    // ── 도우미

    @Autowired
    private InspectEventListener listener;

    private String phoneSetIn(String rental, String checkpointId) throws Exception {
        List<String> photos = PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList();
        return createAndComplete(rental, checkpointId, "PHONE", photos, capture(checkpointId, rental))
                .get("captureSetId").asText();
    }

    private int accepted(String setId, String body) throws Exception {
        return json.readTree(mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("accepted").asInt();
    }

    private static RequestPostProcessor customer(String rental) {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(rental)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** 부스 세트(면 8개 × CAM1·CAM2, 조명 L1)를 만들고 올린 뒤 완료한다. */
    private JsonNode boothSet(String rental, String checkpointId) throws Exception {
        List<String> photos = new ArrayList<>();
        for (String face : BOOTH_FACES) {
            for (String cam : List.of("CAM1", "CAM2")) {
                photos.add(photo(face, "L1", cam));
            }
        }
        return createAndComplete(rental, checkpointId, "BOOTH", photos, booth());
    }

    private String phoneSet(String checkpointId) throws Exception {
        List<String> photos = PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList();
        return createAndComplete(RENTAL, checkpointId, "PHONE", photos, capture(checkpointId, RENTAL))
                .get("captureSetId").asText();
    }

    private JsonNode createAndComplete(String rental, String checkpointId, String source, List<String> photos,
            RequestPostProcessor token) throws Exception {
        String body = "{\"rentalId\":\"" + rental + "\",\"checkpointId\":\"" + checkpointId + "\",\"source\":\""
                + source + "\",\"photos\":[" + String.join(",", photos) + "]}";
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/captures/sets").with(token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String setId = created.get("captureSetId").asText();
        for (JsonNode u : created.get("uploads")) {
            storage.put("captures/" + setId + "/" + u.get("face").asText() + "-" + u.get("light").asText() + "-"
                    + u.get("camera").asText() + ".jpg", SHA);
        }
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/complete").with(token)
                .header("Idempotency-Key", "key-" + setId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        return created;
    }

    private static String photo(String face, String light, String camera) {
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\",\"sha256\":\""
                + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}";
    }

    private static String detection(String face, String camera, String cls, double x, double y, double w) {
        return "{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"" + camera + "\",\"class\":\"" + cls
                + "\",\"polygon\":[[" + x + "," + y + "],[" + (x + w) + "," + y + "],[" + (x + w) + "," + (y + 8)
                + "],[" + x + "," + (y + 8) + "]],\"confidence\":0.88}";
    }

    private static List<List<Double>> rect(double x, double y, double w, double h) {
        return List.of(List.of(x, y), List.of(x + w, y), List.of(x + w, y + h), List.of(x, y + h));
    }

    /** "SF-CAM2"처럼 면-카메라로 고른 사진 ID. */
    private List<String> photoIds(String setId, String... faceCamera) throws Exception {
        JsonNode set = json.readTree(mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                .andReturn().getResponse().getContentAsString());
        List<String> wanted = List.of(faceCamera);
        List<String> ids = new ArrayList<>();
        for (JsonNode p : set.get("photos")) {
            if (wanted.contains(p.get("face").asText() + "-" + p.get("camera").asText())) {
                ids.add(photoId(setId, p.get("face").asText(), p.get("light").asText(), p.get("camera").asText()));
            }
        }
        return ids;
    }

    private String photoId(String setId, String face, String light, String camera) {
        return new TransactionTemplate(txManager).execute(s ->
                sets.findByPublicId(setId).orElseThrow().getPhotos().stream()
                        .filter(p -> p.getFace().name().equals(face) && p.getLight().name().equals(light)
                                && p.getCamera().name().equals(camera))
                        .findFirst().orElseThrow().getPublicId());
    }

    private List<String> allPhotoIds(String setId) {
        return new TransactionTemplate(txManager).execute(s ->
                sets.findByPublicId(setId).orElseThrow().getPhotos().stream().map(Photo::getPublicId).toList());
    }

    /** inspect-worker 흉내: failed를 뺀 모든 사진을 HALF로 정합하고 worker 탐지(defects)를 싣는다. */
    private void publishInspected(String rental, String checkpointId, String setId, List<String> failed,
            List<Map<String, Object>> defects) throws Exception {
        List<Map<String, Object>> alignments = new ArrayList<>();
        for (String id : allPhotoIds(setId)) {
            if (!failed.contains(id)) {
                alignments.add(Map.of("photoId", id, "method", "CONTOUR", "homography", HALF, "errorPx", 1.5,
                        "rectifiedKey", "rectified/" + setId + "/" + id + ".jpg"));
            }
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schemaVersion", "1.0");
        event.put("eventId", ("ev" + setId).substring(0, 21));
        event.put("occurredAt", "2026-11-12T13:06:00+09:00");
        event.put("rentalId", rental);
        event.put("checkpointId", checkpointId);
        event.put("captureSetId", setId);
        event.put("modelVersion", "contour-v1");
        event.put("alignments", alignments);
        event.put("defects", defects);
        event.put("textureKeys", Map.of("TOP", "textures/" + setId + "/TOP.jpg"));
        event.put("failedPhotos", failed.stream().map(id -> Map.of("photoId", id, "reason", "윤곽 못 찾음")).toList());
        MessageProperties props = new MessageProperties();
        props.setContentType("application/json");
        rabbit.send("trace.events", "trace.inspect.completed", new Message(json.writeValueAsBytes(event), props));
    }

    /** 비동기 처리를 기다리는 폴링 간격(100 ms). */
    private static void pause() {
        LockSupport.parkNanos(POLL_INTERVAL.toNanos());
    }

    private JsonNode awaitGrade(String checkpointId) throws Exception {
        for (int i = 0; i < 100; i++) {
            var res = mvc.perform(get("/api/v1/captures/checkpoints/" + checkpointId + "/inspection").with(admin()))
                    .andReturn().getResponse();
            if (res.getStatus() == 200) {
                JsonNode node = json.readTree(res.getContentAsString());
                if (!node.get("grade").isNull()) {
                    return node;
                }
            }
            pause();
        }
        throw new AssertionError("등급이 나지 않았습니다: " + checkpointId);
    }

    private void awaitStatus(String setId, String expected) throws Exception {
        for (int i = 0; i < 100; i++) {
            JsonNode set = json.readTree(mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                    .andReturn().getResponse().getContentAsString());
            if (expected.equals(set.get("status").asText())) {
                return;
            }
            pause();
        }
        throw new AssertionError("세트 상태가 " + expected + "가 되지 않았습니다: " + setId);
    }

    private JsonNode receive(String queue) throws Exception {
        Message m = rabbit.receive(queue, 10_000);
        assertThat(m).as("trace.checkpoint.graded").isNotNull();
        return json.readTree(m.getBody());
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
