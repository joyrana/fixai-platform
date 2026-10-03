# Runtime image for a Spring Boot service. The jar is built by Maven first (CI: `mvn -B package`), which keeps
# dependency resolution in one place (Maven, with its own proxy and mirror settings) instead of inside docker build.
FROM eclipse-temurin:21-jre-alpine

RUN addgroup -S fixai && adduser -S -G fixai -H -s /sbin/nologin fixai
WORKDIR /app
ARG JAR_FILE
COPY --chown=fixai:fixai ${JAR_FILE} /app/app.jar

USER fixai
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=60s --retries=6 \
  CMD wget -qO- "http://127.0.0.1:${SERVER_PORT}/actuator/health/readiness" | grep -q '"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
