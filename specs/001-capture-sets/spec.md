# 기능 명세: CA1 촬영 세트·사전 서명 URL

**브랜치**: `claude/project-thread-aosr81` (Spec Kit 기본 `feature/001-capture-sets` 대신 세션 지정 브랜치)
**작성**: 2026-11-02
**상태**: 확정(clarify 반영)
**요구사항**: FR-CAP-02, FR-CAP-05 (세트 조회로 FR-CAP-01 일부)
**계약**: GAKDO/contracts develop `openapi/capture-server.yaml` 0.1.0(PR #6 PublicId 반영), `codes.yaml` 0.5.0(`capture.face_required` BOOTH 8면·PHONE 7면), `events/trace.capture.uploaded.schema.json` 1.0
**입력(backlog.md)**: "부스와 폰이 한 시점의 촬영 세트를 만들고, 사진마다 사전 서명 URL을 받아 MinIO trace-captures에 직접 올린 뒤 면·조명·카메라 코드와 SHA-256을 등록한다. 세트가 다 올라오면 trace.capture.uploaded 이벤트를 낸다(FR-CAP-02, FR-CAP-05). … 수용 기준: 게이트웨이 트래픽에 사진 본문이 없다. 내려받은 파일 해시와 저장된 해시가 같다. 세트 출처의 필수 면(codes.yaml capture.face_required, 부스는 TOP·BOT·SF·SB·SL·SR·KB·SCR0 8면)이 빠지면 세트가 완료되지 않는다(409). SCR1은 선택이고 건너뛸 때는 skippedFaces에 사유를 남긴다. 업로드 URL 응답은 사진마다 face·light·camera를 돌려준다(부스는 같은 면·조명을 CAM1·CAM2로 두 장 찍음). 폰의 필수 면은 TOP·BOT·SF·SB·SL·SR·SCR0 7면이다. … 필수 면이 빠지면 409 CAPTURE_FACES_MISSING(빠진 면 목록 포함)."

## 사용자 시나리오와 테스트

### 이야기 1 — 세트 만들고 사진 직접 올리기 (P1)

부스(BOOTH 토큰)나 고객 폰(촬영 JWT)이 한 시점의 사진 목록(면·조명·카메라·SHA-256·형식·찍은 시각)을 먼저 알리고, 사진마다 10분짜리 사전 서명 PUT URL을 받아 MinIO `trace-captures`에 직접 올린다.

**독립 시험**: `POST /captures/sets` → 응답 URL로 PUT → `GET /captures/sets/{setId}`에서 uploaded=true.

**수용 시나리오**
1. **주어진** 부스 토큰 **하면** source=BOOTH 세트를 만듦 **그러면** 201, captureSetId, `uploads`가 요청 `photos`와 같은 순서·같은 개수로 오고 사진마다 face·light·camera·method=PUT·url·headers·expiresAt(10분 뒤)이 있다(업로드 URL 응답은 사진마다 face·light·camera를 돌려준다).
2. **주어진** 부스가 같은 면·조명을 CAM1·CAM2로 두 장 알림 **그러면** 두 URL의 객체 키가 다르다.
3. **주어진** 응답 `headers` **하면** 그 헤더를 그대로 실어 PUT **그러면** MinIO가 받는다. headers에는 `Content-Type`과 `x-amz-checksum-sha256`(알린 SHA-256의 base64)이 있어, 알린 해시와 다른 파일은 저장소가 거절한다.
4. **주어진** 같은 세트 안에 (face, light, camera)가 겹침 **그러면** 400 VALIDATION_FAILED(`photos[i]`).
5. **주어진** source와 맞지 않는 카메라·조명(PHONE인데 CAM1, BOOTH인데 ROOM) **그러면** 400 VALIDATION_FAILED.
6. **주어진** 서버 API **그러면** 사진 본문을 받는 경로가 없다. 요청·응답은 JSON뿐이고 사진은 MinIO로만 간다(게이트웨이 트래픽에 사진 본문이 없다).

### 이야기 2 — 세트 완료와 이벤트 (P1)

모두 올린 뒤 `POST /captures/sets/{setId}/complete`로 완료한다. 서버는 MinIO에 올라간 사진의 해시를 확인하고, 출처별 필수 면이 있는지 본 뒤 `trace.capture.uploaded`를 낸다.

**수용 시나리오**
1. **주어진** 필수 면이 모두 올라간 세트 **하면** complete **그러면** 202, status UPLOADED, 사진 uploaded=true, `trace.events`에 라우팅 키 `trace.capture.uploaded` 이벤트 하나(봉투 rentalId·checkpointId, captureSetId, source, needDetection = source가 PHONE, photos[photoId·face·light·camera·objectKey·sha256], 건너뛴 면이 있으면 skippedFaces).
2. **주어진** 부스 세트에서 KB가 빠짐 **그러면** 409 CAPTURE_FACES_MISSING, `missingFaces: [KB]`, 세트는 UPLOADING 그대로(세트 출처의 필수 면이 빠지면 세트가 완료되지 않는다).
3. **주어진** 폰 세트에 KB가 없음 **그러면** 202(폰의 필수 면은 TOP·BOT·SF·SB·SL·SR·SCR0 7면이다).
4. **주어진** 필수 면의 사진 일부(예: TOP L2 CAM2)를 알렸지만 올리지 않음 **그러면** 그 면도 빠진 면으로 409.
5. **주어진** SCR1을 알렸지만 올리지 못함 **하면** `skippedFaces: {SCR1: "화면이 켜지지 않음"}` **그러면** 202, 이벤트에 SCR1 사진은 없고 skippedFaces가 있다(SCR1은 선택이고 건너뛸 때는 skippedFaces에 사유를 남긴다). 사유 없이 올리지 않으면 400 VALIDATION_FAILED(`skippedFaces.SCR1`).
6. **주어진** skippedFaces에 필수 면(부스 KB) **그러면** 400 VALIDATION_FAILED, `errors[].field = skippedFaces.KB`.
7. **주어진** 올라간 객체의 SHA-256이 알린 값과 다름 **그러면** 409 CAPTURE_HASH_MISMATCH.
8. **주어진** 완료된 세트 **하면** MinIO에서 사진을 내려받아 SHA-256을 계산 **그러면** DB·이벤트의 sha256과 같다(내려받은 파일 해시와 저장된 해시가 같다).
9. **주어진** 같은 Idempotency-Key·같은 본문으로 다시 complete **그러면** 처음 응답(202)을 그대로 돌려주고 이벤트는 다시 내지 않는다. 같은 키에 다른 본문이면 422 IDEMPOTENCY_KEY_CONFLICT, 다른 키로 완료된 세트를 다시 완료하면 409 CAPTURE_SET_CLOSED. 키가 없으면 400.

### 이야기 3 — 범위 확인 (P1)

gateway는 역할까지만 막으므로 시점·세트·부스 범위는 서버가 토큰으로 다시 본다(decisions.md 11/2 GW1).

**수용 시나리오**
1. **주어진** 촬영 JWT(role CUSTOMER, scope=checkpointId, rentalId) **하면** 다른 checkpointId나 rentalId로 세트 생성 **그러면** 403 OUT_OF_SCOPE. source=BOOTH로 만들어도 403 OUT_OF_SCOPE.
2. **주어진** 부스 토큰(role BOOTH, scope=boothId) **하면** source=PHONE으로 만듦 **그러면** 403 OUT_OF_SCOPE. 만든 세트의 boothId는 토큰의 scope다.
3. **주어진** 다른 부스·다른 시점의 세트 **하면** complete **그러면** 403 OUT_OF_SCOPE.
4. **주어진** scope 없는 일반 CUSTOMER 토큰 **하면** 세트 생성·완료·조회 **그러면** 403 FORBIDDEN(촬영 JWT만 쓸 수 있다).
5. **주어진** ADMIN **하면** `GET /captures/sets/{setId}` **그러면** 어느 세트든 200. 촬영 JWT는 자기 시점 세트만, 아니면 403 OUT_OF_SCOPE. 없는 세트는 404.
6. **주어진** 토큰 없음 **그러면** 401 UNAUTHENTICATED Problem Details. `/api/v1/captures/ping`은 토큰 없이 200.

### 경계 상황
- MinIO에 닿지 않으면 complete는 500 INTERNAL_ERROR이고 세트는 UPLOADING 그대로라 다시 시도할 수 있다.
- 업로드 URL이 만료되면 세트를 새로 만들지 않고 `POST /captures/sets/{setId}/uploads`로 남은 사진의 URL만 다시 받는다(11/2 결정, contracts PR #8, FR-008).
- 한 시점에 세트를 여러 번 만들 수 있다(다시 찍기). rental-server는 uploaded 이벤트를 멱등으로 받는다.
- GET은 UPLOADING 세트에 한해 MinIO 존재 여부로 uploaded를 보여 준다(해시 확인은 complete에서만).

## 요구사항

- **FR-001**: 세트 생성은 사진 1~54장, 사진마다 면·조명·카메라(codes.yaml)·SHA-256(소문자 64자)·contentType(image/jpeg·image/png)·takenAt(오프셋 필수)을 받는다. 모르는 필드는 400.
- **FR-002**: 사진마다 버킷 `trace-captures`의 객체 키 `captures/{captureSetId}/{face}-{light}-{camera}.{jpg|png}`에 대한 10분 사전 서명 PUT URL을 만든다. 서명에 Content-Type과 x-amz-checksum-sha256을 넣는다.
- **FR-003**: complete는 사진마다 객체 존재와 SHA-256을 확인한다(저장소 체크섬, 없으면 내려받아 계산).
- **FR-004**: 필수 면 = codes.yaml `capture.face_required[source]`. 필수 면의 알린 사진이 모두 올라가지 않았거나 알리지 않았으면 409 CAPTURE_FACES_MISSING + missingFaces(codes.yaml 면 순서).
- **FR-005**: 완료 시 상태 UPLOADED, skipped_faces 저장, `trace.capture.uploaded`(schemaVersion 1.0)를 DB 트랜잭션과 함께 발행한다.
- **FR-006**: complete는 Idempotency-Key(8~128자)로 한 번만 처리한다.
- **FR-007**: 범위는 이야기 3대로 서버가 확인한다. 오류는 모두 Problem Details(code = codes.yaml error_code).
- **FR-008** (11/2 후속): `POST /captures/sets/{setId}/uploads`는 저장소에 아직 없는 사진에만 FR-002와 같은 10분 PUT URL을 새로 준다. 본문이 없거나 `photos`가 없으면 세트의 사진 전부, `photos`를 주면 그 사진만(응답은 세트 순서). 세트에 없는 (face, light, camera)는 400 VALIDATION_FAILED(`photos[i]`), 완료된 세트는 409 CAPTURE_SET_CLOSED, 권한·범위는 세트 생성과 같다(BOOTH 자기 부스, 촬영 JWT 같은 시점·렌탈). 응답 `uploads`는 세트 생성과 같은 형식이고 세트는 바뀌지 않는다.

## 범위 밖
- 부스 탐지 결과 저장(`/detections`), 검사 결과·등급(`/inspection`), 보기 URL(`/media`): CA2·ML2.
- 블러·반사 품질 검사(quality는 OK 고정): 폰은 WE2 화면에서, 부스는 ML2에서.

## Clarify (2026-11-02, 민서 "묻지 말고 진행" 지시로 추천안 채택)

1. 해시 확인 방법 → 사전 서명에 `x-amz-checksum-sha256`을 넣어 저장소가 다른 파일을 거절하게 하고, complete에서 HeadObject 체크섬으로 대조, 체크섬이 없으면 내려받아 계산.
2. 이벤트에 넣을 rentalId 보관 → `capture.capture_set.rental_id` 칸 추가(설계 DDL 보강, decisions.md).
3. 필수 면의 일부 사진만 올라간 경우 → 그 면은 빠진 면(부스 조명·카메라 조합이 하나라도 없으면 검사가 불완전).
4. 선택 면을 알렸는데 올리지 않음 → skippedFaces 사유 필수, 없으면 400.
5. Idempotency 저장 → 세트 행에 키·요청 해시·첫 응답(jsonb).
