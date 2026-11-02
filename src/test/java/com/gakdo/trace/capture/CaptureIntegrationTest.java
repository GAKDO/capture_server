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
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class CaptureIntegrationTest {

    private static final String BUCKET = "trace-captures";
    private static final String EXTERNAL_S3 = System.getenv("S3_TEST_ENDPOINT");
    private static final List<String> PHONE_FACES = List.of("TOP", "BOT", "SF", "SB", "SL", "SR", "SCR0");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("trace");

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

    /**
     * S3 호환 저장소. MinIO는 Docker Hub 공개 이미지 배포를 멈춰(pull access denied) CI에서 받을 수 없어,
     * mirror.gcr.io의 moto 서버를 쓴다. moto는 x-amz-checksum-sha256 내용 검사를 하지 않으므로 "다른 파일 거절"은
     * S3_TEST_VERIFIES_CHECKSUM=true(실제 MinIO 주소)일 때만 확인한다.
     */
    static final GenericContainer<?> S3 = new GenericContainer<>("mirror.gcr.io/motoserver/moto:5.1.16")
            .withExposedPorts(5000)
            .waitingFor(Wait.forHttp("/").forStatusCode(200));
    private static final boolean VERIFIES_CHECKSUM = "true".equals(System.getenv("S3_TEST_VERIFIES_CHECKSUM"));

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry registry) {
        if (EXTERNAL_S3 == null) {
            S3.start();
        }
        registry.add("app.storage.endpoint", () -> EXTERNAL_S3 != null ? EXTERNAL_S3
                : "http://" + S3.getHost() + ":" + S3.getMappedPort(5000));
        registry.add("app.storage.access-key", () -> System.getenv().getOrDefault("S3_TEST_ACCESS_KEY", "test"));
        registry.add("app.storage.secret-key", () -> System.getenv().getOrDefault("S3_TEST_SECRET_KEY", "test"));
        registry.add("app.storage.capture-bucket", () -> BUCKET);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private S3Client s3;

    @Autowired
    private PhotoStorage storage;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitAdmin admin;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("사진은 사전 서명 URL로 직접 올라가고, 완료하면 이벤트가 나가며, 내려받은 파일 해시와 저장된 해시가 같다")
    void endToEnd() throws Exception {
        if (!s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(BUCKET))) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        Queue queue = new Queue("test.capture.uploaded", false, false, true);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange("trace.events")).with("trace.capture.uploaded"));

        Map<String, byte[]> files = new LinkedHashMap<>();
        List<String> photos = new ArrayList<>();
        Random random = new Random(7);
        for (String face : PHONE_FACES) {
            byte[] bytes = new byte[2048 + random.nextInt(4096)];
            random.nextBytes(bytes);
            files.put(face, bytes);
            photos.add("{\"face\":\"" + face + "\",\"light\":\"ROOM\",\"camera\":\"PHONE\",\"sha256\":\"" + sha256(bytes)
                    + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}");
        }
        String body = "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"" + CHECKPOINT
                + "\",\"source\":\"PHONE\",\"photos\":[" + String.join(",", photos) + "]}";
        String created = mvc.perform(post("/api/v1/captures/sets").with(capture())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        JsonNode createdJson = json.readTree(created);
        String setId = createdJson.get("captureSetId").asText();

        // 알린 해시와 다른 파일은 저장소가 거절한다(서명된 x-amz-checksum-sha256). 체크섬을 검사하는 MinIO에서만 확인
        if (VERIFIES_CHECKSUM) {
            JsonNode first = createdJson.get("uploads").get(0);
            assertThat(put(first, "다른 사진".getBytes())).isBetween(400, 499);
        }

        for (JsonNode upload : createdJson.get("uploads")) {
            assertThat(put(upload, files.get(upload.get("face").asText()))).isEqualTo(200);
        }

        String completed = mvc.perform(post("/api/v1/captures/sets/" + setId + "/complete").with(capture())
                .header("Idempotency-Key", "it-key-0001").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(completed).get("status").asText()).isEqualTo("UPLOADED");

        Message message = rabbit.receive(queue.getName(), 10_000);
        assertThat(message).isNotNull();
        JsonNode event = json.readTree(message.getBody());
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.get("eventId").asText());
        assertThat(event.get("rentalId").asText()).isEqualTo(RENTAL);
        assertThat(event.get("captureSetId").asText()).isEqualTo(setId);
        assertThat(event.get("needDetection").asBoolean()).isTrue();
        assertThat(event.get("photos")).hasSize(PHONE_FACES.size());

        // 내려받은 파일 해시 = DB(세트 조회)의 해시 = 이벤트의 해시
        JsonNode stored = json.readTree(mvc.perform(get("/api/v1/captures/sets/" + setId).with(capture()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        for (int i = 0; i < PHONE_FACES.size(); i++) {
            JsonNode photo = event.get("photos").get(i);
            byte[] downloaded = s3.getObjectAsBytes(b -> b.bucket(BUCKET).key(photo.get("objectKey").asText()))
                    .asByteArray();
            assertThat(sha256(downloaded)).isEqualTo(photo.get("sha256").asText())
                    .isEqualTo(stored.get("photos").get(i).get("sha256").asText());
            assertThat(stored.get("photos").get(i).get("uploaded").asBoolean()).isTrue();
        }
    }

    @Test
    @DisplayName("체크섬 없이 올린 객체는 내려받아 SHA-256을 계산하고, 없는 객체는 빈 값")
    void hashFallback() throws Exception {
        if (!s3.listBuckets().buckets().stream().anyMatch(b -> b.name().equals(BUCKET))) {
            s3.createBucket(b -> b.bucket(BUCKET));
        }
        byte[] bytes = "체크섬 없는 사진".getBytes();
        s3.putObject(b -> b.bucket(BUCKET).key("captures/plain/TOP-ROOM-PHONE.jpg"), RequestBody.fromBytes(bytes));
        assertThat(storage.sha256Of("captures/plain/TOP-ROOM-PHONE.jpg")).contains(sha256(bytes));
        assertThat(storage.sha256Of("captures/none/TOP-ROOM-PHONE.jpg")).isEmpty();
        assertThat(storage.exists("captures/plain/TOP-ROOM-PHONE.jpg")).isTrue();
    }

    private int put(JsonNode upload, byte[] bytes) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(upload.get("url").asText()))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(bytes));
        upload.get("headers").properties().forEach(h -> request.header(h.getKey(), h.getValue().asText()));
        return http.send(request.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
