package com.gakdo.trace.capture.captureset;

import static com.gakdo.trace.capture.support.TestJwts.BOOTH;
import static com.gakdo.trace.capture.support.TestJwts.CHECKPOINT;
import static com.gakdo.trace.capture.support.TestJwts.OTHER_CHECKPOINT;
import static com.gakdo.trace.capture.support.TestJwts.OTHER_RENTAL;
import static com.gakdo.trace.capture.support.TestJwts.RENTAL;
import static com.gakdo.trace.capture.support.TestJwts.admin;
import static com.gakdo.trace.capture.support.TestJwts.booth;
import static com.gakdo.trace.capture.support.TestJwts.capture;
import static com.gakdo.trace.capture.support.TestJwts.customer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.capture.captureset.api.CaptureSetController;
import com.gakdo.trace.capture.captureset.application.CaptureEventPublisher;
import com.gakdo.trace.capture.captureset.application.CaptureSetService;
import com.gakdo.trace.capture.captureset.application.CaptureUploadedEvent;
import com.gakdo.trace.capture.captureset.domain.CaptureSet;
import com.gakdo.trace.capture.captureset.domain.Face;
import com.gakdo.trace.capture.captureset.infra.CaptureSetRepository;
import com.gakdo.trace.capture.support.FakePhotoStorage;
import com.gakdo.trace.capture.support.WebTestBase;

/**
 * 컨트롤러 + 실제 서비스 + 메모리 저장소. DB·MinIO·RabbitMQ 없이 수용 시나리오를 확인한다(실제 연결은 CaptureIntegrationTest).
 */
@WebMvcTest(controllers = CaptureSetController.class)
@Import({CaptureSetService.class, FakePhotoStorage.class})
class CaptureSetApiTest extends WebTestBase {

    private static final String SHA = "9f2c4e0b7a1d3c5e8f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e";
    private static final String OTHER_SHA = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";
    private static final List<String> BOOTH_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "KB", "SCR0");
    private static final List<String> PHONE_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "SCR0");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private FakePhotoStorage storage;

    @MockitoBean
    private CaptureSetRepository repository;

    @MockitoBean
    private CaptureEventPublisher events;

    private final Map<String, CaptureSet> saved = new ConcurrentHashMap<>();

    @BeforeEach
    void memoryRepository() {
        storage.clear();
        saved.clear();
        when(repository.save(any(CaptureSet.class))).thenAnswer(inv -> {
            CaptureSet set = inv.getArgument(0);
            saved.put(set.getPublicId(), set);
            return set;
        });
        when(repository.findForUpdate(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));
        when(repository.findByPublicId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));
        when(repository.existsByCheckpointIdAndRentalIdNot(anyString(), anyString())).thenAnswer(inv -> saved.values()
                .stream().anyMatch(s -> s.getCheckpointId().equals(inv.getArgument(0))
                        && !s.getRentalId().equals(inv.getArgument(1))));
    }

    // ── 이야기 1: 세트 생성 ──

    @Test
    @DisplayName("업로드 URL 응답은 사진마다 face·light·camera를 돌려준다(요청과 같은 순서·개수, PUT, 체크섬 헤더, 10분)")
    void createBooth() throws Exception {
        JsonNode body = created(booth(), boothBody(List.of("SCR1")));
        assertThat(body.get("captureSetId").asText()).matches("^[A-Za-z0-9_-]{21}$");
        JsonNode uploads = body.get("uploads");
        assertThat(uploads).hasSize(BOOTH_FACES.size() * 2 + 1);
        assertThat(uploads.get(0).get("face").asText()).isEqualTo("TOP");
        assertThat(uploads.get(0).get("camera").asText()).isEqualTo("CAM1");
        assertThat(uploads.get(1).get("camera").asText()).isEqualTo("CAM2");
        assertThat(uploads.get(uploads.size() - 1).get("face").asText()).isEqualTo("SCR1");
        assertThat(uploads.get(0).get("method").asText()).isEqualTo("PUT");
        assertThat(uploads.get(0).get("headers").get("Content-Type").asText()).isEqualTo("image/jpeg");
        assertThat(uploads.get(0).get("headers").get("x-amz-checksum-sha256").asText())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(SHA)));
        assertThat(uploads.get(0).get("expiresAt").asText()).endsWith("Z");
        assertThat(uploads.get(0).get("url").asText()).isNotEqualTo(uploads.get(1).get("url").asText());
    }

    @Test
    @DisplayName("만든 세트의 boothId는 부스 토큰의 scope이고, 관리자는 어느 세트든 조회한다")
    void boothIdFromToken() throws Exception {
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.boothId").value(BOOTH))
                .andExpect(jsonPath("$.source").value("BOOTH"))
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                .andExpect(jsonPath("$.quality").value("OK"))
                .andExpect(jsonPath("$.photos[0].uploaded").value(false));
    }

    @Test
    @DisplayName("같은 세트 안에 (face, light, camera)가 겹치면 400 VALIDATION_FAILED(photos[i])")
    void duplicatePhoto() throws Exception {
        String body = setBody(RENTAL, CHECKPOINT, "BOOTH",
                List.of(photo("TOP", "L1", "CAM1"), photo("TOP", "L1", "CAM1")));
        perform(post("/api/v1/captures/sets").with(booth()), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("photos[1]"));
    }

    @Test
    @DisplayName("source와 맞지 않는 카메라(PHONE인데 CAM1)는 400")
    void wrongCamera() throws Exception {
        String body = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "CAM1")));
        perform(post("/api/v1/captures/sets").with(capture()), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("photos[0].camera"));
    }

    @Test
    @DisplayName("형식 오류(오프셋 없는 takenAt, 모르는 필드, 대문자 해시, 빈 사진 목록)는 400")
    void malformed() throws Exception {
        String noOffset = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")))
                .replace("+09:00", "");
        perform(post("/api/v1/captures/sets").with(capture()), noOffset).andExpect(status().isBadRequest());
        String unknown = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")))
                .replace("{\"rentalId\"", "{\"boothId\":\"x\",\"rentalId\"");
        perform(post("/api/v1/captures/sets").with(capture()), unknown).andExpect(status().isBadRequest());
        String upper = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")))
                .replace(SHA, SHA.toUpperCase());
        perform(post("/api/v1/captures/sets").with(capture()), upper)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("photos[0].sha256"));
        perform(post("/api/v1/captures/sets").with(capture()), setBody(RENTAL, CHECKPOINT, "PHONE", List.of()))
                .andExpect(status().isBadRequest());
    }

    // ── 이야기 3: 범위 ──

    @Test
    @DisplayName("촬영 JWT로 다른 checkpointId·rentalId나 source=BOOTH로 세트를 만들면 403 OUT_OF_SCOPE")
    void captureTokenScope() throws Exception {
        List<String> photos = List.of(photo("TOP", "ROOM", "PHONE"));
        perform(post("/api/v1/captures/sets").with(capture()), setBody(RENTAL, OTHER_CHECKPOINT, "PHONE", photos))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        perform(post("/api/v1/captures/sets").with(capture()), setBody(OTHER_RENTAL, CHECKPOINT, "PHONE", photos))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        perform(post("/api/v1/captures/sets").with(capture()),
                setBody(RENTAL, CHECKPOINT, "BOOTH", List.of(photo("TOP", "L1", "CAM1"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    @Test
    @DisplayName("이미 다른 렌탈로 세트가 있는 시점에 부스가 다른 렌탈로 세트를 만들면 403 OUT_OF_SCOPE, 같은 렌탈 재촬영은 된다")
    void boothCheckpointRentalPair() throws Exception {
        created(booth(), boothBody(List.of()));
        String mismatched = boothBody(List.of()).replace("\"rentalId\":\"" + RENTAL + "\"",
                "\"rentalId\":\"" + OTHER_RENTAL + "\"");
        assertThat(mismatched).contains(OTHER_RENTAL);
        perform(post("/api/v1/captures/sets").with(booth()), mismatched)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        created(booth(), boothBody(List.of()));
    }

    @Test
    @DisplayName("부스 토큰으로 source=PHONE 세트를 만들면 403 OUT_OF_SCOPE")
    void boothScope() throws Exception {
        perform(post("/api/v1/captures/sets").with(booth()),
                setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    @Test
    @DisplayName("scope 없는 일반 CUSTOMER 토큰은 세트 생성·완료·조회 모두 403 FORBIDDEN, ADMIN은 생성 403")
    void plainCustomer() throws Exception {
        String body = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")));
        perform(post("/api/v1/captures/sets").with(customer()), body)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        String setId = created(capture(), body).get("captureSetId").asText();
        perform(post("/api/v1/captures/sets/" + setId + "/complete").with(customer())
                .header("Idempotency-Key", "key-00000001"), "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(customer()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        perform(post("/api/v1/captures/sets").with(admin()), body)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHENTICATED Problem Details")
    void unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/captures/sets/" + CHECKPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("다른 부스·다른 시점의 세트를 완료하면 403 OUT_OF_SCOPE, 촬영 JWT는 자기 시점 세트만 조회")
    void completeAndGetScope() throws Exception {
        String boothSet = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        complete(boothSet, booth("booth-02"), "key-00000001", "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        complete(boothSet, capture(), "key-00000001", "{}")
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));

        String phoneSet = created(capture(), phoneBody()).get("captureSetId").asText();
        mvc.perform(get("/api/v1/captures/sets/" + phoneSet).with(capture())).andExpect(status().isOk());
        mvc.perform(get("/api/v1/captures/sets/" + phoneSet).with(capture(OTHER_CHECKPOINT, RENTAL)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        mvc.perform(get("/api/v1/captures/sets/" + phoneSet).with(booth()))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mvc.perform(get("/api/v1/captures/sets/AAAAAAAAAAAAAAAAAAAAA").with(admin()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── 이야기 2: 완료 ──

    @Test
    @DisplayName("필수 면이 모두 올라간 부스 세트를 완료하면 202 UPLOADED, trace.capture.uploaded 하나(needDetection=false)")
    void completeBooth() throws Exception {
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, booth(), "key-00000001", null)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                .andExpect(jsonPath("$.boothId").value(BOOTH))
                .andExpect(jsonPath("$.photos[0].uploaded").value(true));

        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        verify(events, times(1)).publishUploaded(event.capture());
        CaptureUploadedEvent e = event.getValue();
        assertThat(e.schemaVersion()).isEqualTo("1.0");
        assertThat(e.rentalId()).isEqualTo(RENTAL);
        assertThat(e.checkpointId()).isEqualTo(CHECKPOINT);
        assertThat(e.captureSetId()).isEqualTo(setId);
        assertThat(e.needDetection()).isFalse();
        assertThat(e.photos()).hasSize(BOOTH_FACES.size() * 2);
        assertThat(e.photos().get(0).objectKey()).isEqualTo("captures/" + setId + "/TOP-L1-CAM1.jpg");
        assertThat(e.skippedFaces()).isEmpty();
        JsonNode serialized = json.valueToTree(e);
        assertThat(serialized.has("skippedFaces")).isFalse();
        assertThat(serialized.has("device")).isFalse(); // 기종을 안 보내면 이벤트에서도 뺀다
        assertThat(serialized.get("eventId").asText()).matches("^[A-Za-z0-9_-]{21}$");
    }

    @Test
    @DisplayName("세트 출처의 필수 면이 빠지면 세트가 완료되지 않는다(409 CAPTURE_FACES_MISSING, missingFaces)")
    void facesMissing() throws Exception {
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        uploadAll(setId);
        storage.clear();
        for (String face : BOOTH_FACES) {
            if (!face.equals("KB")) {
                storage.put("captures/" + setId + "/" + face + "-L1-CAM1.jpg", SHA);
                storage.put("captures/" + setId + "/" + face + "-L1-CAM2.jpg", SHA);
            }
        }
        complete(setId, booth(), "key-00000001", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPTURE_FACES_MISSING"))
                .andExpect(jsonPath("$.missingFaces[0]").value("KB"));
        verify(events, never()).publishUploaded(any());
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                .andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    @Test
    @DisplayName("세트 생성에 기종·치수(device)를 보내면 완료 이벤트 trace.capture.uploaded에 그대로 실린다")
    void deviceForwarded() throws Exception {
        String body = phoneBody().replaceFirst("\\}$", "," + DEVICE + "}");
        String setId = created(capture(), body).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, capture(), "key-00000001", "{}").andExpect(status().isAccepted());
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        verify(events).publishUploaded(event.capture());
        JsonNode device = json.valueToTree(event.getValue()).get("device");
        assertThat(device.get("modelCode").asText()).isEqualTo("GRAM16-2024");
        assertThat(device.get("dimensions").get("widthMm").asDouble()).isEqualTo(357.7);
        assertThat(device.get("dimensions").get("lidHMm").asDouble()).isEqualTo(5.0);
    }

    @Test
    @DisplayName("기종 치수가 0 이하이거나 기종 코드 형식이 틀리면 400 VALIDATION_FAILED")
    void deviceInvalid() throws Exception {
        String zero = phoneBody().replaceFirst("\\}$", "," + DEVICE.replace("357.7", "0") + "}");
        perform(post("/api/v1/captures/sets").with(capture()), zero)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        String lower = phoneBody().replaceFirst("\\}$", "," + DEVICE.replace("GRAM16-2024", "gram16") + "}");
        perform(post("/api/v1/captures/sets").with(capture()), lower)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("폰의 필수 면은 TOP·BOT·SF·SB·SL·SR·SCR0 7면이다: KB 없이 202, needDetection=true")
    void completePhone() throws Exception {
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, capture(), "key-00000001", "{}").andExpect(status().isAccepted());
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        verify(events).publishUploaded(event.capture());
        assertThat(event.getValue().needDetection()).isTrue();
        assertThat(event.getValue().photos()).hasSize(PHONE_FACES.size() + 1); // 올린 SCR1도 실린다
    }

    @Test
    @DisplayName("SCR1을 올리지 못하고 skippedFaces에 사유를 남기면 202, 이벤트에 SCR1 사진 없이 skippedFaces가 실린다")
    void skipScr1() throws Exception {
        String setId = created(booth(), boothBody(List.of("SCR1"))).get("captureSetId").asText();
        uploadAll(setId);
        storage.clear();
        for (String face : BOOTH_FACES) {
            storage.put("captures/" + setId + "/" + face + "-L1-CAM1.jpg", SHA);
            storage.put("captures/" + setId + "/" + face + "-L1-CAM2.jpg", SHA);
        }
        complete(setId, booth(), "key-00000001", "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("skippedFaces.SCR1"));
        complete(setId, booth(), "key-00000002", "{\"skippedFaces\":{\"SCR1\":\"화면이 켜지지 않음\"}}")
                .andExpect(status().isAccepted());
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        verify(events).publishUploaded(event.capture());
        assertThat(event.getValue().photos()).noneMatch(p -> p.face() == Face.SCR1);
        assertThat(event.getValue().skippedFaces()).containsEntry(Face.SCR1, "화면이 켜지지 않음");
    }

    @Test
    @DisplayName("skippedFaces에 필수 면(부스 KB)을 넣으면 400 VALIDATION_FAILED, field skippedFaces.KB")
    void skipRequired() throws Exception {
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, booth(), "key-00000001", "{\"skippedFaces\":{\"KB\":\"못 찍음\"}}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("skippedFaces.KB"));
    }

    @Test
    @DisplayName("올라간 객체의 SHA-256이 알린 값과 다르면 409 CAPTURE_HASH_MISMATCH")
    void hashMismatch() throws Exception {
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        uploadAll(setId);
        storage.put("captures/" + setId + "/TOP-ROOM-PHONE.jpg", OTHER_SHA);
        complete(setId, capture(), "key-00000001", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPTURE_HASH_MISMATCH"));
    }

    @Test
    @DisplayName("같은 Idempotency-Key·같은 본문은 처음 응답을 그대로, 다른 본문은 422, 다른 키는 409 CAPTURE_SET_CLOSED, 키가 없으면 400")
    void idempotency() throws Exception {
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, capture(), "short", "{}").andExpect(status().isBadRequest());
        perform(post("/api/v1/captures/sets/" + setId + "/complete").with(capture()), "{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        String first = complete(setId, capture(), "key-00000001", "{\"skippedFaces\":{\"SCR1\":\"없음\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        clearInvocations(events);
        String again = complete(setId, capture(), "key-00000001", "{\"skippedFaces\":{\"SCR1\":\"없음\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        verify(events, never()).publishUploaded(any());

        complete(setId, capture(), "key-00000001", "{}")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        complete(setId, capture(), "key-00000002", "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPTURE_SET_CLOSED"));
    }

    @Test
    @DisplayName("UPLOADING 세트 조회는 저장소에 올라간 사진을 uploaded=true로 보여 준다")
    void getShowsUploads() throws Exception {
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        storage.put("captures/" + setId + "/TOP-ROOM-PHONE.jpg", SHA);
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(capture()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkpointId").value(CHECKPOINT))
                .andExpect(jsonPath("$.boothId").isEmpty())
                .andExpect(jsonPath("$.photos[0].face").value("TOP"))
                .andExpect(jsonPath("$.photos[0].uploaded").value(true))
                .andExpect(jsonPath("$.photos[1].uploaded").value(false));
    }

    // ── 업로드 URL 다시 받기 (contracts reissueUploadUrls, 11/2 결정) ──

    @Test
    @DisplayName("URL이 만료되면 세트를 새로 만들지 않고 남은 사진 전부의 새 10분 PUT URL을 세트 생성과 같은 형식으로 받는다")
    void reissueAfterExpiry() throws Exception {
        JsonNode first = created(booth(), boothBody(List.of("SCR1")));
        String setId = first.get("captureSetId").asText();
        JsonNode original = first.get("uploads");

        JsonNode uploads = reissue(setId, booth(), null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(this::tree).get("uploads");
        assertThat(uploads).hasSize(original.size());
        for (int i = 0; i < uploads.size(); i++) {
            JsonNode u = uploads.get(i);
            JsonNode o = original.get(i);
            assertThat(u.get("face")).isEqualTo(o.get("face"));
            assertThat(u.get("light")).isEqualTo(o.get("light"));
            assertThat(u.get("camera")).isEqualTo(o.get("camera"));
            assertThat(u.get("method").asText()).isEqualTo("PUT");
            assertThat(u.get("headers")).isEqualTo(o.get("headers"));
            assertThat(u.get("url").asText()).isNotEqualTo(o.get("url").asText());
            assertThat(java.time.OffsetDateTime.parse(u.get("expiresAt").asText()))
                    .isAfterOrEqualTo(java.time.OffsetDateTime.parse(o.get("expiresAt").asText()))
                    .isBefore(java.time.OffsetDateTime.now().plusMinutes(11));
        }
        // 빈 본문 {}도 남은 사진 전부
        reissue(setId, booth(), "{}").andExpect(status().isOk())
                .andExpect(jsonPath("$.uploads.length()").value(original.size()));
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                .andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    @Test
    @DisplayName("이미 올라간 사진은 다시 주지 않고, photos를 주면 그 사진만(세트 순서), 모두 올라갔으면 빈 배열")
    void reissueSkipsUploaded() throws Exception {
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        storage.put("captures/" + setId + "/TOP-ROOM-PHONE.jpg", SHA);

        JsonNode all = tree(reissue(setId, capture(), null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("uploads");
        assertThat(all).hasSize(PHONE_FACES.size()) // 8장 중 TOP 제외
                .noneMatch(u -> u.get("face").asText().equals("TOP"));

        String picked = "{\"photos\":[" + ref("SCR1", "OFF", "PHONE") + "," + ref("TOP", "ROOM", "PHONE") + ","
                + ref("BOT", "ROOM", "PHONE") + "]}";
        reissue(setId, capture(), picked).andExpect(status().isOk())
                .andExpect(jsonPath("$.uploads.length()").value(2))
                .andExpect(jsonPath("$.uploads[0].face").value("BOT"))
                .andExpect(jsonPath("$.uploads[1].face").value("SCR1"))
                .andExpect(jsonPath("$.uploads[1].light").value("OFF"));

        uploadAll(setId);
        reissue(setId, capture(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.uploads").isArray())
                .andExpect(jsonPath("$.uploads.length()").value(0));
    }

    @Test
    @DisplayName("세트에 없는 (face, light, camera)나 형식 오류는 400 VALIDATION_FAILED(photos[i])")
    void reissueUnknownPhoto() throws Exception {
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        reissue(setId, booth(), "{\"photos\":[" + ref("TOP", "L1", "CAM1") + "," + ref("TOP", "L2", "CAM1") + "]}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("photos[1]"));
        reissue(setId, booth(), "{\"photos\":[]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        reissue(setId, booth(), "{\"photos\":[{\"face\":\"TOP\",\"light\":\"L1\"}]}")
                .andExpect(status().isBadRequest());
        reissue(setId, booth(), "{\"photos\":[" + ref("TOP", "L1", "CAM1").replace("}", ",\"sha256\":\"x\"}")
                + "]}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("완료된 세트의 URL을 다시 받으면 409 CAPTURE_SET_CLOSED")
    void reissueClosed() throws Exception {
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, booth(), "key-00000001", "{}").andExpect(status().isAccepted());
        reissue(setId, booth(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CAPTURE_SET_CLOSED"));
    }

    @Test
    @DisplayName("다른 부스·다른 시점은 403 OUT_OF_SCOPE, 일반 고객·관리자는 403 FORBIDDEN, 없는 세트는 404")
    void reissueScope() throws Exception {
        String boothSet = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        reissue(boothSet, booth("booth-02"), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        reissue(boothSet, capture(), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));

        String phoneSet = created(capture(), phoneBody()).get("captureSetId").asText();
        reissue(phoneSet, capture(OTHER_CHECKPOINT, RENTAL), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        reissue(phoneSet, booth(), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        reissue(phoneSet, customer(), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        reissue(phoneSet, admin(), null)
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        reissue("AAAAAAAAAAAAAAAAAAAAA", booth(), null)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── 도우미 ──

    private ResultActions reissue(String setId, RequestPostProcessor token, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/captures/sets/" + setId + "/uploads").with(token);
        return body == null ? mvc.perform(request) : perform(request, body);
    }

    private JsonNode tree(String body) {
        try {
            return json.readTree(body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String ref(String face, String light, String camera) {
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\"}";
    }

    private JsonNode created(RequestPostProcessor token, String body)
            throws Exception {
        String response = perform(post("/api/v1/captures/sets").with(token), body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private ResultActions complete(String setId,
            RequestPostProcessor token, String key, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/captures/sets/" + setId + "/complete").with(token)
                .header("Idempotency-Key", key);
        return body == null ? mvc.perform(request) : perform(request, body);
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 세트의 모든 사진을 알린 해시로 "올린다". */
    private void uploadAll(String setId) {
        saved.get(setId).getPhotos().forEach(p -> storage.put(p.getObjectKey(), p.getSha256()));
    }

    private static String boothBody(List<String> optional) {
        List<String> photos = new ArrayList<>();
        for (String face : BOOTH_FACES) {
            photos.add(photo(face, "L1", "CAM1"));
            photos.add(photo(face, "L1", "CAM2"));
        }
        for (String face : optional) {
            photos.add(photo(face, "OFF", "CAM1"));
        }
        return setBody(RENTAL, CHECKPOINT, "BOOTH", photos);
    }

    private static final String DEVICE = "\"device\":{\"modelCode\":\"GRAM16-2024\",\"dimensions\":"
            + "{\"widthMm\":357.7,\"depthMm\":250.0,\"baseHMm\":11.0,\"lidHMm\":5.0}}";

    private static String phoneBody() {
        List<String> photos = new ArrayList<>(PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList());
        photos.add(photo("SCR1", "OFF", "PHONE"));
        return setBody(RENTAL, CHECKPOINT, "PHONE", photos);
    }

    private static String setBody(String rentalId, String checkpointId, String source, List<String> photos) {
        return "{\"rentalId\":\"" + rentalId + "\",\"checkpointId\":\"" + checkpointId + "\",\"source\":\"" + source
                + "\",\"photos\":[" + String.join(",", photos) + "]}";
    }

    private static String photo(String face, String light, String camera) {
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\",\"sha256\":\""
                + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}";
    }
}
