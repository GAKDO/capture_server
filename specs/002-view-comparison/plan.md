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

## A가 붙일 때 (연결 메모, 11/2 A 답 반영)

1. 이전 시점 = 같은 렌탈의 바로 앞 시점. 수령은 출고와, 반납은 수령과 비교한다. 출고는 기준선이라 `compare(null, now)`는 빈 결과(짝 없음)를 돌려준다.
2. `ViewSnapshot(defects, judgeableFaces)`: defects는 `DefectObservation.fromStandardPx(public_id, photo.face, class, polygon)`로 만든다. DB `inspection.defect.polygon`은 표준 좌표 px이고(1 px = 0.25 mm), 모듈 안에서 mm로 바꿔 거리를 잰다. judgeableFaces는 판정 불가가 아닌 면.
3. `new ViewComparator(MatchRule.V1).compare(prev, now)` → `DefectMatch`를 `inspection.defect_match`에 한 행씩. `distance_mm`은 mm 그대로, `rule_version`(A가 CA2 마이그레이션에서 만드는 varchar 32 필수 칸)에는 `ComparisonResult.ruleVersion`을 넣는다. `unjudgedDefectIds`는 행을 만들지 않는다.
4. 5 mm 문턱 = 표준 좌표 20 px.
