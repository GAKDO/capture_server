# 데이터 모델: CA1

원본은 GAKDO/docs `db/V1__init.sql`의 capture·inspection 절이다. CA1이 아래 칸을 더했고, docs `design/ddl.md`·`db/V1__init.sql`·`decisions.md`에도 같은 내용을 반영했다.

## capture.capture_set

| 칸 | 형식 | 비고 |
| --- | --- | --- |
| id | bigint IDENTITY | 내보내지 않음 |
| public_id | varchar(21) UNIQUE | captureSetId |
| rental_id | varchar(21) NOT NULL | **CA1 추가**. 이벤트 봉투 rentalId(값으로만) |
| checkpoint_id | varchar(21) NOT NULL | 값으로만 |
| source | BOOTH·PHONE | |
| booth_id | varchar(32) | BOOTH 토큰 scope |
| status | UPLOADING·UPLOADED·INSPECTED·GRADED | CA1은 앞 두 개 |
| quality | OK·UNJUDGEABLE | CA1은 OK |
| skipped_faces | jsonb `{면: 사유}` | |
| completion_key | varchar(128) | **CA1 추가**. complete의 Idempotency-Key |
| completion_request_sha256 | char(64) | **CA1 추가**. 정규화한 완료 본문의 해시 |
| completion_response | jsonb | **CA1 추가**. 첫 202 응답 |

## capture.photo

| 칸 | 형식 | 비고 |
| --- | --- | --- |
| public_id | varchar(21) | 이벤트 photoId |
| face·light·camera | codes.yaml CHECK | UNIQUE(capture_set_id, face, light, camera) |
| object_key | varchar(512) | `captures/{setId}/{face}-{light}-{camera}.{jpg\|png}` |
| sha256 | char(64) | 알린 값 = 확인한 값 |
| uploaded | boolean DEFAULT false | **CA1 추가**. complete에서 확인되면 true |
| quality_ok | boolean DEFAULT true | |
| taken_at | timestamptz | |

inspection.* 테이블은 V1에 함께 만들지만 CA1은 쓰지 않는다(CA2).

## 상태 전이

UPLOADING --complete(해시·필수 면 통과)--> UPLOADED --(CA2 검사)--> INSPECTED --> GRADED
