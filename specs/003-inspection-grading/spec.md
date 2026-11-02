# 기능 명세: CA2 검사 결과 저장·시점 비교 연결·판정 불가 면·A~D 등급 (A 몫)

**브랜치**: `claude/project-thread-om86mt` (세션 지정 브랜치)
**작성**: 2026-11-02
**상태**: 확정 (clarify 질문은 민서의 "묻지 말고 진행" 지시로 추천안으로 정함, 아래 "정한 것")
**요구사항**: FR-INS-03(이전 시점 흠집과 비교해 신규·기존 판정), FR-INS-04(외관 A~D 등급, 오염 제외, 규칙 버전), FR-CAP-04(품질 미달 면은 판정 불가, 비교·등급에서 뺌), FR-INS-01(부스 1차 탐지 저장)
**나누기 (11/2, decisions.md)**: C = 시점 비교 모듈(`comparison/`, specs/002). A(이 명세) = 결과 저장·판정 불가 면·등급과 연결.
**계약**: contracts `openapi/capture-server.yaml`(saveBoothDetections·getCheckpointInspection), `events/trace.inspect.completed`·`trace.checkpoint.graded`, `capture/faces.md`(1 px = 0.25 mm), codes.yaml inspection.*. 계약 PR #8·#9·#10.

## 사용자 시나리오

### 이야기 1 — 부스 탐지 저장 (P1)

Jetson이 촬영 직후 탐지한 흠집을 `POST /api/v1/captures/sets/{setId}/detections`로 올린다.

1. **주어진** 자기 부스 세트, 탐지마다 (face, light, camera)와 원본 사진 px polygon **그러면** 201 `{accepted: n}`, 흠집은 그 사진의 `photo_polygon`에 원본 px로 저장된다(표준 좌표 `polygon`은 정합 뒤).
2. **주어진** 세트에 없는 (face, light, camera) **그러면** 400 `VALIDATION_FAILED`, 오류 위치 `detections[i]`, 아무것도 바뀌지 않는다.
3. **주어진** 같은 세트에 다시 보냄(modelVersion이 달라도) **그러면** 그 세트의 부스 탐지를 통째로 바꾼다(마지막 요청만 남는다).
4. **주어진** 등급이 매겨진 세트 **그러면** 409 `CAPTURE_SET_CLOSED`.
5. **주어진** 다른 부스의 세트·폰 세트·부스 아닌 토큰 **그러면** 403(`OUT_OF_SCOPE` / `FORBIDDEN`). 없는 세트는 404.
6. **주어진** 이미 정합 결과가 와 있는 세트 **그러면** 받자마자 표준 좌표로 바꾸고 등급까지 매긴다.

### 이야기 2 — 정합·탐지 결과 저장 (P1)

inspect-worker의 `trace.inspect.completed`를 큐 `trace.capture.inspected`로 받는다.

1. 사진별 `alignments`(호모그래피·오차·펼친 이미지 키)를 `inspection.face_alignment`에 저장한다.
2. 부스 세트: 저장된 부스 탐지의 원본 px를 그 사진의 호모그래피로 표준 좌표 px로 옮겨 `polygon`을 채우고, 길이(mm)·넓이(mm²)를 표준 좌표에서 잰다.
3. 폰 세트(needDetection): 이벤트의 `defects`(표준 좌표 px)를 저장한다. `photo_polygon`은 그 사진 호모그래피의 역행렬로 원본 px를 구해 채운다. `defectId`는 worker가 준 값을 그대로 쓴다.
4. 같은 이벤트가 다시 오거나 이미 처리한 세트면 아무것도 바꾸지 않는다(멱등).
5. 형식이 깨진 이벤트(없는 세트·세트에 없는 사진·3×3이 아닌 행렬·역행렬 없음)는 재시도하지 않고 DLX로 보낸다.

### 이야기 3 — 판정 불가 면 (P1, FR-CAP-04)

1. 면마다, 그 면을 찍은 카메라(CAM1·CAM2·PHONE)별로 정합에 성공하고 품질이 통과한 사진이 한 장 이상 있어야 판정 가능하다. 아니면 판정 불가.
2. 건너뛴 선택 면(`skippedFaces`)은 판정 불가.
3. 판정 불가 면의 흠집은 비교하지 않고(match null) 등급 계산에서 뺀다. 결과에는 판정 불가 면 목록과 사유 문장이 남는다.
4. 판정 가능한 면이 하나도 없으면 등급을 매기지 않는다(세트 quality UNJUDGEABLE, 상태 INSPECTED에 머묾, 이벤트 없음). 다시 찍어야 한다.

### 이야기 4 — 바로 앞 시점과 비교 (P1, FR-INS-03)

1. 비교 대상은 같은 렌탈에서 등급이 매겨진 바로 앞 시점(다른 checkpointId 중 가장 최근)의 세트다. 수령 ↔ 출고, 반납 ↔ 수령. 출고는 기준선이라 짝을 만들지 않는다.
2. C의 `ViewComparator(MatchRule.V1)`로 비교하고, 판정마다 `inspection.defect_match`에 상태·짝·거리·규칙 버전(`view-match-v1`)을 남긴다.
3. 판정 가능 면은 지금과 이전 시점 모두에서 판정 가능해야 비교한다(모듈 규칙).

### 이야기 5 — A~D 등급 (P1, FR-INS-04)

1. 판정 가능 면의 흠집을 같은 면에서 5 mm 안이면 하나로 묶는다(같은 흠집이 CAM1·CAM2·조명별 사진에서 여러 번 탐지됨).
2. 오염(stain)은 등급에서 뺀다(근거 문장에는 몇 개 뺐는지 남긴다).
3. `grade-rules-v1`: 균열 1개 이상 또는 찍힘 3개 이상 → D, 찍힘 1~2개 또는 긁힘 5개 이상 또는 30 mm 이상 긁힘 → C, 긁힘 1~4개(모두 30 mm 미만) → B, 흠집 없음 → A.
4. 등급은 지금 기기 외관 전체로 매기고, 근거 문장에 신규 흠집(종류·개수·최대 길이)을 따로 적는다.
5. 결과는 `inspection.grade_result`(시점당 하나)에 등급·규칙 버전·근거·판정 불가 면·세트로 저장하고, 세트는 GRADED가 된다.
6. `trace.checkpoint.graded`를 낸다: checkpointType, grade, ruleVersion, newDefectIds(비교에서 NEW인 흠집 ID), unjudgeableFaces, photos(올라간 사진 ID·SHA-256).

### 이야기 6 — 검사 결과 조회 (P1)

`GET /api/v1/captures/checkpoints/{checkpointId}/inspection`

1. ADMIN은 모두, CUSTOMER는 토큰 `rentals`에 그 시점의 렌탈이 있을 때만(아니면 403 `OUT_OF_SCOPE`). 촬영 JWT는 403 `FORBIDDEN`(captureToken false).
2. 등급이 있으면 그 세트의 흠집(표준 좌표가 있는 것만)과 매칭·등급·규칙 버전·근거, 아직이면 grade·ruleVersion null, reasons 빈 배열, 지금 있는 흠집.
3. 그 시점의 세트가 없으면 404.

## 정한 것 (clarify, 추천안)

| 질문 | 정한 답 | 이유 |
|---|---|---|
| 시점 종류(OUT·RCV·RET)를 어떻게 아나 | 폰 세트 = RCV, 부스 세트는 같은 렌탈에 등급 난 앞 시점이 있으면 RET, 없으면 OUT | 서버끼리 REST를 부르지 않고, 세트 생성 계약에 종류가 없다. rental-server가 시점 순서(앞 시점 SEALED 뒤 다음 시점)를 지키므로 추론이 맞는다. 계약 변경 없음 |
| 부스 탐지와 정합 중 먼저 온 쪽 | 둘 다 와야 등급. 정합만 오면 INSPECTED에서 탐지를 기다리고, 탐지가 오면 바로 등급 | Jetson 탐지는 업로드 뒤 따로 돌아 정합보다 늦을 수 있다. 탐지 없이 매기면 흠집 없는 A가 잘못 나온다. 흠집이 0개여도 부스는 빈 목록을 보낸다(BO2) |
| 판정 불가 면 기준 | 면 × 카메라마다 정합·품질 통과 사진 1장 이상 | 부스 위 카메라 2대가 면의 앞 반·뒤 반을 나눠 찍는다. 조명 하나 실패로 면 전체를 버리지 않는다 |
| 등급 대상 | 기기 외관 전체(신규 + 기존), 근거에 신규를 따로 | "외관으로 A~D"(FR-INS-04), 출고에도 등급이 있다 |
| 같은 흠집 중복 | 면별 5 mm(view-match-v1와 같은 거리) 안이면 하나 | 사진 여러 장의 같은 흠집을 여러 개로 세지 않는다 |
| 길이·넓이(부스) | 표준 좌표 polygon에서: 길이 = 꼭짓점 사이 최대 거리, 넓이 = 신발끈 공식, 1 px = 0.25 mm | 부스 탐지 계약에 길이 칸이 없다 |
| 판정 가능 면 0개 | 등급 없이 INSPECTED + UNJUDGEABLE | A로 오인하지 않게(헌법 III) |
| 같은 시점에 세트가 또 등급 남(재촬영) | grade_result를 새 세트로 바꾸고 이벤트를 다시 낸다 | 시점당 등급 하나(DDL UNIQUE), rental-server는 앞으로만 간다 |
| 정합 안 된 사진의 흠집 | polygon null로 두고 비교·등급·조회에서 뺀다 | 표준 좌표 없이 비교할 수 없다 |

## DDL 변경 (V2, docs ddl.md·V1__init.sql에도 반영)

- `inspection.defect.photo_polygon jsonb NOT NULL` 추가, `polygon` NULL 허용(정합 뒤 채움)
- `inspection.defect_match.rule_version varchar(32) NOT NULL` 추가
- `inspection.grade_result.capture_set_id bigint REFERENCES capture.capture_set(id)`, `unjudgeable_faces jsonb NOT NULL DEFAULT '[]'` 추가
- `capture.capture_set.detections_received_at timestamptz` 추가(부스 탐지를 받았는지)

## 후속: 3D 트윈 보기 URL (FR-RPT-01)

- `GET /api/v1/captures/checkpoints/{checkpointId}/media`는 검사 결과 조회와 같은 범위(ADMIN·본인 렌탈 고객 로그인)로, 정합 이벤트의 `textureKeys`(버킷 `trace-derived`, `MINIO_DERIVED_BUCKET`)를 면마다 사전 서명 GET URL(`app.storage.view-ttl`, 10분)로 준다. 공개 주소(`MINIO_PUBLIC_BASE`)가 있으면 앞부분만 바꾼다.
- 시점이 없으면 404, 남의 렌탈이면 403. 텍스처가 없는 면은 목록에 없다.

## 범위 밖

흠집 크롭 이미지(만드는 곳이 아직 없어 `/media`의 crops는 빈 목록), 사람 수정(HUMAN) 흠집, 등급 재계산 API, LightGlue 보정(ML2).

## 성공 기준

- 수용 기준마다 시험 이름이 그 문장이다. `./mvnw verify` 통과, 라인 커버리지 40% 이상.
- 출고 → 수령 → 반납 흐름 시험(실제 PostgreSQL)에서 반납에 새로 생긴 긁힘이 NEW, 원래 있던 흠집이 EXISTING, 등급·규칙 버전이 저장되고 이벤트가 나간다.
