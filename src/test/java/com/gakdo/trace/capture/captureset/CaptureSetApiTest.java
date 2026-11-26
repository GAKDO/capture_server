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
// (도구) @WebMvcTest = 서버 전체 대신 지정한 컨트롤러(촬영 세트·기준 방식 조회)만 띄우는 가벼운 API 테스트. WebTestBase로 운영 보안 규칙도 함께 띄운다
@WebMvcTest(controllers = {CaptureSetController.class, RentalProfileController.class})
// (도구) @Import = 가짜가 아닌 진짜 서비스(CaptureSetService)와 메모리 사진 저장소(FakePhotoStorage)를 함께 띄운다. 서비스 규칙까지 실제로 돌려 보려고
@Import({CaptureSetService.class, FakePhotoStorage.class})
class CaptureSetApiTest extends WebTestBase {

    // 사진 내용 해시(SHA-256, 소문자 64자) 예시 값. 사진마다 같은 값을 써서 "올라간 사진의 해시가 맞다"를 흉내 낸다
    private static final String SHA = "9f2c4e0b7a1d3c5e8f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e";
    // 해시가 다를 때(사진이 바뀌었을 때)를 시험하려고 쓰는 다른 해시 값
    private static final String OTHER_SHA = "3a7bd3e2360a3d29eea436fcfb7e44c735d117c42d1c1835420b6b9942dd4f1b";
    // 부스 전체 검사에서 반드시 찍는 8면(윗면·바닥·앞·뒤·왼·오른 옆면·키보드·검은 화면)
    private static final List<String> BOOTH_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "KB", "SCR0");
    // 폰 촬영에서 반드시 찍는 7면(부스 목록에서 키보드만 빠짐)
    private static final List<String> PHONE_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "SCR0");

    // (도구) MockMvc = 진짜 네트워크 없이 서버에 HTTP 요청을 보낸 것처럼 흉내 내는 도구. @Autowired = 스프링이 만들어 넣어 준다
    @Autowired
    private MockMvc mvc;

    // 요청 본문을 JSON으로 읽는 도구(ObjectMapper)를 스프링에게서 받는다
    @Autowired
    private ObjectMapper json;

    // "사진이 올라갔다"를 흉내 낼 메모리 사진 저장소를 받는다
    @Autowired
    private FakePhotoStorage storage;

    // (도구) @MockitoBean = 진짜 대신 가짜(목)를 스프링에 끼워 넣는다. 여기서는 DB 없이 돌리려고 세트 저장소를 가짜로 바꾼다
    @MockitoBean
    private CaptureSetRepository repository;

    // 이벤트(업로드 완료) 발행기도 가짜로 바꿔, RabbitMQ 없이 "이벤트를 보냈는지"만 확인한다
    @MockitoBean
    private CaptureEventPublisher events;

    // 가짜 저장소가 실제로 담아 둘 메모리 지도(세트 ID → 세트). 여러 스레드가 동시에 써도 안전하고, 넣은 순서를 지킨다
    private final Map<String, CaptureSet> saved = java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

    // 테스트 도구: 시험마다 메모리 사진 저장소를 비우고, 세트 저장소를 메모리 지도(saved)로 흉내 낸다(만든 순서 유지).
    @BeforeEach
    void memoryRepository() {
        // 준비: 앞 시험이 올린 사진이 남지 않게 메모리 사진 저장소를 비운다
        storage.clear();
        // 앞 시험이 만든 세트도 지운다
        saved.clear();
        // (도구) when(가짜.메서드(조건)).thenAnswer(inv -> ...) = "가짜에게 이 호출이 오면 이 코드를 돌려서 답해 줘". any(...) = 아무 값이나, inv.getArgument(0) = 호출 때 받은 첫 번째 값
        // 세트를 저장하라는 호출이 오면 메모리 지도에 넣고 그 세트를 그대로 돌려준다(진짜 DB 저장 흉내)
        when(repository.save(any(CaptureSet.class))).thenAnswer(inv -> {
            // 저장하라고 받은 세트를 꺼낸다
            CaptureSet set = inv.getArgument(0);
            // 세트 공개 ID를 열쇠로 메모리 지도에 넣는다
            saved.put(set.getPublicId(), set);
            // 저장된 세트를 돌려준다
            return set;
        });
        // (도구) anyString() = 아무 문자열이나
        // 고치려고 세트를 찾는 호출이 오면 메모리 지도에서 찾아 돌려준다(없으면 빈 값)
        when(repository.findForUpdate(anyString())).thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));
        // 공개 ID로 세트를 조회하는 호출도 메모리 지도에서 찾아 답한다
        when(repository.findByPublicId(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get(inv.getArgument(0))));
        // "이 시점 ID를 다른 렌탈이 이미 쓰고 있나?" 호출이 오면, 메모리 지도에서 같은 시점·다른 렌탈 세트가 있는지로 답한다
        when(repository.existsByCheckpointIdAndRentalIdNot(anyString(), anyString())).thenAnswer(inv -> saved.values()
                .stream().anyMatch(s -> s.getCheckpointId().equals(inv.getArgument(0))
                        && !s.getRentalId().equals(inv.getArgument(1))));
        // 만든 순서(LinkedHashMap) = id 순서
        // 렌탈의 세트 목록을 달라는 호출이 오면 그 렌탈 세트만 만든 순서대로 돌려준다
        when(repository.findByRentalIdOrderByIdAsc(anyString())).thenAnswer(inv -> {
            // 목록을 훑는 동안 다른 스레드가 지도를 바꾸지 못하게 잠근다
            synchronized (saved) {
                // 그 렌탈 ID를 가진 세트만 골라 목록으로 돌려준다
                return saved.values().stream().filter(s -> s.getRentalId().equals(inv.getArgument(0))).toList();
            }
        });
    }

    // ── 검사 방식 고정(11/13) ──

    // [상황] 출고를 부스 전체 검사로 완료한 렌탈에서, 수령을 빠른 검사로 만들려 하고 다음엔 전체 검사로 만든다.
    // [기대] 빠른 검사는 400(profile 칸 오류, 안내에 "전체 검사")으로 거절하고, 전체 검사는 201로 만들어진다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 201 = 새로 만들어짐
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("출고를 전체 검사로 완료한 렌탈은 수령을 빠른 검사로 만들면 400(profile), 전체 검사는 201")
    void receiptMustFollowCheckoutFull() throws Exception {
        // 준비: 출고 시점(CHECKPOINT)을 부스 전체 검사로 찍어 완료까지 해 둔다 = 이 렌탈의 기준 방식이 "전체 검사"가 된다
        completedBooth(boothBody(List.of()));
        // (도구) perform(post(주소).with(토큰), 본문) = 이 파일 아래 도우미. 그 토큰을 가진 사용자로 JSON 본문을 담아 POST 요청을 보낸다. andExpect(...) = 응답이 이래야 한다는 검사
        // 실행: 수령 시점(OTHER_CHECKPOINT)을 빠른 검사로 만들어 달라고 부스 토큰으로 요청한다
        perform(post("/api/v1/captures/sets").with(booth()), quickBody(OTHER_CHECKPOINT))
                // (도구) status().isBadRequest() = 응답 코드가 400(요청 내용이 맞지 않음)인지 본다
                // 확인: 출고와 방식이 달라 400이어야 한다
                .andExpect(status().isBadRequest())
                // (도구) jsonPath("$.칸") = 응답 JSON에서 그 칸 값을 꺼낸다. value(값) = 그 값과 같은지 본다
                // 확인: 오류 코드가 VALIDATION_FAILED(입력값 검사 실패)여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // 확인: 틀린 칸이 profile(검사 방식)이라고 알려 줘야 한다
                .andExpect(jsonPath("$.errors[0].field").value("profile"))
                // (도구) containsString("글자") = 그 글자가 들어 있으면 통과
                // 확인: 안내 문구에 "전체 검사"가 들어 있어야 한다 = 어떤 방식으로 찍어야 하는지 알려 준다
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("전체 검사")));
        // 실행+확인: 같은 수령 시점을 출고와 같은 전체 검사로 만들면 201로 만들어져야 한다(created 도우미가 201을 검사)
        created(booth(), boothBody(List.of()).replace(CHECKPOINT, OTHER_CHECKPOINT));
    }

    // [상황] 출고를 빠른 검사로 완료한 렌탈에서, 수령을 부스 전체 검사로, 또 폰으로 만들려 하고, 마지막엔 빠른 검사로 만든다.
    // [기대] 전체 검사·폰은 400(profile)으로 거절하고, 빠른 검사만 201로 만들어진다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 201 = 새로 만들어짐
    @Test
    @DisplayName("출고를 빠른 검사로 완료한 렌탈은 수령을 전체 검사·폰으로 만들면 400, 빠른 검사는 201")
    void receiptMustFollowCheckoutQuick() throws Exception {
        // 준비: 출고 시점을 빠른 검사로 찍어 완료까지 해 둔다 = 기준 방식이 "빠른 검사"
        completedBooth(quickBody(CHECKPOINT));
        // 실행: 수령 시점을 부스 전체 검사로 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(booth()), boothBody(List.of()).replace(CHECKPOINT, OTHER_CHECKPOINT))
                // 확인: 방식이 달라 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 틀린 칸이 profile이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("profile"));
        // 실행: 수령 시점 고객 촬영 토큰으로 폰 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(capture(OTHER_CHECKPOINT, RENTAL)),
                phoneBody().replace(CHECKPOINT, OTHER_CHECKPOINT))
                // 확인: 폰 촬영도 빠른 검사와 방식이 달라 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 틀린 칸이 profile이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("profile"));
        // 실행+확인: 출고와 같은 빠른 검사로 만들면 201로 만들어져야 한다
        created(booth(), quickBody(OTHER_CHECKPOINT));
    }

    // [상황] 출고 세트를 만들었지만 아직 완료하지 않았다. 다른 시점을 다른 방식(빠른 검사)으로 만든다.
    // [기대] 완료된 세트가 없으면 기준이 없어 어느 방식이든 만들 수 있고, 기준 방식 조회에도 방식이 없다.
    @Test
    @DisplayName("완료하지 않은 세트만 있거나 같은 시점을 다시 찍을 때는 방식을 바꿀 수 있다")
    void noReferenceYet() throws Exception {
        // 준비: 출고 시점 전체 검사 세트를 만들기만 하고 완료하지 않는다
        created(booth(), boothBody(List.of())); // 완료 전: 기준 아님
        // 실행+확인: 다른 시점을 빠른 검사로 만들어도 201이어야 한다 = 완료된 세트가 없으면 기준 방식이 없다
        created(booth(), quickBody(OTHER_CHECKPOINT));
        // (도구) mvc.perform(get(주소).with(토큰)) = 그 토큰으로 GET 요청을 보낸다
        // 실행: 이 렌탈의 기준 방식을 부스 토큰으로 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").with(booth()))
                // (도구) status().isOk() = 응답 코드가 200(정상)인지 본다
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 응답의 렌탈 ID가 물어본 렌탈이어야 한다
                .andExpect(jsonPath("$.rentalId").value(RENTAL))
                // (도구) doesNotExist() = 그 칸이 응답에 없어야 통과
                // 확인: 완료된 세트가 없으니 기준 방식(profile) 칸이 없어야 한다
                .andExpect(jsonPath("$.profile").doesNotExist());
    }

    // [상황] 출고를 빠른 검사로 완료한 뒤 여러 토큰으로 "이 렌탈의 기준 방식"을 조회한다.
    // [기대] 다른 시점을 찍으려는 부스에는 QUICK과 기준 시점(출고)을 알려 준다. 출고 시점 자신을 다시 찍는 경우엔 기준이 없다.
    //        그 렌탈의 고객 촬영 토큰·일반 고객은 볼 수 있고, 다른 렌탈이면 403이다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("기준 방식 조회: 지금 찍는 시점은 빼고 출고 세트 방식을 돌려준다, 범위 밖 토큰은 403")
    void rentalProfileQuery() throws Exception {
        // 준비: 출고 시점을 빠른 검사로 찍어 완료까지 해 둔다
        completedBooth(quickBody(CHECKPOINT));
        // (도구) param(이름, 값) = 주소 뒤에 ?이름=값을 붙인다
        // 실행: 부스가 수령 시점(OTHER_CHECKPOINT)을 찍으려고 기준 방식을 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").param("checkpointId", OTHER_CHECKPOINT)
                .with(booth()))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 기준 방식이 QUICK(빠른 검사)이어야 한다
                .andExpect(jsonPath("$.profile").value("QUICK"))
                // 확인: 기준이 된 시점이 출고 시점이어야 한다
                .andExpect(jsonPath("$.checkpointId").value(CHECKPOINT));
        // 실행: 관리자가 출고 시점 자신을 다시 찍는다고 보고 기준 방식을 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").param("checkpointId", CHECKPOINT)
                .with(admin()))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 지금 찍는 시점은 빼고 보므로 기준 방식이 없어야 한다(같은 시점은 방식을 바꿔 다시 찍을 수 있음)
                .andExpect(jsonPath("$.profile").doesNotExist());
        // 실행: 이 렌탈의 고객 촬영 토큰(수령 시점)으로 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").with(capture(OTHER_CHECKPOINT, RENTAL)))
                // 확인: 200이고 기준 방식이 QUICK이어야 한다
                .andExpect(status().isOk()).andExpect(jsonPath("$.profile").value("QUICK"));
        // 실행: 이 렌탈을 가진 일반 고객 토큰으로 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + RENTAL + "/profile").with(customer()))
                // 확인: 자기 렌탈이라 200이어야 한다
                .andExpect(status().isOk());
        // 실행: 다른 렌탈의 기준 방식을 고객 촬영 토큰으로 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + OTHER_RENTAL + "/profile").with(capture()))
                // (도구) status().isForbidden() = 응답 코드가 403(권한 없음)인지 본다
                // 확인: 토큰 범위 밖이라 403이어야 한다
                .andExpect(status().isForbidden());
        // 실행: 다른 렌탈의 기준 방식을 일반 고객 토큰으로 조회한다
        mvc.perform(get("/api/v1/captures/rentals/" + OTHER_RENTAL + "/profile").with(customer()))
                // 확인: 자기 렌탈이 아니라 403이어야 한다
                .andExpect(status().isForbidden());
    }

    // 테스트 도구: 부스 세트를 만들고 사진을 다 올린 것처럼 한 뒤 완료까지 한다.
    private void completedBooth(String body) throws Exception {
        // 부스 토큰으로 세트를 만들고 응답에서 세트 ID를 꺼낸다
        String setId = created(booth(), body).get("captureSetId").asText();
        // 그 세트의 사진이 모두 올라간 것처럼 메모리 저장소에 넣는다
        uploadAll(setId);
        // 완료 요청을 보내고 202(받아들임)인지 확인한다 = 완료된 세트가 된다
        complete(setId, booth(), "key-" + setId, null).andExpect(status().isAccepted());
    }

    // 테스트 도구: 그 시점의 부스 빠른 검사 세트 요청 본문(TOP·BOT × CAM1·CAM2).
    private static String quickBody(String checkpointId) {
        // 사진 목록을 담을 빈 목록을 만든다
        List<String> photos = new ArrayList<>();
        // 윗면·바닥 두 면을 차례로
        for (String face : List.of("TOP", "BOT")) {
            // 조명 L1·카메라 CAM1 사진을 넣는다
            photos.add(photo(face, "L1", "CAM1"));
            // 조명 L1·카메라 CAM2 사진을 넣는다
            photos.add(photo(face, "L1", "CAM2"));
        }
        // 부스 세트 본문을 만들고 source 앞에 "profile":"QUICK"을 끼워 빠른 검사 요청으로 바꿔 돌려준다
        return setBody(RENTAL, checkpointId, "BOOTH", photos).replace("\"source\"", "\"profile\":\"QUICK\",\"source\"");
    }

    // ── 이야기 1: 세트 생성 ──

    // [상황] 부스가 8면 × 두 카메라 + 흰 화면(SCR1) 사진 목록으로 세트를 만든다.
    // [기대] 21자 세트 ID와, 요청과 같은 순서·개수의 업로드 주소를 받는다. 주소마다 면·조명·카메라, PUT 방식,
    //        사진 형식·SHA-256 체크섬 헤더(바꿔치기 방지), UTC 만료 시각이 있고 주소는 사진마다 다르다.
    @Test
    @DisplayName("업로드 URL 응답은 사진마다 face·light·camera를 돌려준다(요청과 같은 순서·개수, PUT, 체크섬 헤더, 10분)")
    void createBooth() throws Exception {
        // 실행: 부스가 8면 × 카메라 2대 + 흰 화면(SCR1) 사진 목록으로 세트를 만든다(201 확인 포함)
        JsonNode body = created(booth(), boothBody(List.of("SCR1")));
        // (도구) assertThat(값).검사() = "값이 이래야 한다"를 확인하는 AssertJ 도구. matches(정규식) = 글자 모양이 규칙에 맞는지 본다
        // 확인: 세트 ID가 21자 공개 ID 모양이어야 한다
        assertThat(body.get("captureSetId").asText()).matches("^[A-Za-z0-9_-]{21}$");
        // 응답에서 업로드 주소 목록을 꺼낸다
        JsonNode uploads = body.get("uploads");
        // (도구) hasSize(n) = 개수가 n개여야 통과
        // 확인: 업로드 주소가 요청한 사진 수(8면×2 + 1 = 17개)와 같아야 한다
        assertThat(uploads).hasSize(BOOTH_FACES.size() * 2 + 1);
        // (도구) isEqualTo(값) = 그 값과 같아야 통과
        // 확인: 첫 주소가 윗면(TOP)이어야 한다 = 요청과 같은 순서
        assertThat(uploads.get(0).get("face").asText()).isEqualTo("TOP");
        // 확인: 첫 주소의 카메라가 CAM1이어야 한다
        assertThat(uploads.get(0).get("camera").asText()).isEqualTo("CAM1");
        // 확인: 두 번째 주소의 카메라가 CAM2여야 한다
        assertThat(uploads.get(1).get("camera").asText()).isEqualTo("CAM2");
        // 확인: 마지막 주소가 흰 화면(SCR1)이어야 한다
        assertThat(uploads.get(uploads.size() - 1).get("face").asText()).isEqualTo("SCR1");
        // 확인: 업로드 방식이 PUT이어야 한다
        assertThat(uploads.get(0).get("method").asText()).isEqualTo("PUT");
        // 확인: 함께 보낼 파일 종류 머리말이 image/jpeg여야 한다
        assertThat(uploads.get(0).get("headers").get("Content-Type").asText()).isEqualTo("image/jpeg");
        // 확인: 체크섬 머리말이 사진 해시를 Base64로 바꾼 값이어야 한다 = 다른 파일로 바꿔치기하면 MinIO가 거절
        assertThat(uploads.get(0).get("headers").get("x-amz-checksum-sha256").asText())
                .isEqualTo(java.util.Base64.getEncoder().encodeToString(java.util.HexFormat.of().parseHex(SHA)));
        // (도구) endsWith("Z") = 글자가 Z로 끝나야 통과(Z = UTC 시각 표시)
        // 확인: 만료 시각이 UTC로 적혀 있어야 한다
        assertThat(uploads.get(0).get("expiresAt").asText()).endsWith("Z");
        // (도구) isNotEqualTo(값) = 그 값과 달라야 통과
        // 확인: 사진마다 업로드 주소가 서로 달라야 한다
        assertThat(uploads.get(0).get("url").asText()).isNotEqualTo(uploads.get(1).get("url").asText());
    }

    // [상황] 부스가 세트를 만들고 관리자가 조회한다.
    // [기대] 세트의 부스 ID는 요청 본문이 아니라 부스 토큰에서 가져온 값이고, 출처 BOOTH·상태 "올리는 중"·품질 OK·아직 안 올린 사진으로 보인다.
    @Test
    @DisplayName("만든 세트의 boothId는 부스 토큰의 scope이고, 관리자는 어느 세트든 조회한다")
    void boothIdFromToken() throws Exception {
        // 준비: 부스 토큰으로 전체 검사 세트를 만들고 세트 ID를 꺼낸다
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 실행: 관리자 토큰으로 그 세트를 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                // 확인: 관리자는 어느 세트든 볼 수 있어 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 부스 ID가 부스 토큰의 값(booth-01)이어야 한다 = 본문이 아니라 토큰에서 가져옴
                .andExpect(jsonPath("$.boothId").value(BOOTH))
                // 확인: 출처가 BOOTH여야 한다
                .andExpect(jsonPath("$.source").value("BOOTH"))
                // 확인: 상태가 UPLOADING(올리는 중)이어야 한다
                .andExpect(jsonPath("$.status").value("UPLOADING"))
                // 확인: 품질이 OK여야 한다
                .andExpect(jsonPath("$.quality").value("OK"))
                // 확인: 첫 사진은 아직 올라가지 않은 상태(false)여야 한다
                .andExpect(jsonPath("$.photos[0].uploaded").value(false));
    }

    // [상황] 같은 (면, 조명, 카메라) 사진을 두 번 알린다.
    // [기대] 400 VALIDATION_FAILED, 틀린 칸은 photos[1]이다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("같은 세트 안에 (face, light, camera)가 겹치면 400 VALIDATION_FAILED(photos[i])")
    void duplicatePhoto() throws Exception {
        // 준비: 같은 (윗면, L1, CAM1) 사진이 두 번 들어간 부스 세트 본문을 만든다
        String body = setBody(RENTAL, CHECKPOINT, "BOOTH",
                List.of(photo("TOP", "L1", "CAM1"), photo("TOP", "L1", "CAM1")));
        // 실행: 그 본문으로 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(booth()), body)
                // 확인: 겹치는 사진이 있어 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // 확인: 틀린 칸이 두 번째 사진(photos[1])이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("photos[1]"));
    }

    // [상황] 폰 세트인데 카메라를 CAM1(부스 카메라)로 적었다.
    // [기대] 400, 틀린 칸은 photos[0].camera다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("source와 맞지 않는 카메라(PHONE인데 CAM1)는 400")
    void wrongCamera() throws Exception {
        // 준비: 폰 세트인데 카메라를 부스 카메라(CAM1)로 적은 본문을 만든다
        String body = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "CAM1")));
        // 실행: 고객 촬영 토큰으로 그 본문을 보낸다
        perform(post("/api/v1/captures/sets").with(capture()), body)
                // 확인: 출처와 카메라가 맞지 않아 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 틀린 칸이 첫 사진의 카메라여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("photos[0].camera"));
    }

    // [상황] 촬영 시각에 시간대가 없거나, 모르는 칸(boothId)을 넣거나, 해시를 대문자로 적거나, 사진 목록이 비어 있다.
    // [기대] 모두 400으로 거절한다(고객이 boothId를 마음대로 넣지 못함).
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("형식 오류(오프셋 없는 takenAt, 모르는 필드, 대문자 해시, 빈 사진 목록)는 400")
    void malformed() throws Exception {
        // 준비: 촬영 시각에서 시간대(+09:00)를 지운 폰 세트 본문을 만든다
        String noOffset = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")))
                .replace("+09:00", "");
        // 실행+확인: 시간대 없는 시각은 400이어야 한다
        perform(post("/api/v1/captures/sets").with(capture()), noOffset).andExpect(status().isBadRequest());
        // 준비: 고객이 보내면 안 되는 boothId 칸을 앞에 끼운 본문을 만든다
        String unknown = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")))
                .replace("{\"rentalId\"", "{\"boothId\":\"x\",\"rentalId\"");
        // 실행+확인: 모르는 칸이 있으면 400이어야 한다 = 고객이 부스 ID를 마음대로 넣지 못한다
        perform(post("/api/v1/captures/sets").with(capture()), unknown).andExpect(status().isBadRequest());
        // 준비: 해시를 대문자로 바꾼 본문을 만든다
        String upper = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")))
                .replace(SHA, SHA.toUpperCase());
        // 실행: 그 본문을 보낸다
        perform(post("/api/v1/captures/sets").with(capture()), upper)
                // 확인: 해시는 소문자만 받으므로 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 틀린 칸이 첫 사진의 sha256이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("photos[0].sha256"));
        // 실행: 사진 목록이 빈 본문을 보낸다
        perform(post("/api/v1/captures/sets").with(capture()), setBody(RENTAL, CHECKPOINT, "PHONE", List.of()))
                // 확인: 빈 사진 목록은 400이어야 한다
                .andExpect(status().isBadRequest());
    }

    // ── 이야기 3: 범위 ──

    // [상황] QR로 받은 고객 촬영 토큰으로 다른 시점, 다른 렌탈, 부스 출처 세트를 만들려 한다.
    // [기대] 모두 403 OUT_OF_SCOPE(토큰 범위 밖)다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("촬영 JWT로 다른 checkpointId·rentalId나 source=BOOTH로 세트를 만들면 403 OUT_OF_SCOPE")
    void captureTokenScope() throws Exception {
        // 준비: 폰 사진 한 장짜리 목록을 만든다
        List<String> photos = List.of(photo("TOP", "ROOM", "PHONE"));
        // 실행: 토큰에 없는 다른 시점으로 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(capture()), setBody(RENTAL, OTHER_CHECKPOINT, "PHONE", photos))
                // 확인: 403이고 오류 코드가 OUT_OF_SCOPE(토큰 범위 밖)여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 토큰에 없는 다른 렌탈로 요청한다
        perform(post("/api/v1/captures/sets").with(capture()), setBody(OTHER_RENTAL, CHECKPOINT, "PHONE", photos))
                // 확인: 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 고객 촬영 토큰으로 부스 출처(BOOTH) 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(capture()),
                setBody(RENTAL, CHECKPOINT, "BOOTH", List.of(photo("TOP", "L1", "CAM1"))))
                // 확인: 고객은 부스 세트를 못 만들어 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    // [상황] 부스가 한 시점에 세트를 만든 뒤, 같은 시점에 다른 렌탈로 세트를 만들려 하고, 같은 렌탈로 다시 찍는다.
    // [기대] 다른 렌탈은 403 OUT_OF_SCOPE(시점과 렌탈이 어긋나는 것 방지), 같은 렌탈 재촬영은 만들어진다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("이미 다른 렌탈로 세트가 있는 시점에 부스가 다른 렌탈로 세트를 만들면 403 OUT_OF_SCOPE, 같은 렌탈 재촬영은 된다")
    void boothCheckpointRentalPair() throws Exception {
        // 준비: 부스가 기본 렌탈(RENTAL)·출고 시점으로 세트를 하나 만든다
        created(booth(), boothBody(List.of()));
        // 준비: 같은 시점인데 렌탈 ID만 다른 렌탈(OTHER_RENTAL)로 바꾼 본문을 만든다
        String mismatched = boothBody(List.of()).replace("\"rentalId\":\"" + RENTAL + "\"",
                "\"rentalId\":\"" + OTHER_RENTAL + "\"");
        // (도구) contains(글자) = 그 글자가 들어 있어야 통과
        // 준비: (먼저 확인) 바꿔치기가 실제로 됐는지(본문에 다른 렌탈 ID가 있는지) 먼저 확인한다
        assertThat(mismatched).contains(OTHER_RENTAL);
        // 실행: 이미 다른 렌탈이 쓰는 시점에 다른 렌탈로 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(booth()), mismatched)
                // 확인: 시점과 렌탈 짝이 맞지 않아 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행+확인: 같은 렌탈로 같은 시점을 다시 찍는 것은 201로 만들어져야 한다(재촬영 허용)
        created(booth(), boothBody(List.of()));
    }

    // [상황] 부스 토큰으로 폰 출처 세트를 만들려 한다.
    // [기대] 403 OUT_OF_SCOPE다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("부스 토큰으로 source=PHONE 세트를 만들면 403 OUT_OF_SCOPE")
    void boothScope() throws Exception {
        // 실행: 부스 토큰으로 폰 출처(PHONE) 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(booth()),
                setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE"))))
                // 확인: 부스는 폰 세트를 못 만들어 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
    }

    // [상황] 촬영 범위가 없는 일반 고객 토큰으로 세트 만들기·완료·조회를 하고, 관리자 토큰으로 세트를 만들려 한다.
    // [기대] 일반 고객은 모두 403 FORBIDDEN이고, 관리자도 세트는 만들 수 없다(403). 사진은 부스나 QR 촬영으로만 올린다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일)
    @Test
    @DisplayName("scope 없는 일반 CUSTOMER 토큰은 세트 생성·완료·조회 모두 403 FORBIDDEN, ADMIN은 생성 403")
    void plainCustomer() throws Exception {
        // 준비: 폰 사진 한 장짜리 세트 본문을 만든다
        String body = setBody(RENTAL, CHECKPOINT, "PHONE", List.of(photo("TOP", "ROOM", "PHONE")));
        // 실행: 촬영 범위(scope)가 없는 일반 고객 토큰으로 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(customer()), body)
                // 확인: 403이고 오류 코드가 FORBIDDEN(권한 없음)이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 준비: 고객 촬영 토큰으로는 세트를 만들어 세트 ID를 받아 둔다(다음 요청에 쓰려고)
        String setId = created(capture(), body).get("captureSetId").asText();
        // (도구) header(이름, 값) = 요청에 머리말을 붙인다. Idempotency-Key = 같은 요청을 두 번 보내도 한 번만 처리되게 하는 열쇠
        // 실행: 일반 고객 토큰으로 그 세트 완료를 요청한다
        perform(post("/api/v1/captures/sets/" + setId + "/complete").with(customer())
                .header("Idempotency-Key", "key-00000001"), "{}")
                // 확인: 403 FORBIDDEN이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 실행: 일반 고객 토큰으로 그 세트를 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(customer()))
                // 확인: 403 FORBIDDEN이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 실행: 관리자 토큰으로 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(admin()), body)
                // 확인: 관리자는 조회만 하고 촬영은 못 하므로 403 FORBIDDEN이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // [상황] 토큰 없이 세트를 조회한다.
    // [기대] 401 UNAUTHENTICATED를 오류 형식(Problem Details)으로 돌려준다.
    //        ※ 응답 코드: 401 = 로그인 필요(토큰이 없거나 만료·위조됨)
    @Test
    @DisplayName("토큰이 없으면 401 UNAUTHENTICATED Problem Details")
    void unauthenticated() throws Exception {
        // 실행: 토큰 없이 세트를 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + CHECKPOINT))
                // (도구) status().isUnauthorized() = 응답 코드가 401(로그인 안 됨)인지 본다
                // 확인: 401이어야 한다
                .andExpect(status().isUnauthorized())
                // 확인: 오류 코드가 UNAUTHENTICATED여야 한다
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    // [상황] 다른 부스(booth-02)나 고객 촬영 토큰으로 부스 세트를 완료하려 한다. 폰 세트는 자기 시점·다른 시점 토큰·부스 토큰으로 조회한다.
    // [기대] 다른 부스·고객의 부스 세트 완료는 403 OUT_OF_SCOPE. 폰 세트는 자기 시점 토큰만 볼 수 있고,
    //        다른 시점은 403 OUT_OF_SCOPE, 부스는 403 FORBIDDEN이다. 없는 세트는 관리자에게도 404다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("다른 부스·다른 시점의 세트를 완료하면 403 OUT_OF_SCOPE, 촬영 JWT는 자기 시점 세트만 조회")
    void completeAndGetScope() throws Exception {
        // 준비: 기본 부스(booth-01)가 세트를 만들어 ID를 받아 둔다
        String boothSet = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 실행: 다른 부스(booth-02) 토큰으로 그 세트 완료를 요청한다
        complete(boothSet, booth("booth-02"), "key-00000001", "{}")
                // 확인: 남의 부스 세트라 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 고객 촬영 토큰으로 부스 세트 완료를 요청한다
        complete(boothSet, capture(), "key-00000001", "{}")
                // 확인: 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));

        // 준비: 고객 촬영 토큰(출고 시점)으로 폰 세트를 만들어 ID를 받아 둔다
        String phoneSet = created(capture(), phoneBody()).get("captureSetId").asText();
        // 실행+확인: 같은 시점 촬영 토큰으로 조회하면 200이어야 한다
        mvc.perform(get("/api/v1/captures/sets/" + phoneSet).with(capture())).andExpect(status().isOk());
        // 실행: 같은 렌탈이지만 다른 시점의 촬영 토큰으로 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + phoneSet).with(capture(OTHER_CHECKPOINT, RENTAL)))
                // 확인: 자기 시점 세트가 아니라 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 부스 토큰으로 폰 세트를 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + phoneSet).with(booth()))
                // 확인: 403 FORBIDDEN이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 실행: 관리자가 없는 세트 ID로 조회한다
        mvc.perform(get("/api/v1/captures/sets/AAAAAAAAAAAAAAAAAAAAA").with(admin()))
                // (도구) status().isNotFound() = 응답 코드가 404(찾을 수 없음)인지 본다
                // 확인: 404이고 오류 코드가 NOT_FOUND여야 한다
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── 이야기 2: 완료 ──

    // [상황] 부스 세트의 필수 면 사진을 다 올리고 완료한다.
    // [기대] 202와 "올림 완료"(UPLOADED), 사진은 올라감으로 보인다. "업로드 완료" 이벤트가 딱 하나 나가며,
    //        렌탈·시점·세트 ID, 사진 16장(저장 경로 포함), 부스는 이미 탐지를 했으므로 탐지 필요 없음(needDetection=false)이 실린다.
    //        건너뛴 면·기종 정보가 없으면 이벤트에서 그 칸을 빼고, 이벤트 ID는 21자다.
    //        ※ 응답 코드: 202 = 접수됨(처리는 이어서 진행)
    @Test
    @DisplayName("필수 면이 모두 올라간 부스 세트를 완료하면 202 UPLOADED, trace.capture.uploaded 하나(needDetection=false)")
    void completeBooth() throws Exception {
        // 준비: 부스 전체 검사 세트를 만들어 ID를 받는다
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 준비: 모든 사진이 올라간 것처럼 메모리 저장소에 넣는다
        uploadAll(setId);
        // 실행: 부스 토큰으로 완료를 요청한다(본문 없음)
        complete(setId, booth(), "key-00000001", null)
                // (도구) status().isAccepted() = 응답 코드가 202(받아들였고 뒤 처리는 따로 함)인지 본다
                // 확인: 202여야 한다
                .andExpect(status().isAccepted())
                // 확인: 상태가 UPLOADED(올림 완료)여야 한다
                .andExpect(jsonPath("$.status").value("UPLOADED"))
                // 확인: 부스 ID가 booth-01이어야 한다
                .andExpect(jsonPath("$.boothId").value(BOOTH))
                // 확인: 첫 사진이 올라간 상태(true)여야 한다
                .andExpect(jsonPath("$.photos[0].uploaded").value(true));

        // (도구) ArgumentCaptor = 가짜에게 넘어간 값을 붙잡아 두는 그물. 나중에 getValue()로 꺼내 내용을 검사한다
        // 준비: 발행된 "업로드 완료" 이벤트를 붙잡을 그물을 만든다
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        // (도구) verify(가짜, times(1)).메서드(...) = 가짜의 그 메서드가 정확히 1번 불렸는지 확인. event.capture() = 그때 넘어간 값을 그물에 담는다
        // 확인: 업로드 완료 이벤트가 딱 한 번 나갔어야 한다
        verify(events, times(1)).publishUploaded(event.capture());
        // 붙잡은 이벤트를 꺼낸다
        CaptureUploadedEvent e = event.getValue();
        // 확인: 이벤트 형식 버전이 1.0이어야 한다
        assertThat(e.schemaVersion()).isEqualTo("1.0");
        // 확인: 렌탈 ID가 맞아야 한다
        assertThat(e.rentalId()).isEqualTo(RENTAL);
        // 확인: 시점 ID가 맞아야 한다
        assertThat(e.checkpointId()).isEqualTo(CHECKPOINT);
        // 확인: 세트 ID가 맞아야 한다
        assertThat(e.captureSetId()).isEqualTo(setId);
        // (도구) isFalse() = 값이 false여야 통과
        // 확인: 부스는 Jetson에서 이미 탐지했으므로 서버 탐지 필요(needDetection)가 false여야 한다
        assertThat(e.needDetection()).isFalse();
        // 확인: 이벤트에 실린 사진이 8면 × 2 = 16장이어야 한다
        assertThat(e.photos()).hasSize(BOOTH_FACES.size() * 2);
        // 확인: 첫 사진의 파일 키가 "captures/세트ID/TOP-L1-CAM1.jpg" 규칙이어야 한다
        assertThat(e.photos().get(0).objectKey()).isEqualTo("captures/" + setId + "/TOP-L1-CAM1.jpg");
        // (도구) isEmpty() = 비어 있어야 통과
        // 확인: 건너뛴 면이 없어야 한다
        assertThat(e.skippedFaces()).isEmpty();
        // 이벤트를 실제로 보낼 JSON 모양으로 바꾼다
        JsonNode serialized = json.valueToTree(e);
        // 확인: 건너뛴 면이 없으면 JSON에 skippedFaces 칸 자체가 없어야 한다
        assertThat(serialized.has("skippedFaces")).isFalse();
        // 확인: 기종을 안 보냈으면 device 칸도 없어야 한다
        assertThat(serialized.has("device")).isFalse(); // 기종을 안 보내면 이벤트에서도 뺀다
        // 확인: 이벤트 ID가 21자 공개 ID 모양이어야 한다
        assertThat(serialized.get("eventId").asText()).matches("^[A-Za-z0-9_-]{21}$");
    }

    // [상황] 부스 세트를 완료하려는데 키보드(KB) 사진만 실제로 올라가지 않았다.
    // [기대] 409 CAPTURE_FACES_MISSING(빠진 면 KB)으로 완료되지 않고, 이벤트도 없으며, 세트는 "올리는 중" 그대로다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("세트 출처의 필수 면이 빠지면 세트가 완료되지 않는다(409 CAPTURE_FACES_MISSING, missingFaces)")
    void facesMissing() throws Exception {
        // 준비: 부스 전체 검사 세트를 만든다
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 준비: 일단 모든 사진을 올린 것처럼 한다
        uploadAll(setId);
        // 준비: 그 사진을 다 지운다(아래에서 키보드만 빼고 다시 올리려고)
        storage.clear();
        // 8면을 하나씩 보면서
        for (String face : BOOTH_FACES) {
            // 키보드(KB)가 아닌 면만
            if (!face.equals("KB")) {
                // CAM1 사진을 올린 것처럼 넣는다
                storage.put("captures/" + setId + "/" + face + "-L1-CAM1.jpg", SHA);
                // CAM2 사진을 올린 것처럼 넣는다
                storage.put("captures/" + setId + "/" + face + "-L1-CAM2.jpg", SHA);
            }
        }
        // 실행: 키보드 사진이 빠진 채로 완료를 요청한다
        complete(setId, booth(), "key-00000001", "{}")
                // (도구) status().isConflict() = 응답 코드가 409(지금 상태와 맞지 않음)인지 본다
                // 확인: 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 오류 코드가 CAPTURE_FACES_MISSING(필수 면 빠짐)이어야 한다
                .andExpect(jsonPath("$.code").value("CAPTURE_FACES_MISSING"))
                // 확인: 빠진 면 목록 첫 번째가 KB여야 한다 = 무엇을 다시 찍을지 알려 준다
                .andExpect(jsonPath("$.missingFaces[0]").value("KB"));
        // (도구) never() = 한 번도 불리지 않았어야 통과
        // 확인: 완료되지 않았으니 업로드 완료 이벤트가 나가지 않았어야 한다
        verify(events, never()).publishUploaded(any());
        // 실행: 관리자가 세트를 다시 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                // 확인: 상태가 여전히 UPLOADING(올리는 중)이어야 한다
                .andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    // [상황] 폰 세트를 만들 때 기종 코드와 치수(가로·깊이·본체·덮개 두께)를 함께 보낸다.
    // [기대] 완료 이벤트에 기종·치수가 그대로 실린다(뒤 단계 worker가 기기 크기를 쓸 수 있게).
    @Test
    @DisplayName("세트 생성에 기종·치수(device)를 보내면 완료 이벤트 trace.capture.uploaded에 그대로 실린다")
    void deviceForwarded() throws Exception {
        // 준비: 폰 세트 본문 끝에 기종·치수(DEVICE)를 덧붙인다
        String body = phoneBody().replaceFirst("\\}$", "," + DEVICE + "}");
        // 준비: 고객 촬영 토큰으로 세트를 만든다
        String setId = created(capture(), body).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 실행: 완료를 요청하고 202인지 본다
        complete(setId, capture(), "key-00000001", "{}").andExpect(status().isAccepted());
        // 발행된 이벤트를 붙잡을 그물을 만든다
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        // (도구) verify(가짜).메서드(...) = times를 안 쓰면 "정확히 1번 불렸는지"를 본다
        // 확인: 업로드 완료 이벤트가 나갔어야 하고, 그 값을 그물에 담는다
        verify(events).publishUploaded(event.capture());
        // 이벤트를 JSON으로 바꿔 device 칸을 꺼낸다
        JsonNode device = json.valueToTree(event.getValue()).get("device");
        // 확인: 기종 코드가 보낸 그대로(GRAM16-2024)여야 한다
        assertThat(device.get("modelCode").asText()).isEqualTo("GRAM16-2024");
        // 확인: 가로 길이가 357.7mm로 그대로 실려야 한다
        assertThat(device.get("dimensions").get("widthMm").asDouble()).isEqualTo(357.7);
        // 확인: 덮개 두께가 5.0mm로 그대로 실려야 한다
        assertThat(device.get("dimensions").get("lidHMm").asDouble()).isEqualTo(5.0);
    }

    // [상황] 치수가 0이거나 기종 코드가 소문자(형식 틀림)다.
    // [기대] 400 VALIDATION_FAILED다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("기종 치수가 0 이하이거나 기종 코드 형식이 틀리면 400 VALIDATION_FAILED")
    void deviceInvalid() throws Exception {
        // 준비: 가로 길이를 0으로 바꾼 기종 정보를 붙인 본문을 만든다
        String zero = phoneBody().replaceFirst("\\}$", "," + DEVICE.replace("357.7", "0") + "}");
        // 실행: 그 본문으로 세트를 만들어 달라고 요청한다
        perform(post("/api/v1/captures/sets").with(capture()), zero)
                // 확인: 치수가 0 이하라 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 준비: 기종 코드를 소문자(gram16)로 바꾼 본문을 만든다
        String lower = phoneBody().replaceFirst("\\}$", "," + DEVICE.replace("GRAM16-2024", "gram16") + "}");
        // 실행: 그 본문을 보낸다
        perform(post("/api/v1/captures/sets").with(capture()), lower)
                // 확인: 기종 코드 형식이 틀려 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    // [상황] 폰 세트(7면 + 흰 화면)를 다 올리고 완료한다.
    // [기대] 키보드 없이 202로 완료되고, 이벤트에 서버 탐지 필요(needDetection=true, 폰은 부스 탐지가 없음)와 올린 사진 8장이 실린다.
    //        ※ 응답 코드: 202 = 접수됨(처리는 이어서 진행)
    @Test
    @DisplayName("폰의 필수 면은 TOP·BOT·SF·SB·SL·SR·SCR0 7면이다: KB 없이 202, needDetection=true")
    void completePhone() throws Exception {
        // 준비: 고객 촬영 토큰으로 폰 세트(7면 + 흰 화면)를 만든다
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 실행+확인: 키보드 없이 완료해도 202여야 한다
        complete(setId, capture(), "key-00000001", "{}").andExpect(status().isAccepted());
        // 발행된 이벤트를 붙잡을 그물을 만든다
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        // 확인: 업로드 완료 이벤트가 나갔어야 한다
        verify(events).publishUploaded(event.capture());
        // (도구) isTrue() = 값이 true여야 통과
        // 확인: 폰 사진은 서버에서 탐지해야 하므로 needDetection이 true여야 한다
        assertThat(event.getValue().needDetection()).isTrue();
        // 확인: 실린 사진이 7면 + 흰 화면 1장 = 8장이어야 한다
        assertThat(event.getValue().photos()).hasSize(PHONE_FACES.size() + 1); // 올린 SCR1도 실린다
    }

    // [상황] 흰 화면(SCR1) 사진을 알렸지만 올리지 못했다. 이유 없이 완료하고, 다음엔 "화면이 켜지지 않음" 이유를 붙여 완료한다.
    // [기대] 이유가 없으면 400(skippedFaces.SCR1), 이유가 있으면 202이고 이벤트에 SCR1 사진 없이 건너뛴 면과 이유가 실린다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 202 = 접수됨(처리는 이어서 진행)
    @Test
    @DisplayName("SCR1을 올리지 못하고 skippedFaces에 사유를 남기면 202, 이벤트에 SCR1 사진 없이 skippedFaces가 실린다")
    void skipScr1() throws Exception {
        // 준비: 흰 화면(SCR1)까지 찍겠다고 알린 부스 세트를 만든다
        String setId = created(booth(), boothBody(List.of("SCR1"))).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 준비: 다 지운 뒤(아래에서 SCR1만 빼고 다시 올리려고)
        storage.clear();
        // 필수 8면을 하나씩
        for (String face : BOOTH_FACES) {
            // CAM1 사진을 올린 것처럼 넣는다
            storage.put("captures/" + setId + "/" + face + "-L1-CAM1.jpg", SHA);
            // CAM2 사진을 올린 것처럼 넣는다
            storage.put("captures/" + setId + "/" + face + "-L1-CAM2.jpg", SHA);
        }
        // 실행: SCR1을 올리지 않고 사유도 없이 완료를 요청한다
        complete(setId, booth(), "key-00000001", "{}")
                // 확인: 알린 사진이 빠졌는데 사유가 없어 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 틀린 칸이 skippedFaces.SCR1(이 면을 건너뛴 사유)이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("skippedFaces.SCR1"));
        // 실행: 새 열쇠로, SCR1을 건너뛴 사유("화면이 켜지지 않음")를 적어 다시 완료를 요청한다
        complete(setId, booth(), "key-00000002", "{\"skippedFaces\":{\"SCR1\":\"화면이 켜지지 않음\"}}")
                // 확인: 202여야 한다
                .andExpect(status().isAccepted());
        // 발행된 이벤트를 붙잡을 그물을 만든다
        ArgumentCaptor<CaptureUploadedEvent> event = ArgumentCaptor.forClass(CaptureUploadedEvent.class);
        // 확인: 업로드 완료 이벤트가 나갔어야 한다
        verify(events).publishUploaded(event.capture());
        // (도구) noneMatch(조건) = 조건에 맞는 것이 하나도 없어야 통과
        // 확인: 이벤트 사진 목록에 SCR1 사진이 없어야 한다
        assertThat(event.getValue().photos()).noneMatch(p -> p.face() == Face.SCR1);
        // (도구) containsEntry(열쇠, 값) = 지도에 그 열쇠·값 짝이 있어야 통과
        // 확인: 건너뛴 면에 SCR1과 그 사유가 실려야 한다
        assertThat(event.getValue().skippedFaces()).containsEntry(Face.SCR1, "화면이 켜지지 않음");
    }

    // [상황] 부스 세트를 완료하면서 필수 면인 키보드를 "못 찍음"으로 건너뛰려 한다.
    // [기대] 400 VALIDATION_FAILED, 틀린 칸은 skippedFaces.KB다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("skippedFaces에 필수 면(부스 KB)을 넣으면 400 VALIDATION_FAILED, field skippedFaces.KB")
    void skipRequired() throws Exception {
        // 준비: 부스 전체 검사 세트를 만든다
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 실행: 필수 면인 키보드(KB)를 건너뛰었다고 적어 완료를 요청한다
        complete(setId, booth(), "key-00000001", "{\"skippedFaces\":{\"KB\":\"못 찍음\"}}")
                // 확인: 필수 면은 건너뛸 수 없어 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // 확인: 틀린 칸이 skippedFaces.KB여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("skippedFaces.KB"));
    }

    // [상황] 폰 세트의 윗면 사진이 알린 해시와 다른 파일로 올라갔다.
    // [기대] 409 CAPTURE_HASH_MISMATCH로 완료를 거절한다.
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("올라간 객체의 SHA-256이 알린 값과 다르면 409 CAPTURE_HASH_MISMATCH")
    void hashMismatch() throws Exception {
        // 준비: 폰 세트를 만든다
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 준비: 윗면 사진만 다른 해시로 덮어쓴다 = 알린 사진과 다른 파일이 올라간 상황
        storage.put("captures/" + setId + "/TOP-ROOM-PHONE.jpg", OTHER_SHA);
        // 실행: 완료를 요청한다
        complete(setId, capture(), "key-00000001", "{}")
                // 확인: 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 오류 코드가 CAPTURE_HASH_MISMATCH(해시 다름)여야 한다 = 바꿔치기를 막는다
                .andExpect(jsonPath("$.code").value("CAPTURE_HASH_MISMATCH"));
    }

    // [상황] 완료 요청을 Idempotency-Key 없이/너무 짧게 보내고, 같은 키·같은 본문으로 두 번, 같은 키·다른 본문, 다른 키로 보낸다.
    // [기대] 키가 없거나 짧으면 400. 같은 키·같은 본문은 처음 응답을 그대로 돌려주고 이벤트를 다시 내지 않는다(네트워크 재시도 안전).
    //        같은 키·다른 본문은 422 IDEMPOTENCY_KEY_CONFLICT, 다른 키는 이미 완료라 409 CAPTURE_SET_CLOSED다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음, 422 = 형식은 맞지만 내용이 규칙에 어긋남,
    //          409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("같은 Idempotency-Key·같은 본문은 처음 응답을 그대로, 다른 본문은 422, 다른 키는 409 CAPTURE_SET_CLOSED, 키가 없으면 400")
    void idempotency() throws Exception {
        // 준비: 폰 세트를 만든다
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 실행+확인: 너무 짧은 열쇠("short")로 완료하면 400이어야 한다
        complete(setId, capture(), "short", "{}").andExpect(status().isBadRequest());
        // 실행: Idempotency-Key 머리말 없이 완료를 요청한다
        perform(post("/api/v1/captures/sets/" + setId + "/complete").with(capture()), "{}")
                // 확인: 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 틀린 칸이 Idempotency-Key여야 한다
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));

        // (도구) andReturn().getResponse().getContentAsString() = 검사를 마친 응답의 본문을 글자 그대로 꺼낸다
        // 실행: 올바른 열쇠와 본문(SCR1 건너뜀)으로 처음 완료하고, 202 확인 뒤 응답 본문을 저장해 둔다
        String first = complete(setId, capture(), "key-00000001", "{\"skippedFaces\":{\"SCR1\":\"없음\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        // (도구) clearInvocations(가짜) = 가짜가 지금까지 불린 기록을 지운다. 이 뒤에 이벤트가 또 나가는지 따로 세려고
        clearInvocations(events);
        // 실행: 같은 열쇠·같은 본문으로 완료를 한 번 더 보내고(네트워크가 끊겨 다시 보낸 상황), 202 확인 뒤 응답 본문을 저장한다
        String again = complete(setId, capture(), "key-00000001", "{\"skippedFaces\":{\"SCR1\":\"없음\"}}")
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        // 확인: 두 번째 응답이 처음 응답과 똑같아야 한다 = 처음 결과를 그대로 돌려준다
        assertThat(json.readTree(again)).isEqualTo(json.readTree(first));
        // 확인: 다시 보낸 요청으로 이벤트가 또 나가지 않았어야 한다(중복 처리 방지)
        verify(events, never()).publishUploaded(any());

        // 실행: 같은 열쇠인데 본문을 다르게 보낸다
        complete(setId, capture(), "key-00000001", "{}")
                // (도구) status().isUnprocessableEntity() = 응답 코드가 422(형식은 맞지만 처리할 수 없음)인지 본다
                // 확인: 422여야 한다
                .andExpect(status().isUnprocessableEntity())
                // 확인: 오류 코드가 IDEMPOTENCY_KEY_CONFLICT(같은 열쇠에 다른 요청)여야 한다
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        // 실행: 다른 열쇠로 이미 완료된 세트를 또 완료하려 한다
        complete(setId, capture(), "key-00000002", "{}")
                // 확인: 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 오류 코드가 CAPTURE_SET_CLOSED(이미 닫힌 세트)여야 한다
                .andExpect(jsonPath("$.code").value("CAPTURE_SET_CLOSED"));
    }

    // [상황] 폰 세트를 만들고 윗면 사진만 올린 상태에서 조회한다.
    // [기대] 시점 ID가 보이고 부스 ID는 비어 있으며, 윗면은 올라감, 나머지는 아직 안 올라감으로 보인다(진행률 표시용).
    @Test
    @DisplayName("UPLOADING 세트 조회는 저장소에 올라간 사진을 uploaded=true로 보여 준다")
    void getShowsUploads() throws Exception {
        // 준비: 폰 세트를 만든다
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        // 준비: 윗면 사진 한 장만 올린 것처럼 넣는다
        storage.put("captures/" + setId + "/TOP-ROOM-PHONE.jpg", SHA);
        // 실행: 고객 촬영 토큰으로 세트를 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(capture()))
                // 확인: 200이어야 한다
                .andExpect(status().isOk())
                // 확인: 시점 ID가 맞아야 한다
                .andExpect(jsonPath("$.checkpointId").value(CHECKPOINT))
                // (도구) isEmpty() = 그 칸이 비어 있어야(null·빈 값) 통과
                // 확인: 폰 세트라 부스 ID가 비어 있어야 한다
                .andExpect(jsonPath("$.boothId").isEmpty())
                // 확인: 첫 사진이 윗면이어야 한다
                .andExpect(jsonPath("$.photos[0].face").value("TOP"))
                // 확인: 저장소에 올라간 윗면은 uploaded=true여야 한다
                .andExpect(jsonPath("$.photos[0].uploaded").value(true))
                // 확인: 아직 안 올린 두 번째 사진은 uploaded=false여야 한다
                .andExpect(jsonPath("$.photos[1].uploaded").value(false));
    }

    // ── 업로드 URL 다시 받기 (contracts reissueUploadUrls, 11/2 결정) ──

    // [상황] 업로드 주소가 만료돼 세트를 새로 만들지 않고 주소를 다시 받는다(본문 없이, 그리고 빈 본문 {}로).
    // [기대] 남은 사진 전부의 새 주소를 세트 만들 때와 같은 순서·형식으로 받는다. 면·조명·카메라·헤더는 같고,
    //        주소는 새것이며 만료는 약 10분 뒤다. 세트는 여전히 "올리는 중"이다.
    @Test
    @DisplayName("URL이 만료되면 세트를 새로 만들지 않고 남은 사진 전부의 새 10분 PUT URL을 세트 생성과 같은 형식으로 받는다")
    void reissueAfterExpiry() throws Exception {
        // 준비: 흰 화면까지 포함한 부스 세트를 만들고 응답을 받아 둔다
        JsonNode first = created(booth(), boothBody(List.of("SCR1")));
        // 응답에서 세트 ID를 꺼낸다
        String setId = first.get("captureSetId").asText();
        // 처음 받은 업로드 주소 목록을 꺼내 둔다(새로 받은 것과 비교하려고)
        JsonNode original = first.get("uploads");

        // (도구) transform(this::tree) = 꺼낸 글자에 바로 tree 도우미를 적용해 JSON으로 바꾼다
        // 실행: 본문 없이 업로드 주소를 다시 달라고 요청하고, 200 확인 뒤 새 주소 목록을 꺼낸다
        JsonNode uploads = reissue(setId, booth(), null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString().transform(this::tree).get("uploads");
        // 확인: 새 주소 수가 처음과 같아야 한다 = 아직 안 올린 사진 전부
        assertThat(uploads).hasSize(original.size());
        // 주소를 하나씩 처음 것과 짝지어 비교한다
        for (int i = 0; i < uploads.size(); i++) {
            // 새로 받은 i번째 주소
            JsonNode u = uploads.get(i);
            // 처음 받은 i번째 주소
            JsonNode o = original.get(i);
            // 확인: 면이 같아야 한다 = 순서가 그대로
            assertThat(u.get("face")).isEqualTo(o.get("face"));
            // 확인: 조명이 같아야 한다
            assertThat(u.get("light")).isEqualTo(o.get("light"));
            // 확인: 카메라가 같아야 한다
            assertThat(u.get("camera")).isEqualTo(o.get("camera"));
            // 확인: 업로드 방식이 PUT이어야 한다
            assertThat(u.get("method").asText()).isEqualTo("PUT");
            // 확인: 함께 보낼 머리말(파일 종류·체크섬)이 처음과 같아야 한다
            assertThat(u.get("headers")).isEqualTo(o.get("headers"));
            // 확인: 주소는 새로 발급돼 처음과 달라야 한다
            assertThat(u.get("url").asText()).isNotEqualTo(o.get("url").asText());
            // 새 만료 시각을 시각 값으로 바꿔 아래 두 가지를 확인한다
            assertThat(java.time.OffsetDateTime.parse(u.get("expiresAt").asText()))
                    // (도구) isAfterOrEqualTo(시각) = 그 시각과 같거나 뒤여야 통과
                    // 확인: 새 만료 시각이 처음 만료 시각보다 앞서면 안 된다
                    .isAfterOrEqualTo(java.time.OffsetDateTime.parse(o.get("expiresAt").asText()))
                    // (도구) isBefore(시각) = 그 시각보다 앞이어야 통과
                    // 확인: 지금부터 11분 안이어야 한다 = 유효 시간이 10분
                    .isBefore(java.time.OffsetDateTime.now().plusMinutes(11));
        }
        // 빈 본문 {}도 남은 사진 전부
        // 실행: 빈 JSON 본문({})으로 다시 요청하고 200인지 본다
        reissue(setId, booth(), "{}").andExpect(status().isOk())
                // (도구) length() = jsonPath에서 목록의 개수를 센다
                // 확인: 빈 본문도 "남은 사진 전부"로 보고 처음과 같은 개수를 줘야 한다
                .andExpect(jsonPath("$.uploads.length()").value(original.size()));
        // 실행: 관리자가 세트를 조회한다
        mvc.perform(get("/api/v1/captures/sets/" + setId).with(admin()))
                // 확인: 주소를 다시 받아도 세트는 새로 생기지 않고 상태가 그대로 UPLOADING이어야 한다
                .andExpect(jsonPath("$.status").value("UPLOADING"));
    }

    // [상황] 폰 세트에서 윗면만 올린 뒤 주소를 다시 받고, 특정 사진 세 장(SCR1·TOP·BOT)만 골라 다시 받고, 다 올린 뒤 또 받는다.
    // [기대] 이미 올린 윗면은 빼고 7장만 준다. 골라 받으면 그중 안 올린 BOT·SCR1만 세트 순서대로 준다. 다 올렸으면 빈 목록이다.
    @Test
    @DisplayName("이미 올라간 사진은 다시 주지 않고, photos를 주면 그 사진만(세트 순서), 모두 올라갔으면 빈 배열")
    void reissueSkipsUploaded() throws Exception {
        // 준비: 폰 세트(7면 + 흰 화면 = 8장)를 만든다
        String setId = created(capture(), phoneBody()).get("captureSetId").asText();
        // 준비: 윗면 사진만 올린 것처럼 넣는다
        storage.put("captures/" + setId + "/TOP-ROOM-PHONE.jpg", SHA);

        // 실행: 본문 없이 주소를 다시 달라고 요청하고, 200 확인 뒤 주소 목록을 꺼낸다
        JsonNode all = tree(reissue(setId, capture(), null).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString()).get("uploads");
        // 확인: 8장 중 윗면을 뺀 7장만 와야 하고
        assertThat(all).hasSize(PHONE_FACES.size()) // 8장 중 TOP 제외
                // 확인: 그 안에 이미 올린 윗면(TOP)은 없어야 한다
                .noneMatch(u -> u.get("face").asText().equals("TOP"));

        // 준비: 흰 화면·윗면·바닥 세 장을 골라 달라는 본문을 만든다(일부러 세트 순서와 다르게)
        String picked = "{\"photos\":[" + ref("SCR1", "OFF", "PHONE") + "," + ref("TOP", "ROOM", "PHONE") + ","
                + ref("BOT", "ROOM", "PHONE") + "]}";
        // 실행: 고른 사진만 주소를 다시 달라고 요청하고 200인지 본다
        reissue(setId, capture(), picked).andExpect(status().isOk())
                // 확인: 이미 올린 윗면은 빼고 2장만 와야 한다
                .andExpect(jsonPath("$.uploads.length()").value(2))
                // 확인: 첫 번째가 바닥이어야 한다 = 요청 순서가 아니라 세트 순서
                .andExpect(jsonPath("$.uploads[0].face").value("BOT"))
                // 확인: 두 번째가 흰 화면이어야 한다
                .andExpect(jsonPath("$.uploads[1].face").value("SCR1"))
                // 확인: 흰 화면의 조명 값이 OFF여야 한다
                .andExpect(jsonPath("$.uploads[1].light").value("OFF"));

        // 준비: 이제 모든 사진을 올린 것처럼 한다
        uploadAll(setId);
        // 실행: 다시 주소를 달라고 요청하고 200인지 본다
        reissue(setId, capture(), null).andExpect(status().isOk())
                // (도구) isArray() = 그 칸이 목록(배열)이어야 통과
                // 확인: uploads가 목록이어야 하고
                .andExpect(jsonPath("$.uploads").isArray())
                // 확인: 모두 올라갔으니 빈 목록(0개)이어야 한다
                .andExpect(jsonPath("$.uploads.length()").value(0));
    }

    // [상황] 세트에 없는 사진(윗면 L2)을 고르거나, 빈 목록, 카메라가 빠진 항목, 모르는 칸(sha256)이 든 항목으로 다시 받으려 한다.
    // [기대] 모두 400이고, 없는 사진은 몇 번째 항목인지(photos[1]) 알려 준다.
    //        ※ 응답 코드: 400 = 요청 내용이 형식에 맞지 않음
    @Test
    @DisplayName("세트에 없는 (face, light, camera)나 형식 오류는 400 VALIDATION_FAILED(photos[i])")
    void reissueUnknownPhoto() throws Exception {
        // 준비: 부스 전체 검사 세트(조명 L1만)를 만든다
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 실행: 세트에 있는 (TOP, L1, CAM1)과 세트에 없는 (TOP, L2, CAM1)의 주소를 달라고 요청한다
        reissue(setId, booth(), "{\"photos\":[" + ref("TOP", "L1", "CAM1") + "," + ref("TOP", "L2", "CAM1") + "]}")
                // 확인: 400이어야 한다
                .andExpect(status().isBadRequest())
                // 확인: 오류 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                // 확인: 틀린 칸이 세트에 없는 두 번째 사진(photos[1])이어야 한다
                .andExpect(jsonPath("$.errors[0].field").value("photos[1]"));
        // 실행+확인: 빈 사진 목록을 보내면 400이어야 하고
        reissue(setId, booth(), "{\"photos\":[]}").andExpect(status().isBadRequest())
                // 확인: 오류 코드가 VALIDATION_FAILED여야 한다
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        // 실행: 카메라 칸이 빠진 사진을 보낸다
        reissue(setId, booth(), "{\"photos\":[{\"face\":\"TOP\",\"light\":\"L1\"}]}")
                // 확인: 400이어야 한다
                .andExpect(status().isBadRequest());
        // 실행+확인: 다시 받기에는 필요 없는 sha256 칸을 끼워 보내면(모르는 칸) 400이어야 한다
        reissue(setId, booth(), "{\"photos\":[" + ref("TOP", "L1", "CAM1").replace("}", ",\"sha256\":\"x\"}")
                + "]}").andExpect(status().isBadRequest());
    }

    // [상황] 이미 완료된 세트의 업로드 주소를 다시 받으려 한다.
    // [기대] 409 CAPTURE_SET_CLOSED다(완료 뒤 사진 바꿔 올리기 방지).
    //        ※ 응답 코드: 409 = 지금 상태와 맞지 않아 거절(이미 있음·순서 어긋남 등)
    @Test
    @DisplayName("완료된 세트의 URL을 다시 받으면 409 CAPTURE_SET_CLOSED")
    void reissueClosed() throws Exception {
        // 준비: 부스 세트를 만든다
        String setId = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 준비: 사진을 모두 올린 것처럼 한다
        uploadAll(setId);
        // 준비: 완료까지 해서 세트를 닫는다(202 확인)
        complete(setId, booth(), "key-00000001", "{}").andExpect(status().isAccepted());
        // 실행: 닫힌 세트의 주소를 다시 달라고 요청한다
        reissue(setId, booth(), null)
                // 확인: 409여야 한다
                .andExpect(status().isConflict())
                // 확인: 오류 코드가 CAPTURE_SET_CLOSED여야 한다
                .andExpect(jsonPath("$.code").value("CAPTURE_SET_CLOSED"));
    }

    // [상황] 다른 부스, 고객 촬영 토큰, 다른 시점 토큰, 부스(폰 세트에), 일반 고객, 관리자, 없는 세트로 주소를 다시 받으려 한다.
    // [기대] 다른 부스·다른 시점·남의 출처는 403 OUT_OF_SCOPE, 일반 고객·관리자는 403 FORBIDDEN, 없는 세트는 404다.
    //        ※ 응답 코드: 403 = 권한 없음(누구인지는 알지만 허락되지 않은 일), 404 = 찾을 수 없음(없는 주소나 데이터)
    @Test
    @DisplayName("다른 부스·다른 시점은 403 OUT_OF_SCOPE, 일반 고객·관리자는 403 FORBIDDEN, 없는 세트는 404")
    void reissueScope() throws Exception {
        // 준비: 기본 부스(booth-01)가 세트를 만든다
        String boothSet = created(booth(), boothBody(List.of())).get("captureSetId").asText();
        // 실행: 다른 부스 토큰으로 주소를 다시 달라고 요청한다
        reissue(boothSet, booth("booth-02"), null)
                // 확인: 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 고객 촬영 토큰으로 부스 세트 주소를 요청한다
        reissue(boothSet, capture(), null)
                // 확인: 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));

        // 준비: 고객 촬영 토큰(출고 시점)으로 폰 세트를 만든다
        String phoneSet = created(capture(), phoneBody()).get("captureSetId").asText();
        // 실행: 다른 시점 촬영 토큰으로 요청한다
        reissue(phoneSet, capture(OTHER_CHECKPOINT, RENTAL), null)
                // 확인: 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 부스 토큰으로 폰 세트 주소를 요청한다
        reissue(phoneSet, booth(), null)
                // 확인: 403 OUT_OF_SCOPE여야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("OUT_OF_SCOPE"));
        // 실행: 일반 고객 토큰으로 요청한다
        reissue(phoneSet, customer(), null)
                // 확인: 403 FORBIDDEN이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 실행: 관리자 토큰으로 요청한다
        reissue(phoneSet, admin(), null)
                // 확인: 관리자는 촬영하지 않으므로 403 FORBIDDEN이어야 한다
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        // 실행: 없는 세트 ID로 요청한다
        reissue("AAAAAAAAAAAAAAAAAAAAA", booth(), null)
                // 확인: 404 NOT_FOUND여야 한다
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    // ── 도우미 ──

    // 테스트 도구: 업로드 주소 다시 받기 요청을 보낸다(본문은 있을 수도 없을 수도 있음).
    private ResultActions reissue(String setId, RequestPostProcessor token, String body) throws Exception {
        // (도구) MockHttpServletRequestBuilder = 보낼 요청(주소·토큰·머리말)을 차곡차곡 조립하는 도구
        // 업로드 주소 다시 받기 POST 요청에 토큰을 붙여 조립한다
        MockHttpServletRequestBuilder request = post("/api/v1/captures/sets/" + setId + "/uploads").with(token);
        // 본문이 없으면 그대로, 있으면 JSON 본문을 담아 보낸다
        return body == null ? mvc.perform(request) : perform(request, body);
    }

    // 테스트 도구: 응답 문자열을 JSON으로 읽는다.
    private JsonNode tree(String body) {
        try {
            // 글자를 JSON 나무 구조로 읽어 돌려준다
            return json.readTree(body);
        } catch (java.io.IOException e) {
            // 읽지 못하면 시험을 바로 실패시키려고 예외로 바꿔 던진다
            throw new IllegalStateException(e);
        }
    }

    // 테스트 도구: 다시 받을 사진 하나(면·조명·카메라) JSON.
    private static String ref(String face, String light, String camera) {
        // 면·조명·카메라 칸만 있는 JSON 글자를 돌려준다
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\"}";
    }

    // 테스트 도구: 세트를 만들고 201인지 확인한 뒤 응답 JSON을 돌려준다.
    // ※ 응답 코드: 201 = 새로 만들어짐
    private JsonNode created(RequestPostProcessor token, String body)
            throws Exception {
        // 그 토큰으로 세트 만들기를 요청한다
        String response = perform(post("/api/v1/captures/sets").with(token), body)
                // (도구) status().isCreated() = 응답 코드가 201(새로 만들어짐)인지 본다
                // 201이어야 한다
                .andExpect(status().isCreated())
                // 응답 본문을 글자로 꺼낸다
                .andReturn().getResponse().getContentAsString();
        // JSON으로 바꿔 돌려준다
        return json.readTree(response);
    }

    // 테스트 도구: 그 키로 완료 요청을 보낸다(본문은 있을 수도 없을 수도 있음).
    private ResultActions complete(String setId,
            RequestPostProcessor token, String key, String body)
            throws Exception {
        // 완료 POST 요청에 토큰과 Idempotency-Key 머리말을 붙여 조립한다
        MockHttpServletRequestBuilder request = post("/api/v1/captures/sets/" + setId + "/complete").with(token)
                .header("Idempotency-Key", key);
        // 본문이 없으면 그대로, 있으면 JSON 본문을 담아 보낸다
        return body == null ? mvc.perform(request) : perform(request, body);
    }

    // 테스트 도구: JSON 본문을 붙여 요청을 보낸다.
    private ResultActions perform(MockHttpServletRequestBuilder request, String body) throws Exception {
        // (도구) contentType(JSON) = "본문은 JSON"이라고 알리는 머리말, content(본문) = 보낼 내용
        // 본문 형식을 JSON으로 알리고 본문을 담아 요청을 보낸다
        return mvc.perform(request.contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 세트의 모든 사진을 알린 해시로 "올린다". */
    // 테스트 도구: 세트의 모든 사진을 알린 해시 그대로 "올린" 것으로 메모리 저장소에 넣는다.
    private void uploadAll(String setId) {
        // 메모리 지도에서 세트를 꺼내, 사진마다 그 파일 키·알린 해시로 저장소에 넣는다 = 모두 올린 상황
        saved.get(setId).getPhotos().forEach(p -> storage.put(p.getObjectKey(), p.getSha256()));
    }

    // 테스트 도구: 부스 8면 × CAM1·CAM2(L1) 세트 요청 본문. optional에 적은 선택 면(예: SCR1)을 CAM1 한 장씩 더한다.
    private static String boothBody(List<String> optional) {
        // 사진 목록을 담을 빈 목록을 만든다
        List<String> photos = new ArrayList<>();
        // 필수 8면을 하나씩
        for (String face : BOOTH_FACES) {
            // 조명 L1·CAM1 사진을 넣는다
            photos.add(photo(face, "L1", "CAM1"));
            // 조명 L1·CAM2 사진을 넣는다
            photos.add(photo(face, "L1", "CAM2"));
        }
        // 선택 면(흰 화면 등)을 하나씩
        for (String face : optional) {
            // 조명 끔(OFF)·CAM1 사진을 넣는다
            photos.add(photo(face, "OFF", "CAM1"));
        }
        // 기본 렌탈·출고 시점의 부스 세트 본문으로 만들어 돌려준다
        return setBody(RENTAL, CHECKPOINT, "BOOTH", photos);
    }

    // 테스트 도구: 시험용 기종 정보(GRAM16-2024, 치수 mm).
    private static final String DEVICE = "\"device\":{\"modelCode\":\"GRAM16-2024\",\"dimensions\":"
            + "{\"widthMm\":357.7,\"depthMm\":250.0,\"baseHMm\":11.0,\"lidHMm\":5.0}}";

    // 테스트 도구: 폰 7면 + 흰 화면(SCR1) 세트 요청 본문.
    private static String phoneBody() {
        // 필수 7면을 실내 조명·폰 카메라 사진으로 바꿔 목록에 담는다
        List<String> photos = new ArrayList<>(PHONE_FACES.stream().map(f -> photo(f, "ROOM", "PHONE")).toList());
        // 흰 화면 사진(조명 끔)을 더한다
        photos.add(photo("SCR1", "OFF", "PHONE"));
        // 기본 렌탈·출고 시점의 폰 세트 본문으로 만들어 돌려준다
        return setBody(RENTAL, CHECKPOINT, "PHONE", photos);
    }

    // 테스트 도구: 세트 만들기 요청 본문(렌탈·시점·출처·사진 목록).
    private static String setBody(String rentalId, String checkpointId, String source, List<String> photos) {
        // 네 칸을 이어 붙여 JSON 글자로 돌려준다
        return "{\"rentalId\":\"" + rentalId + "\",\"checkpointId\":\"" + checkpointId + "\",\"source\":\"" + source
                + "\",\"photos\":[" + String.join(",", photos) + "]}";
    }

    // 테스트 도구: 알릴 사진 한 장(면·조명·카메라·해시·형식·촬영 시각) JSON.
    private static String photo(String face, String light, String camera) {
        // 한국 시간(+09:00) 촬영 시각과 공통 해시(SHA)를 넣은 JSON 글자를 돌려준다
        return "{\"face\":\"" + face + "\",\"light\":\"" + light + "\",\"camera\":\"" + camera + "\",\"sha256\":\""
                + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}";
    }
}
