# 변경 기록

이 레포의 버전별 변경을 적는다. 형식은 [Keep a Changelog](https://keepachangelog.com/ko/1.1.0/)를 따른다.
기능 PR은 `## [Unreleased]` 아래에 한 줄을 더하고, 릴리스 PR에서 `## [x.y.z] - 날짜`로 바꾼다(CONTRIBUTING.md 3번).
main에 버전이 적힌 CHANGELOG가 올라가면 release.yml이 태그와 GitHub Release를 만든다.

## [Unreleased]

### 변경

- CI: SonarQube 분석 뒤 품질 게이트 판정까지 기다려 실행 요약·경고·알림에 통과/실패를 남김(이미지 빌드·배포는 막지 않음)

### 추가
- 블로그(AIGJ-01-002-blog/devlog) CI/CD를 옮겨 초기 구성
