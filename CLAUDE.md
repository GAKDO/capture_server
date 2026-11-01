# capture_server

TRACE의 촬영 세트·사전 서명 URL·검사 결과·등급 레포입니다. 담당: A (CA2의 시점 비교는 C와 함께). 이 레포의 기능: CA1, CA2 (GAKDO/docs `speckit/backlog.md`).

## 먼저 읽을 것

- 헌법: `.specify/memory/constitution.md` (모든 명세·계획·구현의 기준. 원본은 GAKDO/docs `speckit/constitution.md`)
- 기능 목록과 붙여 넣을 설명: GAKDO/docs [`speckit/backlog.md`](https://github.com/GAKDO/docs/blob/main/speckit/backlog.md)
- 설계서: [`design/mvp-design.md`](https://github.com/GAKDO/docs/blob/main/design/mvp-design.md), REST API: [`design/rest-api-v0.md`](https://github.com/GAKDO/docs/blob/main/design/rest-api-v0.md), 테이블: [`design/ddl.md`](https://github.com/GAKDO/docs/blob/main/design/ddl.md), 결정: [`decisions.md`](https://github.com/GAKDO/docs/blob/main/decisions.md)
- 계약 원본: [GAKDO/contracts](https://github.com/GAKDO/contracts) (OpenAPI·JSON Schema·`codes.yaml`)

## 명령

- 빌드·테스트: `./mvnw verify` (JaCoCo 40% 이상)
- 실행: `./mvnw spring-boot:run` (PostgreSQL·RabbitMQ 주소는 환경 변수)

## Spec Kit으로 기능 하나 만들기

1. `git switch develop && git pull`
2. `/speckit-specify` + backlog.md의 설명 → `feature/<번호>-<이름>` 브랜치와 `specs/<번호>-<이름>/spec.md`
3. `/speckit-clarify` → 질문에 답하기
4. `/speckit-plan` + 아래 "계획에 줄 기술 설명"
5. `/speckit-tasks` → `/speckit-analyze` → `/speckit-implement` → `/speckit-converge` (Converged까지 반복)
6. 로컬 검사가 통과하면 PR을 `develop`으로. 본문에 요구사항 ID를 적고 `specs/` 문서도 함께 올린다

## 계획에 줄 기술 설명

`/speckit-plan` 뒤에 그대로 붙여 넣습니다.

```
Java 21, Spring Boot 3.5.7, Maven(./mvnw). Spring Web MVC, Spring Data JPA, PostgreSQL 스키마 capture·inspection, Flyway(src/main/resources/db/migration, 이 서버 스키마만, GAKDO/docs db/V1__init.sql의 해당 절에서 시작), Spring Security OAuth2 리소스 서버(auth-server JWKS 검증), MinIO 사전 서명 URL은 AWS SDK v2 S3 Presigner(버킷 trace-captures), Spring AMQP(trace.events), Bean Validation, ProblemDetail(RFC 9457). 테스트는 JUnit 5 + Spring Boot Test + Testcontainers(PostgreSQL·RabbitMQ), JaCoCo 라인 커버리지 40% 이상. Claude 클라우드 세션에는 Docker가 없으므로 Testcontainers 테스트는 @Testcontainers(disabledWithoutDocker = true)로 두어 세션에서는 건너뛰고 GitHub Actions에서 돌게 하며, 도메인 로직은 Docker 없이 도는 단위 테스트로도 덮는다. 패키지는 com.gakdo.trace.capture 아래 기능별. 기존 ping·application.yml·Dockerfile을 유지하고, 주소·비밀은 환경 변수로 받는다(gitops infra.env, trace-secrets의 이름).
```

## 팀 운영 (모든 세션)

- **세션을 시작하면 먼저 `/trace-standup start`, 끝내기 전에 `/trace-standup end`.** 보드와 어제·오늘 기록을 읽고, 오늘 할 일·한 일·남은 것·막힌 것을 GAKDO/docs `team/`에 적는다.
- 세션에 이 레포와 함께 **GAKDO/docs를 연결**하면 바로 읽고 쓸 수 있다.
- 남은 작업은 `team/board.md`, 코드 컨벤션 전체는 `team/conventions.md`. 아래는 이 레포에 해당하는 요약이다.

## 코드 컨벤션 요약

- 패키지는 `com.gakdo.trace.<서비스>.<기능>` 아래 `api`·`application`·`domain`·`infra`.
- DTO는 `record`(`XxxRequest`·`XxxResponse`), 엔티티를 API로 내보내지 않음. 생성자 주입, `@Transactional`은 application에만.
- 도메인 예외 → `@RestControllerAdvice` 한 곳에서 Problem Details. Flyway는 새 파일만 더함(`V<번호>__<설명>.sql`).
- 테스트 이름은 수용 기준 문장. Testcontainers는 `@Testcontainers(disabledWithoutDocker = true)`.
- 커밋 `<종류>(<범위>): <내용>`, PR 하나 = 백로그 기능 하나(코드 400줄 넘으면 나눔), PR 본문에 백로그 ID·요구사항 ID·테스트 방법.

## 꼭 지킬 것

- 계약(API·이벤트·진단 JSON·코드 값)을 바꿔야 하면 코드를 고치지 말고 멈추고 사람에게 알린다.
- 비밀번호·토큰·키·학번을 코드·문서·커밋에 넣지 않는다. 설정은 환경 변수로 받는다.
- 커밋은 `<종류>(<범위>): <내용>`(feat·fix·refactor·test·docs·chore·ci), 문서·PR·주석은 한국어.
- 레포 이름은 `_server`, 클러스터 안 서비스·이미지 이름은 `-server`(예: `auth-server`).
