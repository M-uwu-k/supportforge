# syntax=docker/dockerfile:1.7

FROM maven:3.9.11-eclipse-temurin-21-alpine AS builder

WORKDIR /workspace

COPY pom.xml ./

COPY src ./src
COPY skills ./skills

RUN --mount=type=cache,target=/root/.m2 \
    mvn -B --no-transfer-progress -Dmaven.test.skip=true clean package


FROM eclipse-temurin:21-jre-alpine AS production

ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="SupportForge" \
      org.opencontainers.image.description="Enterprise AI customer support service" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}"

WORKDIR /app

RUN addgroup -S supportforge \
    && adduser -S supportforge -G supportforge \
    && mkdir -p /app/data /app/skills \
    && chown -R supportforge:supportforge /app

COPY --from=builder \
     --chown=supportforge:supportforge \
     /workspace/target/supportforge-*.jar \
     /app/app.jar

COPY --from=builder \
     --chown=supportforge:supportforge \
     /workspace/skills \
     /app/skills

USER supportforge

ENV SERVER_PORT=7000 \
    SUPPORTFORGE_SKILLS_ROOT=/app/skills \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.io.tmpdir=/tmp"

EXPOSE 7000

HEALTHCHECK \
    --interval=30s \
    --timeout=5s \
    --start-period=30s \
    --retries=3 \
    CMD wget -q --spider \
        http://localhost:7000/actuator/health/readiness \
        || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
