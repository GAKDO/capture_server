# 005 성능 그래프용 처리 시간 (11/6)

C가 웹 /admin에 성능 그래프(Jetson 판정 시간, 세트당 처리 시간)를 만들려고 요청했고 민서가 승인했다. InfluxDB·Grafana는 쓰지 않는다. 계약은 contracts #29(추가만).

## 수용 기준

- 부스가 탐지 결과에 `inferenceMs`(선택, 0~600000)를 실으면 세트에 남고, 다시 보내면 마지막 값이 남는다. 안 실으면 null.
- 촬영 완료 요청을 받은 시각(`completedAt`)을 세트에 남긴다. 11/6 전 세트는 null.
- `GET /api/v1/captures/benchmarks`(ADMIN)는 최근 세트부터 `limit`(기본 20, 최대 100)개를 돌려준다. `source`로 BOOTH·PHONE을 거른다.
- 항목마다 세트·시점 ID, 출처·범위·부스, 모델 버전, 올라간 사진 수, `inferenceMs`, 시각 4개(생성·완료·탐지 수신·등급), `completedToGradedMs`(완료→등급, 둘 중 하나라도 없거나 순서가 뒤집히면 null).
- 관리자가 아니면 403, `limit`가 범위를 벗어나면 400.

## 설계

- Flyway V5: `capture_set.completed_at`·`detection_model_version`·`inference_ms`.
- 조회는 여러 표(세트·사진 수·등급)를 모으는 읽기 전용이라 JPA 투영 대신 `NamedParameterJdbcTemplate`(benchmark/infra)로 읽는다.
- 시험: `BenchmarkServiceTest`(변환·권한), `InspectionFlowIntegrationTest` 출고 단계에서 실제 PostgreSQL로 확인(Docker 필요, CI).
