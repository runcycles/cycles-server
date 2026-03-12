FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="cycles-server" \
      org.opencontainers.image.description="Cycles Protocol budget authority server" \
      org.opencontainers.image.source="https://github.com/runcycles/cycles-server" \
      org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app
COPY cycles-protocol-service/cycles-protocol-service-api/target/cycles-protocol-service-api-*.jar app.jar

EXPOSE 7878
ENTRYPOINT ["java", "-jar", "app.jar"]
