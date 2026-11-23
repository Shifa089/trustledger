# risk-aml-service image (M14, ADR 0022). Build from dev/ (the context needs the parent pom and event-contracts):
#   docker build -f risk-aml-service/Dockerfile -t trustledger/risk-aml-service:local .
#
# Same structure as core-app/Dockerfile: Maven wrapper build stage, layered jar, JRE-only run stage, non-root user,
# HEALTHCHECK on /actuator/health, base images pinned by digest.

ARG BUILD_IMAGE=eclipse-temurin:25-jdk-noble@sha256:4ddc1a8bdaf283421df5487411aff10b797ccf1e06a339041005114c8f489b5c
ARG RUN_IMAGE=eclipse-temurin:25-jre-noble@sha256:f3dd0838e69b881cc382237d5901e74ae3705b1056ad964b2e0e523b174fef99

FROM ${BUILD_IMAGE} AS build
WORKDIR /workspace

COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
COPY event-contracts/pom.xml event-contracts/
COPY core-app/pom.xml core-app/
COPY risk-aml-service/pom.xml risk-aml-service/
COPY event-contracts/src/ event-contracts/src/
COPY risk-aml-service/src/ risk-aml-service/src/

RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -pl risk-aml-service -am package -Dmaven.test.skip=true \
    && cp risk-aml-service/target/risk-aml-service-*.jar application.jar \
    && java -Djarmode=tools -jar application.jar extract --layers --destination extracted

FROM ${RUN_IMAGE}

LABEL org.opencontainers.image.title="trustledger-risk-aml-service" \
      org.opencontainers.image.description="TrustLedger risk and AML service" \
      org.opencontainers.image.source="https://github.com/Shifa089/trustledger"

RUN groupadd --system --gid 10001 trustledger \
    && useradd --system --uid 10001 --gid trustledger --no-create-home --home-dir /nonexistent \
       --shell /usr/sbin/nologin trustledger

WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./

USER 10001:10001

ENV SERVER_PORT=8081 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Duser.timezone=UTC"
EXPOSE 8081

HEALTHCHECK --interval=15s --timeout=5s --start-period=120s --retries=3 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT} && printf 'GET /actuator/health HTTP/1.1\\r\\nHost: localhost\\r\\nConnection: close\\r\\n\\r\\n' >&3 && grep -q '\"status\":\"UP\"' <&3"]

ENTRYPOINT ["java", "-jar", "application.jar"]
