FROM eclipse-temurin:23-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn package -DskipTests

FROM eclipse-temurin:23-jre
RUN mkdir -p /capi/config /capi/logs && \
    chown -R 1000:0 /capi /app
WORKDIR /app
COPY --from=build --chown=1000:0 /app/target/capi-core-*.jar app.jar
COPY --chown=1000:0 config/config.yaml /capi/config/config.yaml
ENV CAPI_CONFIG_FILE=/capi/config/config.yaml
USER 1000
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-Xms512m", "-Xmx512m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/capi/logs/heap-dump.hprof", "-jar", "app.jar"]