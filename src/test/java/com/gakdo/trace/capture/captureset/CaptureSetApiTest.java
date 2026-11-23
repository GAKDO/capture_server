// 이 파일이 확인하는 것: 촬영 세트 API의 수용 시나리오. 부스·폰이 사진을 올리는 과정 전체를 웹 요청으로 확인한다.
// - 세트 만들기: 찍을 사진 목록을 알리면 사진마다 10분짜리 업로드 주소(사전 서명 URL)를 받는다.
// - 완료: 필수 면이 다 올라갔고 해시가 맞으면 "올림 완료"가 되고 "업로드 완료" 이벤트가 나간다.
// - 범위: 부스·고객 촬영 토큰은 자기 부스·자기 시점 세트만 다룰 수 있다.
// - 업로드 주소 다시 받기, 검사 방식 고정(수령·반납은 출고와 같은 방식).
// DB·MinIO·RabbitMQ 없이 메모리 저장소로 돈다(실제 연결은 CaptureIntegrationTest).

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
import com.gakdo.trace.capture.captureset.api.RentalProfileController;
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
@WebMvcTest(controllers = {CaptureSetController.class, RentalProfileController.class})
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

    private final Map<String, CaptureSet> saved = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    // 테스트 도구: 시험마다 메모리 사진 저장소를 비우고, 세트 저장소를 메모리 지도(saved)로 흉내 낸다(만든 순서 유지).
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
        // 만든 순서(LinkedHashMap) = id 순서
        when(repository.findByRentalIdOrderByIdAsc(anyString())).thenAnswer(inv -> {
            synchronized (saved) {
                return saved.values().stream().filter(s -> s.getRentalId().equals(inv.getArgument(0))).toList();
            }
        });
    }

    // ── 검사 방식 고정(11/13) ──

    // [상황] 출고를 부스 전체 검사로 완료한 렌탈에서, 수령을 빠른 검사로 만들려 하고 다음엔 전체 검사로 만든다.
    // [기대] 빠른 검사는 400(profile 칸 오류, 안내에 "전체 검사")으로 거절하고, 전체 검사는 201로 만들어진다.
    @Test
    @DisplayName("출고를 전체 검사로 완료한 렌탈은 수령을 빠른 검사로 만들면 400(profile), 전체 검사는 201")
    void receiptMustFollowCheckoutFull() throws Exception {
        completedBooth(boothBody(List.of()));
        perform(post("/api/v1/captures/sets").with(booth()), quickBody(OTHER_CHECKPOINT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].field").value("profile"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("전체 검사")));
        created(booth(), boothBody(List.of()).replace(CHECKPOINT, OTHER_CHECKPOINT));
    }

    // [상황] 출고를 빠른 검사로 완료한 렌탈에서, 수령을 부스 전체 검사로, 또 폰으로 만들려 하고, 마지막엔 빠른 검사로 만든다.
    // [기대] 전체 검사·폰은 400(profile)으로 거절하고, 빠른 검사만 201로 만들어진다.
    @Test
    @DisplayName("출고를 빠른 검사로 완료한 렌탈은 수령을 전체 검사·폰으로 만들면 400, 빠른 검사는 201")
    void receiptMustFollowCheckoutQuick() throws Exception {
        completedBooth(quickBody(CHECKPOINT));
        perform(post("/api/v1/captures/sets").with(booth()), boothBody(List.of()).replace(CHECKPOINT, OTHER_CHECKPOINT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("profile"));
        perform(post("/api/v1/captures/sets").with(capture(OTHER_CHECKPOINT, RENTAL)),
                phoneBody().replace(CHECKPOINT, OTHER_CHECKPOINT))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("profile"));
        created(booth(), quickBody(OTHER_CHECKPOINT));
    }

    // [상황] 출고 세트를 만들었지만 아직 완료하지 않았다. 다른 시점을 다른 방식(빠른 검사)으로 만든다.
    // [기대] 완료된 세트가 없으면 기준이 없어 어느 방식이든 만들 수 있고, 기준 방식 조회에도 방식이 없다.
    @Test
    @DisplayName("완료하지 않은 세트만 있거나 같은 시점을 다시 찍을 때는 방식을 바꿀 수 있다")
    void noReferenceYet() throws Exception {
        created(booth(), boothBody(List.of())); // 완료 전: 기준 아님
        created(booth(), quickBody(OTHER_CHECKPOINT));
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").with(booth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rentalId").value(RENTAL))
                .andExpect(jsonPath("$.profile").doesNotExist());
    }

    // [상황] 출고를 빠른 검사로 완료한 뒤 여러 토큰으로 "이 렌탈의 기준 방식"을 조회한다.
    // [기대] 다른 시점을 찍으려는 부스에는 QUICK과 기준 시점(출고)을 알려 준다. 출고 시점 자신을 다시 찍는 경우엔 기준이 없다.
    //        그 렌탈의 고객 촬영 토큰·일반 고객은 볼 수 있고, 다른 렌탈이면 403이다.
    @Test
    @DisplayName("기준 방식 조회: 지금 찍는 시점은 빼고 출고 세트 방식을 돌려준다, 범위 밖 토큰은 403")
    void rentalProfileQuery() throws Exception {
        completedBooth(quickBody(CHECKPOINT));
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").param("checkpointId", OTHER_CHECKPOINT)
                .with(booth()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").value("QUICK"))
                .andExpect(jsonPath("$.checkpointId").value(CHECKPOINT));
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").param("checkpointId", CHECKPOINT)
                .with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.profile").doesNotExist());
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").with(capture(OTHER_CHECKPOINT, RENTAL)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("QUICK"));
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").with(customer()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/captures/rentals/" + OTHER_RENTAL + "/profile").with(capture()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/captures/rentals/" + OTHER_RENTAL + "/profile").with(customer()))
                .andExpect(status().isForbidden());
    }

    // 테스트 도구: 부스 세트를 만들고 사진을 다 올린 것처럼 한 뒤 완료까지 한다.
    private void completedBooth(String body) throws Exception {
        String setId = created(booth(), body).get("captureSetId").asText();
        uploadAll(setId);
        complete(setId, booth(), "key-" + setId, null).andExpect(status().isAccepted());
    }

    // 테스트 도구: 그 시점의 부스 빠른 검사 세트 요청 본문(TOP·BOT × CAM1·CAM2).
    private static String quickBody(String checkpointId) {
        List<String> photos = new ArrayList<>();
        for (String face : List.of("TOP", "BOT")) {
            photos.add(photo(face, "L1", "CAM1"));
            photos.add(photo(face, "L1", "CAM2"));
        }
        return setBody(RENTAL, checkpointId, "BOOTH", photos).replace("\"source\"", "\"profile\":\"QUICK\",\"source\"");
    }

    // ── 이야기 1: 세트 생성 ──

    // [상황] 부스가 8면 × 두 카메라 + 흰 화면(SCR1) 사진 목록으로 세트를 만든다.
    // [기대] 21자 세트 ID와, 요청과 같은 순서·개수의 업로드 주소를 받는다. 주소마다 면·조명·카메라, PUT 방식,
    //        사진 형식·SHA-256 체크섬 헤더(바꿔치기 방지), UTC 만료 시각이 있고 주소는 사진마다 다르다.
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

    // [상황] 부스가 세트를 만들고 관리자가 조회한다.
    // [기대] 세트의 부스 ID는 요청 본문이 아니라 부스 토큰에서 가져온 값이고, 출처 BOOTH·상태 "올리는 중"·품질 OK·아직 안 올린 사진으로 보인다.
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

    // [상황] 같은 (면, 조명, 카메라) 사진을 두 번 알린다.
    // [기대] 400 VALIDATION_FAILED, 틀린 칸은 photos[1]이다.
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

    // [상황] 폰 세트인데 카메라를 CAM1(부스 카메라)로 적었다.
    // [기대] 400, 틀린 칸은 photos[0].camera다.
    @Test
    @DisplayName("source와 맞지 않는 카메라(PHONE인데 CAM1)는 400")
    void wrongCamera() throws Exception {
        String body = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "CAM1")));
        perform(post("/api/v1/captures/sets").with(capture()), body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("photos[0].camera"));
    }

    // [상황] 촬영 시각에 시간대가 없거나, 모르는 칸(boothId)을 넣거나, 해시를 대문자로 적거나, 사진 목록이 비어 있다.
    // [기대] 모두 400으로 거절한다(고객이 boothId를 마음대로 넣지 못함).
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

    // [상황] QR로 받은 고객 촬영 토큰으로 다른 시점, 다른 렌탈, 부스 출처 세트를 만들려 한다.
    // [기대] 모두 403 OUT_OF_SCOPE(토큰 범위 밖)다.
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

    // [상황] 부스가 한 시점에 세트를 만든 뒤, 같은 시점에 다른 렌탈로 세트를 만들려 하고, 같은 렌탈로 다시 찍는다.
    // [기대] 다른 렌탈은 403 OUT_OF_SCOPE(시점과 렌탈이 어긋나는 것 방지), 같은 렌탈 재촬영은 만들어진다.
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

    // [상황] 부스 토큰으로 폰 출처 세트를 만들려 한다.
    // [기대] 403 OUT_OF_SCOPE다.
    @Test
    @DisplayName("부스 토큰으로 source=PHONE 세트를 만들면 403 OUT_OF_SCOPE")
    void boothScope() throws Exception {
        perform(post("/api/v1/captures/sets").with(booth()),
                setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE"))))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    // [상황] 촬영 범위가 없는 일반 고객 토큰으로 세트 만들기·완료·조회를 하고, 관리자 토큰으로 세트를 만들려 한다.
    // [기대] 일반 고객은 모두 403 FORBIDDEN이고, 관리자도 세트는 만들 수 없다(403). 사진은 부스나 QR 촬영으로만 올린다.
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

    // [상황] 토큰 없이 세트를 조회한다.
    // [기대] 401 UNAUTHENTICATED를 오류 형식(Problem Details)으로 돌려준다.
    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHENTICATED Problem Details")
    void unauthenticated() throws Exception {
        mvc.perform(get("/api/v1/captures/sets/" + CHECKPOINT))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // [상황] 다른 부스(booth-02)나 고객 촬영 토큰으로 부스 세트를 완료하려 한다. 폰 세트는 자기 시점·다른 시점 토큰·부스 토큰으로 조회한다.
    // [기대] 다른 부스·고객의 부스 세트 완료는 403 OUT_OF_SCOPE. 폰 세트는 자기 시점 토큰만 볼 수 있고,
    //        다른 시점은 403 OUT_OF_SCOPE, 부스는 403 FORBIDDEN이다. 없는 세트는 관리자에게도 404다.
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

    // [상황] 부스 세트의 필수 면 사진을 다 올리고 완료한다.
    // [기대] 202와 "올림 완료"(UPLOADED), 사진은 올라감으로 보인다. "업로드 완료" 이벤트가 딱 하나 나가며,
    //        렌탈·시점·세트 ID, 사진 16장(저장 경로 포함), 부스는 이미 탐지를 했으므로 탐지 필요 없음(needDetection=false)이 실린다.
    //        건너뛴 면·기종 정보가 없으면 이벤트에서 그 칸을 빼고, 이벤트 ID는 21자다.
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

    // [상황] 부스 세트를 완료하려는데 키보드(KB) 사진만 실제로 올라가지 않았다.
    // [기대] 409 CAPTURE_FACES_MISSING(빠진 면 KB)으로 완료되지 않고, 이벤트도 없으며, 세트는 "올리는 중" 그대로다.
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

    // [상황] 폰 세트를 만들 때 기종 코드와 치수(가로·깊이·본체·덮개 두께)를 함께 보낸다.
    // [기대] 완료 이벤트에 기종·치수가 그대로 실린다(뒤 단계 worker가 기기 크기를 쓸 수 있게).
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

    // [상황] 치수가 0이거나 기종 코드가 소문자(형식 틀림)다.
    // [기대] 400 VALIDATION_FAILED다.
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

    // [상황] 폰 세트(7면 + 흰 화면)를 다 올리고 완료한다.
    // [기대] 키보드 없이 202로 완료되고, 이벤트에 서버 탐지 필요(needDetection=true, 폰은 부스 탐지가 없음)와 올린 사진 8장이 실린다.
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

    // [상황] 흰 화면(SCR1) 사진을 알렸지만 올리지 못했다. 이유 없이 완료하고, 다음엔 "화면이 켜지지 않음" 이유를 붙여 완료한다.
    // [기대] 이유가 없으면 400(skippedFaces.SCR1), 이유가 있으면 202이고 이벤트에 SCR1 사진 없이 건너뛴 면과 이유가 실린다.
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

    // [상황] 부스 세트를 완료하면서 필수 면인 키보드를 "못 찍음"으로 건너뛰려 한다.
    // [기대] 400 VALIDATION_FAILED, 틀린 칸은 skippedFaces.KB다.
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

    // [상황] 폰 세트의 윗면 사진이 알린 해시와 다른 파일로 올라갔다.
    // [기대] 409 CAPTURE_HASH_MISMATCH로 완료를 거절한다.
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

    // [상황] 완료 요청을 Idempotency-Key 없이/너무 짧게 보내고, 같은 키·같은 본문으로 두 번, 같은 키·다른 본문, 다른 키로 보낸다.
    // [기대] 키가 없거나 짧으면 400. 같은 키·같은 본문은 처음 응답을 그대로 돌려주고 이벤트를 다시 내지 않는다(네트워크 재시도 안전).
    //        같은 키·다른 본문은 422 IDEMPOTENCY_KEY_CONFLICT, 다른 키는 이미 완료라 409 CAPTURE_SET_CLOSED다.
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

    // [상황] 폰 세트를 만들고 윗면 사진만 올린 상태에서 조회한다.
    // [기대] 시점 ID가 보이고 부스 ID는 비어 있으며, 윗면은 올라감, 나머지는 아직 안 올라감으로 보인다(진행률 표시용).
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

    // [상황] 업로드 주소가 만료돼 세트를 새로 만들지 않고 주소를 다시 받는다(본문 없이, 그리고 빈 본문 {}로).
    // [기대] 남은 사진 전부의 새 주소를 세트 만들 때와 같은 순서·형식으로 받는다. 면·조명·카메라·헤더는 같고,
    //        주소는 새것이며 만료는 약 10분 뒤다. 세트는 여전히 "올리는 중"이다.
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

    // [상황] 폰 세트에서 윗면만 올린 뒤 주소를 다시 받고, 특정 사진 세 장(SCR1·TOP·BOT)만 골라 다시 받고, 다 올린 뒤 또 받는다.
    // [기대] 이미 올린 윗면은 빼고 7장만 준다. 골라 받으면 그중 안 올린 BOT·SCR1만 세트 순서대로 준다. 다 올렸으면 빈 목록이다.
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

    // [상황] 세트에 없는 사진(윗면 L2)을 고르거나, 빈 목록, 카메라가 빠진 항목, 모르는 칸(sha256)이 든 항목으로 다시 받으려 한다.
    // [기대] 모두 400이고, 없는 사진은 몇 번째 항목인지(photos[1]) 알려 준다.
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

    // [상황] 이미 완료된 세트의 업로드 주소를 다시 받으려 한다.
    // [기대] 409 CAPTURE_SET_CLOSED다(완료 뒤 사진 바꿔 올리기 방지).
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

    // [상황] 다른 부스, 고객 촬영 토큰, 다른 시점 토큰, 부스(폰 세트에), 일반 고객, 관리자, 없는 세트로 주소를 다시 받으려 한다.
    // [기대] 다른 부스·다른 시점·남의 출처는 403 OUT_OF_SCOPE, 일반 고객·관리자는 403 FORBIDDEN, 없는 세트는 404다.
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

    // 테스트 도구: 업로드 주소 다시 받기 요청을 보낸다(본문은 있을 수도 없을 수도 있음).
    private ResultActions reissue(String setId, RequestPostProcessor token, String body) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/captures/sets/" + setId + "/uploads").with(token);
        return body == null ? mvc.perform(request) : perform(request, body);
    }

    // 테스트 도구: 응답 문자열을 JSON으로 읽는다.
    private JsonNode tree(String body) {
        try {
            return json.readTree(body);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // 테스트 도구: 다시 받을 사진 하나(면·조명·카메라) JSON.
    private static String ref(String face, String light, String camera) {
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\"}";
    }

    // 테스트 도구: 세트를 만들고 201인지 확인한 뒤 응답 JSON을 돌려준다.
    private JsonNode created(RequestPostProcessor token, String body)
            throws Exception {
        String response = perform(post("/api/v1/captures/sets").with(token), body)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    // 테스트 도구: 그 키로 완료 요청을 보낸다(본문은 있을 수도 없을 수도 있음).
    private ResultActions complete(String setId,
            RequestPostProcessor token, String key, String body)
            throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/captures/sets/" + setId + "/complete").with(token)
                .header("Idempotency-Key", key);
        return body == null ? mvc.perform(request) : perform(request, body);
    }

    // 테스트 도구: JSON 본문을 붙여 요청을 보낸다.
    private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 세트의 모든 사진을 알린 해시로 "올린다". */
    // 테스트 도구: 세트의 모든 사진을 알린 해시 그대로 "올린" 것으로 메모리 저장소에 넣는다.
    private void uploadAll(String setId) {
        saved.get(setId).getPhotos().forEach(p -> storage.put(p.getObjectKey(), p.getSha256()));
    }

    // 테스트 도구: 부스 8면 × CAM1·CAM2(L1) 세트 요청 본문. optional에 적은 선택 면(예: SCR1)을 CAM1 한 장씩 더한다.
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

    // 테스트 도구: 시험용 기종 정보(GRAM16-2024, 치수 mm).
    private static final String DEVICE = "\"device\":{\"modelCode\":\"GRAM16-2024\",\"dimensions\":"
            + "{\"widthMm\":357.7,\"depthMm\":250.0,\"baseHMm\":11.0,\"lidHMm\":5.0}}";

    // 테스트 도구: 폰 7면 + 흰 화면(SCR1) 세트 요청 본문.
    private static String phoneBody() {
        List<String> photos = new ArrayList<>(PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList());
        photos.add(photo("SCR1", "OFF", "PHONE"));
        return setBody(RENTAL, CHECKPOINT, "PHONE", photos);
    }

    // 테스트 도구: 세트 만들기 요청 본문(렌탈·시점·출처·사진 목록).
    private static String setBody(String rentalId, String checkpointId, String source, List<String> photos) {
        return "{\"rentalId\":\"" + rentalId + "\",\"checkpointId\":\"" + checkpointId + "\",\"source\":\"" + source
                + "\",\"photos\":[" + String.join(",", photos) + "]}";
    }

    // 테스트 도구: 알릴 사진 한 장(면·조명·카메라·해시·형식·촬영 시각) JSON.
    private static String photo(String face, String light, String camera) {
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\",\"sha256\":\""
                + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}";
    }
}
