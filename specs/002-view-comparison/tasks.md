# 작업: CA2 시점 비교 모듈

- [x] T001 [P] `DefectClass`·`MatchStatus`와 codes.yaml 대조 테스트
- [x] T002 [P] `Shapes.gap`(겹침 0, 안쪽 0, 떨어지면 최근접 거리) 테스트 → 구현
- [x] T003 `MatchRule.V1`(view-match-v1, 5 mm, 종류 벌점 2 mm, 오염은 오염끼리)
- [x] T004 `ViewComparator` 테스트(기존·신규·다른 면·부분·나뉨·종류·판정 불가·첫 시점) → 구현
- [x] T005 `MatchAccuracy`(억울한 신규·놓친 신규·짝 틀림·빠짐) 테스트 → 구현
- [x] T006 고정 시드 테스트 흠집 정확도 테스트, 결과를 accuracy.md에 기록
- [x] T007 `mvn verify`(JaCoCo 40%) 통과
- [ ] T008 (A) 결과 저장·등급과 연결, 규칙 버전 저장 위치 결정
- [ ] T009 (B·C) 실제 기기 테스트 흠집으로 다시 재기
