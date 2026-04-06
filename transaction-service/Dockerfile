# =============================================================
# Dockerfile — transaction-service
# Multi-stage build: Maven build → lightweight JRE runtime
# Build context: fraud-detection-system/ (project root)
# =============================================================

# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

RUN apk add --no-cache maven

COPY pom.xml .
COPY common-dto/pom.xml         common-dto/pom.xml
COPY api-gateway/pom.xml        api-gateway/pom.xml
COPY auth-service/pom.xml       auth-service/pom.xml
COPY bank-service/pom.xml       bank-service/pom.xml
COPY transaction-service/pom.xml    transaction-service/pom.xml
COPY fraud-detection-service/pom.xml fraud-detection-service/pom.xml
COPY notification-service/pom.xml   notification-service/pom.xml

RUN mvn dependency:go-offline -pl transaction-service -am -q --no-transfer-progress 2>/dev/null || true

COPY common-dto/ common-dto/
COPY transaction-service/ transaction-service/

RUN mvn package -pl transaction-service -am -DskipTests --no-transfer-progress

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

RUN addgroup -S appgroup && adduser -S appuser -G appgroup

COPY --from=builder /build/transaction-service/target/transaction-service-1.0.0.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

EXPOSE 8081

ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8081/actuator/health || exit 1
