FROM eclipse-temurin:23-jdk AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apt-get update && apt-get install -y maven && \
    mvn package -DskipTests

FROM eclipse-temurin:23-jre
WORKDIR /app
COPY --from=build /app/target/capi-core-*.jar app.jar
COPY config/config.yaml /capi/config/config.yaml
ENV CAPI_CONFIG_FILE=/capi/config/config.yaml
ENTRYPOINT exec java -XX:+UseG1GC \
                     -XX:MaxGCPauseMillis=100 \
                     -Xms512m -Xmx512m \
                     -XX:+HeapDumpOnOutOfMemoryError \
                     -XX:HeapDumpPath=/capi/logs/heap-dump.hprof \
                     -jar app.jar