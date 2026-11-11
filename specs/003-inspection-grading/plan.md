# 구현 계획: CA2 검사 결과 저장·비교 연결·등급 (A 몫)

**명세**: [spec.md](spec.md) · **기술**: CLAUDE.md "계획에 줄 기술 설명" 그대로(Java 21, Spring Boot 3.5.7, JPA, Flyway, Spring AMQP, ProblemDetail, JUnit 5 + Testcontainers `disabledWithoutDocker`).

## 헌법 점검

| 원칙 | 이 기능에서 |
|---|---|
| I 계약 먼저 | 계약은 바꾸지 않는다. 경로·이벤트 필드는 contracts develop(PR #8~#10) 그대로. 시점 종류는 추론(계약 변경 없음) |
| II 시험 | 등급·판정 불가·호모그래피·묶기는 Docker 없는 단위 시험, 흐름은 Testcontainers(PostgreSQL·RabbitMQ) |
| III 원본·null | 원본 사진·원본 px는 덮지 않는다. 정합 안 된 흠집은 polygon null, 판정 가능 면이 없으면 등급 없음 |
| IV 버전 | defect.model_version, defect_match.rule_version(view-match-v1), grade_result.rule_version(grade-rules-v1) |
| V 보안 | 부스 범위·본인 렌탈은 서버가 토큰으로 다시 본다(gateway는 경로 rentalId까지만) |

## 구조 (`com.gakdo.trace.capture.inspection`)

```
inspection/
  api/          InspectionController (POST detections, GET inspection), InspectionDtos(record)
  application/  DetectionService(부스 탐지 저장), InspectionService(정합 결과 저장), GradingService(비교·등급·이벤트),
                InspectionQueryService(조회), InspectEvent(받는 이벤트 record), CheckpointGradedEvent(내는 이벤트 record),
                GradeEventPublisher(포트)
  domain/       Defect, FaceAlignment, DefectMatchRecord, GradeResult (엔티티)
                Homography, PolygonMeasure, Judgeability, DefectGroups, GradeRules, CheckpointKind (순수 도메인)
  infra/        Repository들, InspectAmqpConfig(큐 trace.capture.inspected + DLQ), InspectEventListener, RabbitGradeEventPublisher
```

- `comparison/`(C)은 그대로 부른다: `new ViewComparator(MatchRule.V1).compare(prev, current)`.
- 세트 행 잠금(`CaptureSetRepository.findForUpdate`)으로 탐지 POST와 정합 이벤트가 동시에 와도 한 번만 등급을 매긴다.
- 이벤트는 CA1과 같이 채널 트랜잭션(DB 커밋과 함께). 소비 실패는 3회 재시도 뒤(형식 오류로 던진 `AmqpRejectAndDontRequeueException`은 원인 사슬에 있어도 재시도 없이 바로) `trace.dlx` → `trace.capture.inspected.dlq`(RE1 규칙).

## 흐름

1. 탐지 POST: 범위 확인 → 세트 잠금 → GRADED면 409 → 사진 찾기(없으면 400) → 세트의 흠집·매칭 지우고 새로 저장 → `detections_received_at` → 정합이 와 있으면 표준 좌표 채우고 등급.
2. 정합 이벤트: 세트 잠금 → UPLOADED가 아니면 무시(멱등) → 행렬 검사 → face_alignment 저장 → 폰이면 worker 흠집 저장(역행렬로 원본 px) / 부스면 저장된 탐지에 표준 좌표 → INSPECTED → (폰이거나 탐지 받음) 등급.
3. 등급: 판정 불가 면 → 판정 가능 0이면 UNJUDGEABLE로 끝 → 앞 시점 찾기(같은 렌탈, 다른 checkpointId, grade_result 최근) → 비교·매칭 저장 → 묶기 → 등급 규칙 → grade_result 저장(시점당 하나, 있으면 갱신) → GRADED → 이벤트.

## 마이그레이션

`V2__inspection_results.sql`: 명세 "DDL 변경". 테이블이 비어 있어도 안전하게 기본값을 주고 바로 지운다.

## PR 나누기

코드 400줄 기준으로 PR 1개(탐지 저장 + 정합 저장 + 등급 + 조회)를 시도하고, 넘치면 저장(1)·등급과 조회(2)로 나눈다. `/media`와 재시도 정책은 후속 PR.
