# capture-server 컨테이너 이미지. (블로그 deploy/docker/Dockerfile에서 화면 빌드를 뺀 서버용)
# 빌드: docker build -t capture-server .
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /src
# 의존성을 먼저 받아 두면 코드만 바뀔 때 이 계층을 다시 쓴다
COPY mvnw pom.xml ./
COPY .mvn/ .mvn/
RUN ./mvnw -q -B dependency:go-offline
COPY src/ src/
RUN ./mvnw -q -B -DskipTests package && cp "$(ls target/*.jar | grep -v original | head -1)" /app.jar
# 계층 분리: 의존성 계층이 캐시되어 코드만 바뀌면 이미지 전송량이 작다
RUN mkdir /layers && cd /layers && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination .

FROM eclipse-temurin:21-jre
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
# 배포 워크플로가 커밋 SHA를 넘긴다. 앱은 app.commit으로 읽어 /ping에 싣는다(앞 계층 캐시를 깨지 않게 맨 끝에 둔다)
ARG APP_COMMIT=unknown
ENV APP_COMMIT=$APP_COMMIT
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
