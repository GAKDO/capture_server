# 작업: CA2 A 몫

- [x] T001 `V2__inspection_results.sql` (photo_polygon·polygon NULL·rule_version·grade_result 세트·판정 불가 면·detections_received_at)
- [x] T002 순수 도메인: Homography(적용·역행렬·검사), PolygonMeasure(길이·넓이 mm), Judgeability, DefectGroups, GradeRules(grade-rules-v1), CheckpointKind
- [x] T003 단위 시험: 위 도메인 수용 기준 문장
- [x] T004 엔티티·리포지토리: Defect, FaceAlignment, DefectMatchRecord, GradeResult, CaptureSet 확장
- [x] T005 DetectionService + POST detections + 보안 규칙 + WebMvc 시험(201·400·403·404·409, 통째로 교체)
- [x] T006 InspectEvent·리스너·큐 선언·InspectionService(정합 저장, 부스 표준 좌표, 폰 흠집, 멱등, 형식 오류 DLX)
- [x] T007 GradingService(앞 시점·비교·매칭·묶기·등급·grade_result·이벤트) + CheckpointGradedEvent 발행
- [x] T008 GET inspection + 범위(ADMIN·본인 렌탈) + WebMvc 시험
- [x] T009 Testcontainers 흐름 시험: 출고 → 수령 → 반납, NEW·EXISTING·등급·이벤트
- [x] T010 `./mvnw verify`, docs ddl.md·V1__init.sql·decisions.md 반영, PR
