# 명세: 부스 빠른 검사 세트(위·아래 2면) — capture-server 구현

- 계약 원본: GAKDO/contracts `specs/005-booth-quick-set/spec.md` (contracts #20)
- 날짜: 2026-11-03, 요청: 11/3 강사님 리뷰 → 최소 MVP 재조정(민서 승인)
- 요구사항: FR-CAP-01, FR-INS-01, FR-INS-04, NFR-01

## 구현

- 세트 생성 `profile`(FULL·QUICK, 빼면 FULL). QUICK + PHONE은 400(profile). Flyway V4 `capture_set.profile`.
- 완료: QUICK은 codes.yaml `capture.face_required_quick.BOOTH`(TOP·BOT)로 빠진 면 검사.
- 등급: QUICK은 완료(UPLOADED) + 부스 탐지가 있으면 정합 없이 바로 GRADED. 탐지가 먼저 왔으면 완료 때(QuickSetCompleted →
  QuickGradeListener), 완료가 먼저면 탐지 저장 때 매긴다. 시점 비교 없음, 길이 모름, 핀 없음.
- 흠집 묶기: 같은 면·같은 카메라 사진끼리 원본 px 20 px 안이면 하나(조명 L1·L2·L3에 함께 잡힌 같은 흠집을 한 번만 셈).
- 조회: `/inspection` 흠집에 `coordinateSpace=PHOTO`·`camera`·`light`, polygon은 원본 px. `/media`에 `photos`(원본 사진 보기 URL).

## 수용 기준(시험)

- QuickSetDomainTest: profile 없이 만든 부스 세트는 8면 필요, QUICK은 TOP·BOT 사진만으로 완료, BOT 빠지면 409, 폰 QUICK 400.
- QuickGradingTest: 흠집 0개면 A, 긁힘 2개 B, 찍힘 C, 탐지 전·완료 전에는 등급 없음, L1·L2·L3 같은 자리 흠집은 한 번만.

## 리뷰 반영 (11/3, contracts #20 CodeRabbit)

- 빠른 검사 필수 면(TOP·BOT)은 CAM1·CAM2 사진이 모두 올라와야 완료된다. 한 카메라만 있으면 409 CAPTURE_FACES_MISSING(QuickSetDomainTest).
- 빠른 검사 등급 이벤트는 `compared: false`(앞 시점과 비교하지 않음). 정합 세트는 `compared: true`.
