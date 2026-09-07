# ---------- 1단계: 빌드 ----------
FROM eclipse-temurin:25-jdk-noble AS builder

WORKDIR /workspace

# 의존성 정의 파일을 먼저 복사해 소스만 바뀔 때 의존성 다운로드 레이어를 재사용한다.
COPY backend/gradlew gradlew
COPY backend/gradle gradle
COPY backend/settings.gradle.kts backend/build.gradle.kts ./
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies

COPY backend/src src
RUN ./gradlew --no-daemon bootJar \
    && cp build/libs/*.jar /workspace/app.jar

# ---------- 2단계: 실행 ----------
FROM eclipse-temurin:25-jre-noble

WORKDIR /app

RUN groupadd --system spring && useradd --system --gid spring spring
USER spring:spring

COPY --from=builder --chown=spring:spring /workspace/app.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
