FROM eclipse-temurin:23-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn package -DskipTests

FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=build /app/target/capi-core-1.0-SNAPSHOT.jar app.jar
COPY config/config.yaml /capi/config/config.yaml
ENV CAPI_CONFIG_FILE=/capi/config/config.yaml
ENTRYPOINT exec java -XX:InitialHeapSize=512m \
                     -XX:+UseG1GC \
                     -XX:MaxGCPauseMillis=100 \
                     -XX:+ParallelRefProcEnabled \
                     -XX:+HeapDumpOnOutOfMemoryError \
                     -XX:HeapDumpPath=/capi/logs/heap-dump.hprof \
                     -jar app.jar