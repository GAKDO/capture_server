# 작업: CA1 촬영 세트·사전 서명 URL

[P]는 다른 작업과 동시에 할 수 있음. 테스트를 먼저 쓰고 구현한다.

## 1단계 준비
- [x] T001 pom.xml에 JPA·Flyway·PostgreSQL·AMQP·리소스 서버·AWS SDK v2 S3·Testcontainers(PostgreSQL·RabbitMQ·MinIO) 추가
- [x] T002 application.yml에 DB·Rabbit·JWKS·MinIO 환경 변수
- [x] T003 Flyway `V1__init_capture.sql`(docs V1 capture·inspection 절 + CA1 칸)
- [x] T004 [P] common: ErrorCode·DomainException·ProblemAdvice·ProblemFactory·PublicIds·Times·SecurityConfig·Caller
- [x] T005 [P] ping을 계약 PingResponse(service·version·commit)에 맞춤

## 2단계 도메인 (이야기 1·2)
- [x] T010 [P] 코드 enum(CaptureSource·Face·Light·Camera·SetStatus)과 codes.yaml 0.5.0 대조 테스트
- [x] T011 FaceRules·CompletionCheck 단위 테스트(필수 면, 일부만 올림, 선택 면 사유, 필수 면 skip 400)
- [x] T012 CaptureSet·Photo 엔티티, 사진 검증(겹침·source별 카메라·조명)

## 3단계 이야기 1 세트 생성
- [x] T020 PhotoStorage 포트 + S3PhotoStorage(Presigner, 체크섬 헤더, Head·Get)
- [x] T021 POST /captures/sets: 범위 확인, 세트·사진 저장, uploads 같은 순서
- [x] T022 WebMvc 테스트: 201·순서·헤더, 400 겹침·카메라, 403 범위

## 4단계 이야기 2 완료
- [x] T030 CaptureSetService.complete: 잠금·멱등·해시·필수 면·상태·이벤트
- [x] T031 RabbitCaptureEventPublisher + AmqpConfig(trace.events, 채널 트랜잭션)
- [x] T032 WebMvc 테스트: 202, 409 빠진 면·해시, 400 skip, 422·409 멱등, 400 키 없음

## 5단계 이야기 3 조회·범위
- [x] T040 GET /captures/sets/{setId}: ADMIN·촬영 JWT, UPLOADING이면 존재 확인
- [x] T041 WebMvc 테스트: 403 FORBIDDEN(일반 CUSTOMER)·OUT_OF_SCOPE, 404, 401

## 6단계 통합
- [x] T050 Testcontainers(PostgreSQL·RabbitMQ·MinIO): 생성 → 사전 서명 PUT → complete → 이벤트 수신 → 내려받은 해시 = 저장 해시, 다른 내용 PUT은 저장소가 거절
- [x] T051 `./mvnw verify`(JaCoCo 40%)
- [x] T052 docs ddl.md·V1__init.sql·decisions.md, 공유 칸
