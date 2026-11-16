# capture-server

TRACE(노트북 렌탈 상태 기록 서비스)의 **capture-server**: 촬영 세트·사전 서명 URL·탐지 결과·사람 검수·시점 비교·등급·정산

- 담당: A
- 설계: [GAKDO/docs](https://github.com/GAKDO/docs) — 요구사항·아키텍처·ERD·REST API v0
- 기술: Java 21, Spring Boot 3.5.7, Maven

## 실행

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/captures/ping
```

## API

계약 원본: GAKDO/contracts `openapi/capture-server.yaml`. 아래는 요약입니다.

| 경로 | 하는 일 |
|---|---|
| `POST /api/v1/captures/sets`, `GET /api/v1/captures/sets/{setId}` | 촬영 세트 만들기(부스 `QUICK`·`FULL`, 고객 폰 수령 촬영), 조회 |
| `POST /api/v1/captures/sets/{setId}/uploads` | 사진마다 MinIO 사전 서명 업로드 URL(사진은 서버를 거치지 않음) |
| `POST /api/v1/captures/sets/{setId}/complete` | 올린 사진의 SHA-256 확인 후 `capture.uploaded` 발행(폰 사진은 inspect-worker가 검사) |
| `POST /api/v1/captures/sets/{setId}/detections` | 부스 Jetson의 탐지 결과(폴리곤) 저장 |
| `GET /api/v1/captures/reviews`, `POST /api/v1/captures/checkpoints/{checkpointId}/reviews`(`/additions`·`/complete`) | **사람 검수**(관리자): 검수 대기 목록, AI 후보 확정·기각, 놓친 흠집 직접 그리기, 검수 끝내기 |
| `GET /api/v1/captures/checkpoints/{checkpointId}/inspection`·`/media`·`/settlement` | 시점 검사 결과(흠집·신규 판정·등급 A~D, 이전 시점 비교), 사진, 정산 |
| `GET /api/v1/captures/rentals/{rentalId}/profile` | 출고 때 고른 검사 방식(수령·반납도 같은 방식만) |
| `GET /api/v1/captures/catalog`·`/catalog/checkpoints/{checkpointId}` | 로그인 없이 보는 보유 노트북 흠집 위치(3D) |
| `GET /api/v1/captures/benchmarks` | 모델별 평가 점수(관리자) |

AI는 후보를 넓게 잡고, 관리자가 확정한 흠집만 등급에 들어갑니다(`REVIEW_REQUIRED`). 등급이 나오면 `checkpoint.graded`를 발행합니다.

## 환경 변수

| 이름 | 뜻 | 기본값 |
|---|---|---|
| `POSTGRES_HOST`·`POSTGRES_PORT`·`POSTGRES_DB`, `DB_USERNAME`·`DB_PASSWORD` | `capture`·`inspection` 스키마 | `localhost`·`5432`·`trace`, `trace` |
| `RABBITMQ_HOST`·`RABBITMQ_PORT`·`RABBITMQ_VHOST`·`RABBITMQ_USERNAME`·`RABBITMQ_PASSWORD` | `trace.events` | `localhost`·`5672`·`/` |
| `AUTH_JWKS_URI`(또는 `AUTH_SERVER_URL`) | JWT 검증 공개 키 | `http://auth-server:8080` |
| `MINIO_ENDPOINT`·`MINIO_ACCESS_KEY`·`MINIO_SECRET_KEY`·`MINIO_REGION` | 사진 저장소 | `http://localhost:9000`, 없음, `us-east-1` |
| `MINIO_CAPTURE_BUCKET`·`MINIO_DERIVED_BUCKET` | 원본·펼친 사진 버킷 | `trace-captures`·`trace-derived` |
| `MINIO_PUBLIC_BASE` | 사전 서명 URL 앞부분을 이 주소(web)로 바꿈. HTTPS 화면에서 http MinIO를 직접 부르지 않게 | 없음 |
| `REVIEW_REQUIRED` | `true`면 관리자 검수가 끝나야 등급을 매김(`false`로 되돌림) | `true` |
| `REVIEW_HOLD_EMPTY` | `true`면 AI 후보가 없는 세트도 검수를 기다림 | `true` |
| `MODEL_METRICS` | 모델별 검증 점수(학습 PC에서 잰 값) | 없음 |
| `TRACE_SETTLEMENT_BASE_PRICE` | 정산 기준가(원) | `2057693` |

## 테스트

```bash
./mvnw verify
```

줄 커버리지 40% 미만이면 실패합니다(블로그와 같은 기준).

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `backend-ci` | PR, develop push | 테스트 + JaCoCo 40% + (켜져 있으면) 학교 SonarQube |
| `배포` | PR(빌드만), develop·main push(`.md`만 바뀌면 건너뜀) | 이미지 → GHCR(`ghcr.io/<조직>/capture-server`, develop은 `:develop`). 지금 운영(https://gakdo.site, 학교 서버 Compose)은 gitops `compose/ops/autodeploy.sh`가 3분마다 `:develop`을 받아 이 서비스만 무중단으로 바꾼다. main이면 gitops `overlays/school` 이미지 태그도 바꾼다(클러스터 경로, 지금은 쓰지 않음) |
| `비밀값 검사` | PR, main·develop push | 학교 공용 비밀번호 패턴 + gitleaks |
| `release` | main에 CHANGELOG 버전 | 태그와 GitHub Release |
| `PR 알림`·`CI 결과 알림` | PR·CI 끝 | Discord·텔레그램 (변수로 켬) |

브랜치 규칙과 릴리스 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.
