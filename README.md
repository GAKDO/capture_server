# capture-server

TRACE(노트북 렌탈 상태 기록 서비스)의 **capture-server**: 촬영 세트·사전 서명 URL·검사 결과·시점 비교·등급

- 담당: A
- 설계: [GAKDO/docs](https://github.com/GAKDO/docs) — 요구사항·아키텍처·ERD·REST API v0
- 기술: Java 21, Spring Boot 3.5.7, Maven

## 실행

```bash
./mvnw spring-boot:run
```

```bash
curl http://localhost:8080/api/v1/captures/ping
```

## 테스트

```bash
./mvnw verify
```

줄 커버리지 40% 미만이면 실패합니다(블로그와 같은 기준).

## CI/CD

| 워크플로 | 언제 | 하는 일 |
|---|---|---|
| `backend-ci` | PR, develop push | 테스트 + JaCoCo 40% + (켜져 있으면) 학교 SonarQube |
| `배포` | PR(빌드만), develop·main push | 이미지 → GHCR(`ghcr.io/<조직>/capture-server`). main이면 gitops 레포 이미지 태그 갱신 → Argo CD 배포 |
| `비밀값 검사` | PR, main·develop push | 학교 공용 비밀번호 패턴 + gitleaks |
| `release` | main에 CHANGELOG 버전 | 태그와 GitHub Release |
| `PR 알림`·`CI 결과 알림` | PR·CI 끝 | Discord·텔레그램 (변수로 켬) |

브랜치 규칙과 릴리스 절차는 [CONTRIBUTING.md](CONTRIBUTING.md)에 있습니다.
