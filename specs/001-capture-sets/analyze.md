# 분석: CA1 (명세·계획·작업 대조)

| 확인 | 결과 |
| --- | --- |
| 수용 기준 → 테스트 | backlog 수용 기준 6개 모두 `@DisplayName`으로 있음: 사진 본문 없음(경로가 JSON만, 통합 시험에서 PUT은 MinIO로), 내려받은 해시 = 저장 해시(CaptureIntegrationTest), 필수 면 409(부스 8·폰 7), SCR1 skippedFaces, uploads의 face·light·camera, 409 missingFaces |
| 계약 대조 | 경로 3개·상태 코드·오류 code(codes.yaml error_code)·이벤트 필드가 contracts develop과 같음. 계약 변경 없음 |
| 범위 확인 | gateway가 못 보는 시점·세트·부스 범위를 서버가 확인(이야기 3, decisions.md 11/2 GW1) |
| DDL | capture_set.rental_id·completion_*, photo.uploaded를 docs ddl.md·V1__init.sql에 반영 |
| 남은 위험 | DB 커밋 뒤 Rabbit 커밋 실패 시 이벤트 유실(로그 ERROR). inspect-worker 큐가 없으면 이벤트가 버려짐 → ML2가 큐를 durable로 선언해야 함 |
| 계약 문구 | events `trace.capture.uploaded`의 objectKey 설명이 "버킷 trace-raw"이나 서비스 버킷은 trace-captures(설계서·backlog). 설명만 고치면 되는 문구 오류로 사람에게 보고 |

**Converged**: 작업 T001~T052 완료, `./mvnw verify` 통과(테스트 32개, 줄 커버리지 93.7%).
