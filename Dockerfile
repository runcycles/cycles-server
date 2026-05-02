# ---- Build stage ----
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app

# Copy source and build with Maven repository cache mount
# BuildKit caches ~/.m2/repository across builds so dependencies
# are only downloaded once, even when source files change
COPY cycles-protocol-service/ cycles-protocol-service/
RUN --mount=type=cache,target=/root/.m2/repository \
    mvn -f cycles-protocol-service/pom.xml clean package -DskipTests -B

# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-alpine

# Apply latest Alpine security patches over whatever ships in the upstream
# eclipse-temurin:21-jre-alpine layer. The temurin tag is a moving ref so a
# fresh build picks up older Alpine patch levels until temurin itself rebuilds;
# applying `apk upgrade` here closes that window every time we build.
#
# Concrete fix on this commit: gnutls 3.8.12-r0 → 3.8.13-r0 (CVE-2026-33845
# HIGH + 12 bundled gnutls CVEs all resolved by the same package bump).
RUN apk upgrade --no-cache

ARG APP_VERSION=0.0.0

LABEL org.opencontainers.image.title="cycles-server" \
      org.opencontainers.image.description="Cycles Protocol runtime authority server" \
      org.opencontainers.image.source="https://github.com/runcycles/cycles-server" \
      org.opencontainers.image.licenses="Apache-2.0" \
      org.opencontainers.image.version="${APP_VERSION}"

RUN addgroup -g 1000 appuser && adduser -D -u 1000 -G appuser appuser

WORKDIR /app
COPY --from=build /app/cycles-protocol-service/cycles-protocol-service-api/target/cycles-protocol-service-api-*.jar app.jar
RUN chown appuser:appuser app.jar

USER appuser
EXPOSE 7878

HEALTHCHECK --interval=15s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:7878/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
