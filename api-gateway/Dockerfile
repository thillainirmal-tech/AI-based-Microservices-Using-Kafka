# =============================================================
# Dockerfile — api-gateway
# Multi-stage build: Maven build → lightweight JRE runtime
# Build context: fraud-detection-system/ (project root)
# =============================================================

# ── Stage 1: Build ────────────────────────────────────────────
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /build

# Install Maven
RUN apk add --no-cache maven

# Copy parent pom + all child poms first (layer cache for deps)
COPY pom.xml .
COPY common-dto/pom.xml         common-dto/pom.xml
COPY api-gateway/pom.xml        api-gateway/pom.xml
COPY auth-service/pom.xml       auth-service/pom.xml
COPY bank-service/pom.xml       bank-service/pom.xml
COPY transaction-service/pom.xml    transaction-service/pom.xml
COPY fraud-detection-service/pom.xml fraud-detection-service/pom.xml
COPY notification-service/pom.xml   notification-service/pom.xml

# Pre-download dependencies for api-gateway + common-dto only
RUN mvn dependency:go-offline -pl api-gateway -am -q --no-transfer-progress 2>/dev/null || true

# Copy sources
COPY common-dto/ common-dto/
COPY api-gateway/ api-gateway/

# Build (skip tests — tests run in CI pipeline separately)
RUN mvn package -pl api-gateway -am -DskipTests --no-transfer-progress

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy built JAR from builder stage
COPY --from=builder /build/api-gateway/target/api-gateway-1.0.0.jar app.jar

RUN chown appuser:appgroup app.jar
USER appuser

# Expose service port
EXPOSE 8085

# JVM tuning for containers: respect cgroup memory limits
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+UseG1GC", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD wget -qO- http://localhost:8085/actuator/health || exit 1
