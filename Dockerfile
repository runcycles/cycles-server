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
ENTRYPOINT ["java", "-jar", "app.jar"]
