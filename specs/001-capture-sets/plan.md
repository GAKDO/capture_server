# 구현 계획: CA1 촬영 세트·사전 서명 URL

**브랜치**: `claude/project-thread-aosr81` | **날짜**: 2026-11-02 | **명세**: [spec.md](spec.md)

## 요약

contracts `capture-server.yaml`의 촬영 경로 3개(세트 생성·완료·조회)를 Spring Boot로 만든다. capture·inspection 스키마는 GAKDO/docs `db/V1__init.sql`의 해당 절에 CA1이 필요한 칸(렌탈 ID, 업로드 확인, 완료 멱등 키)을 더해 Flyway V1로 둔다. 사진은 AWS SDK v2 S3 Presigner로 만든 URL로 MinIO에 직접 올라가고, 완료 때 해시를 확인해 `trace.capture.uploaded`를 낸다.

## 기술 맥락 (CLAUDE.md "계획에 줄 기술 설명")

Java 21, Spring Boot 3.5.7, Maven(./mvnw). Spring Web MVC, Spring Data JPA, PostgreSQL 스키마 capture·inspection, Flyway(src/main/resources/db/migration, 이 서버 스키마만, GAKDO/docs db/V1__init.sql의 해당 절에서 시작), Spring Security OAuth2 리소스 서버(auth-server JWKS 검증), MinIO 사전 서명 URL은 AWS SDK v2 S3 Presigner(버킷 trace-captures), Spring AMQP(trace.events), Bean Validation, ProblemDetail(RFC 9457). 테스트는 JUnit 5 + Spring Boot Test + Testcontainers(PostgreSQL·RabbitMQ·MinIO), JaCoCo 라인 커버리지 40% 이상. Testcontainers 테스트는 `@Testcontainers(disabledWithoutDocker = true)`, 도메인 로직은 Docker 없이 도는 단위 테스트로도 덮는다. 패키지는 `com.gakdo.trace.capture` 아래 기능별. 기존 ping·application.yml·Dockerfile을 유지하고, 주소·비밀은 환경 변수(gitops infra.env, trace-secrets의 이름)로 받는다.

| 항목 | 값 |
| --- | --- |
| DB | `jdbc:postgresql://${POSTGRES_HOST}:${POSTGRES_PORT}/trace`, `DB_USERNAME`·`DB_PASSWORD`, Flyway 스키마 `capture`·`inspection` |
| AMQP | `RABBITMQ_*`, exchange `trace.events`(topic), 라우팅 키 `trace.capture.uploaded`, 채널 트랜잭션 |
| MinIO | `MINIO_ENDPOINT`·`MINIO_ACCESS_KEY`·`MINIO_SECRET_KEY`, 버킷 `MINIO_CAPTURE_BUCKET`(기본 `trace-captures`), path-style, 지역 `us-east-1` |
| JWKS | `AUTH_JWKS_URI`, 기본 `${AUTH_SERVER_URL}/.well-known/jwks.json`, iss `trace` |

## 헌법 점검

| 원칙 | 판단 |
| --- | --- |
| I 계약 먼저 | OpenAPI·codes.yaml·이벤트 스키마를 그대로 따른다. 업로드 `headers`에 체크섬 헤더를 싣는 것은 계약이 이미 연 칸("PUT에 그대로 실어야 하는 헤더")이다. 통과 |
| II 서비스 경계 | capture·inspection 스키마만. 렌탈·시점은 값으로만. 사진은 MinIO 직접 업로드. 통과 |
| III 기록 불변 | 사진 SHA-256 저장, 객체 키는 세트별이라 덮어쓰지 않음, 완료된 세트는 다시 바뀌지 않음. 통과 |
| IV 테스트 | 수용 기준 문장을 `@DisplayName`으로. 단위 + WebMvc + Testcontainers. 통과 |
| V 비밀 | MinIO 키·DB·Rabbit 비밀은 환경 변수. 사전 서명 URL은 로그에 남기지 않는다. 통과 |
| VI 단순 | 경로 3개만. 아웃박스 대신 채널 트랜잭션(위험은 아래). 통과 |

## 구조

```
src/main/java/com/gakdo/trace/capture/
  common/   api(ProblemAdvice, ProblemFactory) · domain(ErrorCode, DomainException) · infra(PublicIds, Times, SecurityConfig, Caller)
  captureset/
    api(CaptureSetController, CaptureSetDtos)
    application(CaptureSetService, CaptureEventPublisher)
    domain(CaptureSet, Photo, CaptureSource, Face, Light, Camera, SetStatus, FaceRules, CompletionCheck)
    infra(CaptureSetRepository, PhotoStorage, S3PhotoStorage, S3Config, AmqpConfig, RabbitCaptureEventPublisher)
src/main/resources/db/migration/V1__init_capture.sql
```

## 설계 결정 (research)

1. **해시 확인**: 사전 서명 요청에 `checksumSHA256`(base64)을 넣으면 `x-amz-checksum-sha256`이 서명 헤더가 되어, 클라이언트는 그 값을 보내야 하고 MinIO는 내용이 다르면 거절한다. complete는 `HeadObject(ChecksumMode=ENABLED)`의 체크섬과 대조하고, 체크섬이 없으면(헤더 없이 올린 옛 객체 등) 내려받아 계산한다. 서버가 사진 본문을 받지 않는다.
2. **면 규칙은 도메인에**: `FaceRules.required(source)`가 codes.yaml 0.5.0 face_required를 옮긴 값(이름 대조 테스트). `CompletionCheck`가 (알린 사진, 올라간 사진, skippedFaces) → 빠진 면·400 오류를 계산한다. 저장소 없이 단위 테스트.
3. **이벤트**: 서비스 트랜잭션 안에서 채널 트랜잭션 RabbitTemplate으로 보내면 DB 커밋 뒤 Rabbit 커밋이 따라간다. 위험: DB 커밋 뒤 Rabbit 커밋이 실패하면 이벤트가 빠진다 → 로그 ERROR, 시연 규모에서 감수(아웃박스는 필요해지면 CA2에서). messageId = eventId.
4. **멱등**: 세트 행에 `completion_key`·`completion_request_sha256`·`completion_response`. 같은 키·같은 본문 → 저장된 응답, 다른 본문 → 422, 다른 키 → 409 CAPTURE_SET_CLOSED. 세트 행을 `SELECT … FOR UPDATE`로 잠가 동시 완료를 막는다.
5. **범위**: `Caller`(role·scope·rentalId) 하나로 판단. BOOTH → source BOOTH·boothId=scope. CUSTOMER + scope → 촬영 JWT, checkpointId=scope·rentalId=rentalId 클레임. scope 없는 CUSTOMER → 403 FORBIDDEN.
6. **ID·시각**: rental-server와 같은 PublicIds(NanoID 21자)·Times(UTC 마이크로초). 응답 시각은 UTC 오프셋 `Z`.
7. **조회의 uploaded**: UPLOADING이면 HeadObject로 존재만 본다(저장소 오류면 false + WARN). 이후 상태는 DB 값.
