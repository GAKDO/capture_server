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
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class ReviewFlowIntegrationTest {

    private static final String RENTAL = "RvwRntLv6QRbDILPWOcz5";
    private static final String CP = "RvwCpQOlTkKj6mOecAQt1";
    private static final String SHA = "7f2c4e0b7a1d3c5e8f0a2b4c6d8e0f1a3b5c7d9e1f2a4b6c8d0e2f4a6b8c0d2e";

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

    @Test
    @DisplayName("AI 후보가 있는 빠른 검사는 검수 완료 뒤에만 등급이 나고, 기각한 찍힘은 빠져 긁힘 하나로 B, 이벤트에 검수 기록이 실린다")
    void reviewThenGrade() throws Exception {
        Queue graded = new Queue("test.review.graded", false, false, false);
        rabbitAdmin.declareQueue(graded);
        rabbitAdmin.purgeQueue(graded.getName(), false);
        rabbitAdmin.declareBinding(BindingBuilder.bind(graded).to(new TopicExchange("trace.events"))
                .with("trace.checkpoint.graded"));

        String setId = quickSet();
        String detections = "{\"modelVersion\":\"aug-v002\",\"detections\":["
                + detection("TOP", "scratch", 400) + "," + detection("BOT", "dent", 600) + "],"
                + "\"analyzedPhotos\":[" + String.join(",", analyzed()) + "]}";
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/detections").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(detections))
                .andExpect(status().isCreated());

        // 등급 보류: grade null, 후보 2개 PENDING, 대기 목록에 나온다
        JsonNode held = inspection(admin());
        assertThat(held.get("grade").isNull()).isTrue();
        assertThat(held.get("review").get("pending").asInt()).isEqualTo(2);
        assertThat(held.get("photoDefects")).allMatch(d -> "PENDING".equals(d.get("review").get("status").asText())
                && d.get("groupId").asText().equals(d.get("defectId").asText()));
        mvc.perform(get("/api/v1/captures/reviews").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.checkpointId == '" + CP + "')].pending").value(2));
        assertThat(rabbit.receive(graded.getName(), 500)).isNull();

        // PENDING이 남은 채로 완료하면 409
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-" + CP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_PENDING_REMAINS"))
                .andExpect(jsonPath("$.pending").value(2));

        String scratch = idOf(held, "scratch");
        String dent = idOf(held, "dent");
        // 기각에 사유가 없으면 400
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"defectId\":\"" + dent + "\",\"decision\":\"DISMISSED\"}]}"))
                .andExpect(status().isBadRequest());
        // 고객은 검수할 수 없다
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews").with(customer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"defectId\":\"" + dent + "\",\"decision\":\"CONFIRMED\"}]}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews").with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"defectId\":\"" + scratch + "\",\"decision\":\"CONFIRMED\"},"
                        + "{\"defectId\":\"" + dent + "\",\"decision\":\"DISMISSED\",\"reason\":\"TAPE\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").isEmpty())
                .andExpect(jsonPath("$.review.pending").value(0))
                .andExpect(jsonPath("$.review.dismissed").value(1));

        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-" + CP))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grade").value("B"))
                .andExpect(jsonPath("$.review.confirmed").value(1))
                .andExpect(jsonPath("$.review.completedAt").isNotEmpty());
        // 같은 키로 다시 보내도 200, 다른 키는 409
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-" + CP))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/captures/checkpoints/" + CP + "/reviews/complete").with(admin())
                .header("Idempotency-Key", "review-other-" + CP))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REVIEW_CLOSED"));

        Message m = rabbit.receive(graded.getName(), 10_000);
        assertThat(m).as("trace.checkpoint.graded").isNotNull();
        JsonNode event = json.readTree(m.getBody());
        assertThat(event.get("schemaVersion").asText()).isEqualTo("1.3");
        assertThat(event.get("grade").asText()).isEqualTo("B");
        assertThat(event.get("review").get("reviewedBy").asText()).isEqualTo("admin0000000000000001");
        assertThat(event.get("review").get("dismissed").asInt()).isEqualTo(1);

        // 고객에게는 기각한 찍힘이 보이지 않는다
        JsonNode forCustomer = inspection(customer());
        assertThat(forCustomer.get("photoDefects")).hasSize(1);
        assertThat(forCustomer.get("photoDefects").get(0).get("defectId").asText()).isEqualTo(scratch);

        mvc.perform(get("/api/v1/captures/reviews?status=CONFIRMED").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.checkpointId == '" + CP + "')].dismissed").value(1));
    }

    private JsonNode inspection(org.springframework.test.web.servlet.request.RequestPostProcessor token)
            throws Exception {
        return json.readTree(mvc.perform(get("/api/v1/captures/checkpoints/" + CP + "/inspection").with(token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static String idOf(JsonNode view, String cls) {
        for (JsonNode d : view.get("photoDefects")) {
            if (cls.equals(d.get("class").asText())) {
                return d.get("defectId").asText();
            }
        }
        throw new AssertionError("흠집이 없습니다: " + cls);
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("cust00000000000000009").claim("role", "CUSTOMER")
                .claim("rentals", List.of(RENTAL)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    private String quickSet() throws Exception {
        List<String> photos = new ArrayList<>();
        for (String face : List.of("TOP", "BOT")) {
            for (String cam : List.of("CAM1", "CAM2")) {
                photos.add("{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"" + cam + "\",\"sha256\":\""
                        + SHA + "\",\"contentType\":\"image/jpeg\",\"takenAt\":\"2026-11-12T13:05:00+09:00\"}");
            }
        }
        String body = "{\"rentalId\":\"" + RENTAL + "\",\"checkpointId\":\"" + CP
                + "\",\"source\":\"BOOTH\",\"profile\":\"QUICK\",\"photos\":[" + String.join(",", photos) + "]}";
        JsonNode created = json.readTree(mvc.perform(post("/api/v1/captures/sets").with(booth())
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        String setId = created.get("captureSetId").asText();
        for (JsonNode u : created.get("uploads")) {
            storage.put("captures/" + setId + "/" + u.get("face").asText() + "-" + u.get("light").asText() + "-"
                    + u.get("camera").asText() + ".jpg", SHA);
        }
        mvc.perform(post("/api/v1/captures/sets/" + setId + "/complete").with(booth())
                .header("Idempotency-Key", "key-" + setId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isAccepted());
        return setId;
    }

    private static List<String> analyzed() {
        List<String> out = new ArrayList<>();
        for (String face : List.of("TOP", "BOT")) {
            for (String cam : List.of("CAM1", "CAM2")) {
                out.add("{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"" + cam + "\"}");
            }
        }
        return out;
    }

    private static String detection(String face, String cls, double x) {
        return "{\"face\":\"" + face + "\",\"light\":\"L1\",\"camera\":\"CAM1\",\"class\":\"" + cls
                + "\",\"polygon\":[[" + x + "," + x + "],[" + (x + 40) + "," + x + "],[" + (x + 40) + "," + (x + 8)
                + "],[" + x + "," + (x + 8) + "]],\"confidence\":0.31}";
    }
}
