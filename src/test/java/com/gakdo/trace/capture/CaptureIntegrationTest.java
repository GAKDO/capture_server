// 이 파일이 확인하는 것: 촬영 업로드 전체 흐름을 실제 PostgreSQL·RabbitMQ·S3 호환 저장소로 끝까지 돌려 보는 통합 시험.
// 세트 만들기 → 사전 서명 URL로 사진 직접 올리기 → 끊기면 남은 사진 URL만 다시 받기 → 완료 → "업로드 완료" 이벤트 → 해시 확인.
// Docker가 필요해서 Claude 세션에서는 건너뛰고 GitHub Actions에서 돈다.

package com.gakdo.trace.capture;

import static com.gakdo.trace.capture.support.TestJwts.CHECKPOINT;
import static com.gakdo.trace.capture.support.TestJwts.RENTAL;
import static com.gakdo.trace.capture.support.TestJwts.capture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gakdo.trace.capture.captureset.application.PhotoStorage;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 실제 PostgreSQL(Flyway V1)·RabbitMQ·S3 호환 저장소로 생성 → 사전 서명 PUT → 완료 → 이벤트 → 내려받은 해시를 확인한다.
 * Docker가 없으면 건너뛴다. S3_TEST_ENDPOINT를 주면 컨테이너 대신 그 S3 호환 주소(예: 실제 MinIO)를 쓴다.
 */
// (도구) @SpringBootTest = 서버 전체(컨트롤러·서비스·DB 연결·메시지 큐)를 진짜로 띄우는 통합 테스트
// (도구) @AutoConfigureMockMvc = 그 서버에 요청을 보낼 MockMvc를 준비. @Testcontainers = 도커로 진짜 DB·RabbitMQ를 띄움(도커 없으면 건너뜀)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CaptureIntegrationTest {

    // 사진을 담는 저장소 버킷 이름
    private static final String BUCKET = "trace-captures";
    // 환경 변수 S3_TEST_ENDPOINT (있으면 컨테이너 대신 그 저장소를 쓴다)
    private static final String EXTERNAL_S3 = System.getenv("S3_TEST_ENDPOINT");
    // 폰 촬영 필수 7면
    private static final List<String> PHONE_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "SCR0");

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

    /**
     * S3 호환 저장소. MinIO는 Docker Hub 공개 이미지 배포를 멈춰(pull access denied) CI에서 받을 수 없어,
     * mirror.gcr.io의 moto 서버를 쓴다. moto는 x-amz-checksum-sha256 내용 검사를 하지 않으므로 "다른 파일 거절"은
     * S3_TEST_VERIFIES_CHECKSUM=true(실제 MinIO 주소)일 때만 확인한다.
     */
    // 준비: S3 호환 저장소(moto) 컨테이너. 포트 5000을 열고 "/"가 200을 줄 때까지 기다린다 (필요할 때만 직접 켠다)
    static final GenericContainer<?> S3 = new GenericContainer<>("mirror.gcr.io/motoserver/moto:5.1.16")
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));
    // 환경 변수 S3_TEST_VERIFIES_CHECKSUM이 true면 체크섬 검사 시험도 한다
    private static final boolean VERIFIES_CHECKSUM = "true".equals(System.getenv("S3_TEST_VERIFIES_CHECKSUM"));

    // 테스트 도구: S3 호환 저장소 주소와 버킷을 서버 설정에 넣는다(S3_TEST_ENDPOINT가 있으면 그 주소, 없으면 moto 컨테이너).
    // (도구) @DynamicPropertySource = 서버가 뜨기 전에 설정값을 코드로 넣는 곳 (컨테이너 주소처럼 실행 때 정해지는 값)
    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        // 외부 저장소 주소가 없으면
        if (EXTERNAL_S3 == null) {
            // moto 컨테이너를 켠다
            S3.start();
        }
        // 저장소 주소를 외부 주소 또는 moto 컨테이너 주소로 넣는다
        registry.add("app.storage.endpoint", () -> EXTERNAL_S3 != null ? EXTERNAL_S3
                : "http://" + S3.getHost() + ":" + S3.getMappedPort(5000));
        // 저장소 접근 키 (환경 변수가 없으면 시험용 "test")
        registry.add("app.storage.access-key", () -> System.getenv().getOrDefault("S3_TEST_ACCESS_KEY", "test"));
        // 저장소 비밀 키 (환경 변수가 없으면 시험용 "test")
        registry.add("app.storage.secret-key", () -> System.getenv().getOrDefault("S3_TEST_SECRET_KEY", "test"));
        // 사진 버킷 이름을 넣는다
        registry.add("app.storage.capture-bucket", () -> BUCKET);
    }

    // (도구) @Autowired = 스프링이 만든 객체를 넣어 준다. MockMvc = 서버에 HTTP 요청을 흉내 내 보내는 도구
    @Autowired
    private MockMvc mvc;

    // JSON 글자를 읽고 쓰는 도구
    @Autowired
    private ObjectMapper json;

    // 저장소에 직접 접근하는 S3 클라이언트 (버킷 만들기·파일 내려받기용)
    @Autowired
    private S3Client s3;

    // 서버의 사진 저장소 (해시 계산 시험용)
    @Autowired
    private PhotoStorage storage;

    // RabbitMQ에서 메시지를 꺼내는 도구
    @Autowired
    private RabbitTemplate rabbit;

    // RabbitMQ에 큐를 만드는 관리 도구
    @Autowired
    private RabbitAdmin admin;

    // 사전 서명 URL로 사진을 직접 올릴 HTTP 클라이언트 (서버를 거치지 않으므로 MockMvc가 아님)
    private final HttpClient http = HttpClient.newHttpClient();

    // [상황] 폰으로 7면 사진을 찍은 고객이 세트를 만들고, 사진 3장만 올린 뒤 연결이 끊겼다가 나머지 URL을 다시 받아 올리고 완료한다.
    // [기대] 다시 받은 URL은 남은 4장 것만 온다. 완료하면 "올림 완료"(UPLOADED)가 되고 업로드 완료 이벤트가 나간다
    //        (메시지 ID = 이벤트 ID, 렌탈·세트 ID, 탐지 필요 표시, 사진 7장).
    //        저장소에서 내려받은 파일의 SHA-256 = DB에 저장된 해시 = 이벤트의 해시다(증거 사진이 바뀌지 않았음).
    //        체크섬을 검사하는 실제 MinIO에서는 알린 해시와 다른 파일 업로드가 거절되는 것도 확인한다.
    // (도구) @Test = JUnit에게 "이 메서드가 테스트 하나"라고 알리는 표시. @DisplayName = 결과 화면에 보일 한글 이름
    @Test
    @DisplayName("사진은 사전 서명 URL로 직접 올라가고, 끊기면 남은 사진의 URL만 다시 받으며, 완료하면 이벤트가 나가며, 내려받은 파일 해시와 저장된 해시가 같다")
    void endToEnd() throws Exception {
        // 준비: 사진 버킷이 없으면
        if (!s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(BUCKET))) {
            // 버킷을 만든다
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        // 준비: 시험용 임시 큐를 만든다 (서버 끝나면 자동 삭제)
        Queue queue = new Queue("test.capture.uploaded", false, false, true);
        // RabbitMQ에 그 큐를 등록한다
        admin.declareQueue(queue);
        // trace.events로 나가는 "업로드 완료"(trace.capture.uploaded) 메시지가 이 큐로 오게 연결한다
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("trace.events")).with("trace.capture.uploaded"));

        // 준비: 면 → 사진 파일 내용
        Map<String, byte[]> files = new LinkedHashMap<>();
        // 준비: 요청에 넣을 사진 항목(JSON 글자) 목록
        List<String> photos = new ArrayList<>();
        // 난수를 시드 7로 고정한다 (매번 같은 가짜 사진이 나오게)
        Random random = new Random(7);
        // 폰 필수 7면마다
        for (String face : PHONE_FACES) {
            // 2~6KB 크기 빈 파일을 만들고
            byte[] bytes = new byte[2048 + random.nextInt(4096)];
            // 아무 값으로 채워 가짜 사진으로 쓴다
            random.nextBytes(bytes);
            // 면 이름으로 저장한다
            files.put(face, bytes);
            // 면·실내 조명·폰 카메라·SHA-256·jpg·촬영 시각을 담은 사진 항목을 만든다
            photos.add("{\"face\":\"" + face + "\",\"light\":\"ROOM\",\"camera\":\"PHONE\",\"sha256\":\"" + sha256(bytes)
                    + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}");
        }
        // 준비: 렌탈·시점·출처(PHONE)·사진 7장을 담은 세트 만들기 요청 본문
        String body = "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"" + CHECKPOINT
                + "\",\"source\":\"PHONE\",\"photos\":[" + String.join(",", photos) + "]}";
        // (도구) mvc.perform(post(주소)) = 서버에 POST 요청을 흉내 내 보낸다. with(capture()) = 촬영 권한 토큰을 붙인다
        // (도구) andExpect(status().isCreated()) = 응답이 201(생성됨)이어야 한다는 검사. andReturn()…getContentAsString() = 응답 본문 글자를 꺼냄
        // 실행: 세트 만들기 요청을 보내고 201 응답 본문을 받는다
        String created = mvc.perform(post("/api/v1/captures/sets").with(capture())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        // 응답 글자를 JSON으로 읽는다
        JsonNode createdJson = json.readTree(created);
        // 만들어진 세트 ID를 꺼낸다
        String setId = createdJson.get("captureSetId").asText();

        // 알린 해시와 다른 파일은 저장소가 거절한다(서명된 x-amz-checksum-sha256). 체크섬을 검사하는 MinIO에서만 확인
        // 체크섬을 검사하는 실제 MinIO일 때만
        if (VERIFIES_CHECKSUM) {
            // 첫 사진의 업로드 정보(URL·헤더)를 꺼내고
            JsonNode first = createdJson.get("uploads").get(0);
            // (도구) isBetween(a, b) = 값이 a 이상 b 이하여야 한다는 검사
            // 실행+확인: 알린 것과 다른 내용을 올리면 400번대로 거절돼야 한다
            assertThat(put(first, "다른 사진".getBytes())).isBetween(400, 499);
        }

        // 처음 3장만 올리고 끊긴 상황: 남은 사진의 URL만 다시 받아 나머지를 올린다(11/2 결정)
        // 처음 받은 업로드 정보 7개
        JsonNode uploads = createdJson.get("uploads");
        // 앞의 3장만
        for (int i = 0; i < 3; i++) {
            // (도구) assertThat(실제값).isEqualTo(기대값) = "실제값이 기대값과 같아야 한다"는 검사
            // 실행+확인: 사전 서명 URL로 올리면 200이어야 한다
            assertThat(put(uploads.get(i), files.get(uploads.get(i).get("face").asText()))).isEqualTo(200);
        }
        // (도구) status().isOk() = 응답이 200이어야 한다는 검사
        // 실행: 남은 사진의 업로드 URL을 다시 달라고 요청해 목록을 받는다
        JsonNode reissued = json.readTree(mvc.perform(post("/api/v1/captures/sets/" + setId + "/uploads")
                .with(capture())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .get("uploads");
        // (도구) assertThat(목록).hasSize(n) = 목록 개수가 n이어야 한다는 검사
        // 확인: 다시 받은 URL은 남은 4장 것만이어야 한다
        assertThat(reissued).hasSize(PHONE_FACES.size() - 3);
        // 다시 받은 URL마다
        for (JsonNode upload : reissued) {
            // 실행+확인: 해당 면 사진을 올리면 200이어야 한다
            assertThat(put(upload, files.get(upload.get("face").asText()))).isEqualTo(200);
        }

        // (도구) header(이름, 값) = 요청 헤더를 붙인다. Idempotency-Key = 같은 요청을 두 번 보내도 한 번만 처리되게 하는 키
        // (도구) status().isAccepted() = 응답이 202(접수됨)여야 한다는 검사
        // 실행: 요청 키 it-key-0001로 세트 완료를 요청하고 202 응답 본문을 받는다
        String completed = mvc.perform(post("/api/v1/captures/sets/" + setId + "/complete").with(capture())
                .header("Idempotency-Key", "it-key-0001").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        // 확인: 세트 상태는 올림 완료(UPLOADED)여야 한다
        assertThat(json.readTree(completed).get("status").asText()).isEqualTo("UPLOADED");

        // 실행: 시험용 큐에서 메시지를 최대 10초 기다려 꺼낸다
        Message message = rabbit.receive(queue.getName(), 10_000);
        // (도구) isNotNull() = 값이 비어 있지(null) 않아야 한다는 검사
        // 확인: 업로드 완료 이벤트가 와야 한다
        assertThat(message).isNotNull();
        // 메시지 본문을 JSON으로 읽는다
        JsonNode event = json.readTree(message.getBody());
        // 확인: 메시지 ID가 이벤트 ID와 같아야 한다 (받는 쪽이 중복을 거르려고)
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
        // 확인: 렌탈 ID가 맞아야 한다
        assertThat(event.get("rentalId").asText()).isEqualTo(RENTAL);
        // 확인: 세트 ID가 맞아야 한다
        assertThat(event.get("captureSetId").asText()).isEqualTo(setId);
        // (도구) isTrue() = 값이 참이어야 한다는 검사
        // 확인: 탐지가 필요하다는 표시가 있어야 한다 (폰 사진은 서버가 탐지)
        assertThat(event.get("needDetection").asBoolean()).isTrue();
        // 확인: 이벤트에 사진 7장이 담겨야 한다
        assertThat(event.get("photos")).hasSize(PHONE_FACES.size());

        // 내려받은 파일 해시 = DB(세트 조회)의 해시 = 이벤트의 해시
        // (도구) get(주소) = 서버에 GET(조회) 요청을 흉내 내 보낸다
        // 실행: 세트를 조회해 DB에 저장된 내용을 받는다
        JsonNode stored = json.readTree(mvc.perform(get("/api/v1/captures/sets/" + setId).with(capture()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        // 사진 7장마다
        for (int i = 0; i < PHONE_FACES.size(); i++) {
            // 이벤트의 i번째 사진 정보를 꺼낸다
            JsonNode photo = event.get("photos").get(i);
            // 저장소에서 그 사진 파일을 직접 내려받는다
            byte[] downloaded = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(photo.get("objectKey").asText()))
                    .asByteArray();
            // 확인: 내려받은 파일의 해시 = 이벤트의 해시 = DB의 해시여야 한다 (사진이 바뀌지 않았음)
            assertThat(sha256(downloaded)).isEqualTo(photo.get("sha256").asText())
                    .isEqualTo(stored.get("photos").get(i).get("sha256").asText());
            // 확인: DB에 그 사진이 올라간 것으로 표시돼야 한다
            assertThat(stored.get("photos").get(i).get("uploaded").asBoolean()).isTrue();
        }
    }

    // [상황] 체크섬 없이 올린 사진과 없는 사진의 해시를 묻는다.
    // [기대] 체크섬이 없으면 내려받아 SHA-256을 직접 계산하고, 없는 사진은 빈 값이다.
    @Test
    @DisplayName("체크섬 없이 올린 객체는 내려받아 SHA-256을 계산하고, 없는 객체는 빈 값")
    void hashFallback() throws Exception {
        // 준비: 사진 버킷이 없으면
        if (!s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(BUCKET))) {
            // 버킷을 만든다
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        // 준비: 가짜 사진 내용
        byte[] bytes = "체크섬 없는 사진".getBytes();
        // 준비: 체크섬 없이 저장소에 직접 올린다
        s3.putObject(b -> b.bucket(BUCKET).key("captures/plain/TOP-ROOM-PHONE.jpg"), RequestBody.fromBytes(bytes));
        // (도구) contains(값) = (있을 수도 없을 수도 있는 값이) 이 값을 담고 있어야 한다는 검사
        // 실행+확인: 해시를 물으면 내려받아 계산한 SHA-256이 나와야 한다
        assertThat(storage.sha256Of("captures/plain/TOP-ROOM-PHONE.jpg")).contains(sha256(bytes));
        // (도구) isEmpty() = 값이 비어 있어야 한다는 검사
        // 실행+확인: 없는 파일의 해시는 빈 값이어야 한다
        assertThat(storage.sha256Of("captures/none/TOP-ROOM-PHONE.jpg")).isEmpty();
        // 실행+확인: 올린 파일은 "있음"이어야 한다
        assertThat(storage.exists("captures/plain/TOP-ROOM-PHONE.jpg")).isTrue();
    }

    // 테스트 도구: 사전 서명 URL로 사진을 PUT하고(서버가 준 헤더 포함) 응답 상태 코드를 돌려준다.
    private int put(JsonNode upload, byte[] bytes) throws Exception {
        // 사전 서명 URL로 사진 내용을 PUT하는 요청을 만든다
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.get("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        // 서버가 준 헤더(체크섬 등)를 모두 붙인다
        upload.get("headers").properties().forEach(h -> request.header(h.getKey(), h.getValue().asText()));
        // 요청을 보내고 응답 상태 코드만 돌려준다
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    // 테스트 도구: 바이트의 SHA-256 16진 문자열을 구한다.
    private static String sha256(byte[] bytes) throws Exception {
        // SHA-256을 계산해 16진 글자로 돌려준다
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
