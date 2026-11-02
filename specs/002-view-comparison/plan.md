# 구현 계획: CA2 시점 비교 모듈

**명세**: [spec.md](spec.md) | **날짜**: 2026-11-02

## 기술 맥락

Java 21, Spring Boot 3.5.7(이 모듈은 스프링에 기대지 않는 순수 자바), JUnit 5 + AssertJ, JaCoCo 40%. Docker 없이 도는 단위 테스트만.

## 헌법 점검

| 원칙 | 판단 |
|---|---|
| I 계약 먼저 | codes.yaml 값(defect_class·match_status)을 enum 대조 테스트로 묶음. 계약 변경 없음 |
| II 서비스 경계 | capture-server 안 `comparison` 패키지. DB·이벤트 없음 |
| III 기록 불변 | 판정마다 규칙 버전. 값을 바꾸면 새 버전(v2)을 만든다 |
| IV 테스트 | 수용 기준 문장을 `@DisplayName`으로, 정확도 테스트는 고정 시드 |
| VI 단순 | 탐욕적 최근접 짝짓기(헝가리안 없음). 한 면 흠집 수십 개 규모 |

## 구조

```
src/main/java/com/gakdo/trace/capture/comparison/domain/
  Point · DefectClass · MatchStatus · MatchRule(V1) · DefectObservation · ViewSnapshot
  DefectMatch · ComparisonResult · Shapes(polygon 거리) · ViewComparator · MatchAccuracy
src/test/java/com/gakdo/trace/capture/comparison/
  ViewComparatorTest · TestScratchAccuracyTest
```

`Face`는 CA1의 `captureset.domain.Face`를 그대로 쓴다(읽기만).

## A가 붙일 때 (연결 메모)

1. 이전 시점 = 같은 렌탈에서 지금보다 앞선 시점 중 가장 최근 것(출고 → 수령 → 반납).
2. `ViewSnapshot(defects, judgeableFaces)`: defects는 `inspection.defect`의 `public_id`·`photo.face`·`class`·`polygon`, judgeableFaces는 판정 불가가 아닌 면.
3. `new ViewComparator(MatchRule.V1).compare(prev, now)` → `DefectMatch`를 `inspection.defect_match`에 한 행씩. `unjudgedDefectIds`는 행을 만들지 않는다.
4. `ruleVersion`은 DDL에 칸이 없다. `grade_result.reasons`에 `matchRuleVersion`으로 넣거나 칸을 더할지 A가 정한다.
5. polygon 단위는 mm(이벤트 스키마). 펼친 이미지 px로 저장했다면 4로 나눠 넘긴다(1 px = 0.25 mm).
