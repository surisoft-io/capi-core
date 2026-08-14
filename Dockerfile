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
# NOTE (2026-07-23, RSS native-creep investigation): the EU prod-like nodes do NOT run these
# args (they show a ~7GB heap; this image is demo-only at -Xmx512m). Wherever the real launch
# args live (systemd unit / launch script on the EU hosts), add for the prod rollout:
#   env  MALLOC_ARENA_MAX=2                 # cap glibc malloc arenas (flat-heap/creeping-RSS insurance)
#   jvm  -XX:NativeMemoryTracking=summary   # so native growth is visible via `jcmd VM.native_memory` (needs restart)
# See tools/capi-rss-watch.sh and the memory note project_rss_native_investigation.md.
ENTRYPOINT ["java", "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=100", "-Xms512m", "-Xmx512m", "-XX:+HeapDumpOnOutOfMemoryError", "-XX:HeapDumpPath=/capi/logs/heap-dump.hprof", "-jar", "app.jar"]