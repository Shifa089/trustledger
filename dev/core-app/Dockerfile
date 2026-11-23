# core-app image (M14, ADR 0022). Build from dev/ (the context needs the parent pom and event-contracts):
#   docker build -f core-app/Dockerfile -t trustledger/core-app:local .
#
# Stage 1 builds the Spring Boot jar with the Maven wrapper and splits it into layers; stage 2 is the JRE only,
# runs as a non-root user and copies the layers from the least to the most often changed, so a code change
# rebuilds and pushes only the small "application" layer. Base images are pinned by digest (like the scanners in
# ci.yml): a tag can be moved to other content, a digest can't. To update, pull the tag and take its new digest.

ARG BUILD_IMAGE=eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c
ARG RUN_IMAGE=eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

# Poms first: the layers above "COPY ... src" stay cached while only code changes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY event-contracts/pom.xml event-contracts/
COPY core-app/pom.xml core-app/
COPY risk-aml-service/pom.xml risk-aml-service/
COPY event-contracts/src/ event-contracts/src/
COPY core-app/src/ core-app/src/

# The cache mount keeps ~/.m2 between local builds without putting it into a layer. Tests ran in "./mvnw verify"
# before any image is built (CI: the images job needs verify), so they are not even compiled here.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl core-app -am package -Dmaven.test.skip=true \
    && cp core-app/target/core-app-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM ${RUN_IMAGE}

LABEL org.opencontainers.image.title="trustledger-core-app" \
      org.opencontainers.image.description="TrustLedger core-app (modular monolith)" \
      org.opencontainers.image.source="https://github.com/Shifa089/trustledger"

# Fixed non-root user without a shell or home. The application files stay owned by root, so the process can read
# but not change its own code.
RUN groupadd --system --gid 10001 trustledger \
    && useradd --system --uid 10001 --gid trustledger --no-create-home --home-dir /nonexistent \
       --shell /usr/sbin/nologin trustledger

WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001

# SERVER_PORT is also Spring's server.port, so the app and the health check can't disagree on the port.
# UTC as in every other JVM of the project (parent pom); heap sized from the container's memory limit.
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8080

# The JRE image has no curl or wget, and installing one only for this adds packages to patch. Plain HTTP over
# bash's /dev/tcp instead (like the Keycloak check in infra/docker-compose.yml). /actuator/health is public and
# answers {"status":"UP"} only when the database, Redis and the other indicators are up.
HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT} && printf 'GET /actuator/health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "application.jar"]
