FROM maven:3.9.9-eclipse-temurin-23 AS build
WORKDIR /app
# Resolve dependencies in their own layer so they are only re-fetched when pom.xml
# changes, not on every source edit. Note: dependency:go-offline is not exhaustive
# (it misses some plugin deps), so the package step below must stay online — do not add -o.
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B package -DskipTests

FROM eclipse-temurin:23-jre
WORKDIR /app
RUN mkdir -p /capi/config /capi/logs && \
    chown -R 1000:0 /capi /app
COPY --from=build --chown=1000:0 /app/target/capi-core-*.jar app.jar
COPY --chown=1000:0 config/config.yaml /capi/config/config.yaml
LABEL io.modelcontextprotocol.server.name="io.github.surisoft-io/capi-core"
ENV CAPI_CONFIG_FILE=/capi/config/config.yaml
USER 1000
# NOTE: the heap settings below are demo-sized (-Xmx512m). A deployment that overrides the
# launch args elsewhere (systemd unit, Helm values, launch script) will not pick these up.
# For investigating creeping RSS with a flat heap, two settings are worth adding there:
#   env  MALLOC_ARENA_MAX=2                 # cap glibc malloc arenas
#   jvm  -XX:NativeMemoryTracking=summary   # native growth visible via `jcmd VM.native_memory`
# See tools/capi-rss-watch.sh.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-Xms512m", "-Xmx512m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/capi/logs/heap-dump.hprof", "-jar", "app.jar"]