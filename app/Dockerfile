# The app image, built from the jar Maven already made (./mvnw package -DskipTests), not from source: a jar is
# the same on every CPU, so a multi-arch build (amd64 + arm64, CI's "image" job) only repackages it per platform.
#
# Three stages (T37):
#   jre     a Java runtime with only the modules Dispatch uses (jlink), from the current Temurin release
#   layers  the jar split into layers: dependencies change rarely, so after a code change only a small layer is new
#   final   distroless (glibc, CA certificates, tzdata; no shell, no package manager), as a non-root user
#
# Base images are pinned by digest: a rebuild gets exactly the same bases until someone updates them on purpose.
ARG JDK=eclipse-temurin:21.0.12.1_1-jdk@sha256:5d90b26c0784ccf55af55f97cbcf7f3969c66a06299c93bdc72649219942a48f
ARG RUNTIME=gcr.io/distroless/cc-debian12:nonroot@sha256:9dac0a79194e45a7da0158a9c6da57b217585af0786db3845d1f0ec1a0dd182f

FROM ${JDK} AS jre
# From jdeps over the app and every library (T37), plus modules loaded by name at runtime, which jdeps can't see:
# jdk.crypto.ec (TLS with elliptic curves), jdk.charsets (mail encodings), jdk.zipfs, jdk.naming.dns,
# jdk.localedata for English and Bangla.
RUN jlink \
      --add-modules java.base,java.compiler,java.desktop,java.instrument,java.logging,java.management,java.naming,\
java.net.http,java.prefs,java.scripting,java.security.jgss,java.security.sasl,java.sql,java.sql.rowset,\
java.transaction.xa,java.xml,jdk.jfr,jdk.management,jdk.net,jdk.unsupported,\
jdk.crypto.ec,jdk.charsets,jdk.zipfs,jdk.naming.dns,jdk.localedata \
      --include-locales=en,bn \
      --strip-debug --no-man-pages --no-header-files --compress=zip-6 \
      --output /jre

FROM ${JDK} AS layers
WORKDIR /build
COPY target/dispatch-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM ${RUNTIME}
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="dispatch" \
      org.opencontainers.image.description="Dispatch: a multi-tenant marketplace for on-site technical work" \
      org.opencontainers.image.source="https://github.com/md-rashed-zaman/dispatch-platform" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}"
ENV JAVA_HOME=/opt/java PATH=/opt/java/bin:/usr/bin:/bin
COPY --from=jre /jre /opt/java
WORKDIR /app
COPY --from=layers /build/extracted/dependencies/ ./
COPY --from=layers /build/extracted/spring-boot-loader/ ./
COPY --from=layers /build/extracted/snapshot-dependencies/ ./
COPY --from=layers /build/extracted/application/ ./
# distroless's "nonroot" user (uid 65532): no root inside the container, and a numeric uid Kubernetes can check
# (runAsNonRoot).
USER 65532:65532
EXPOSE 8080
# - Heap from the container's memory limit, not the machine's: half of it. The rest is needed: this app uses
#   ~420 MB outside the heap (metaspace alone ~190 MB, then the code cache, threads, GC and malloc). Run it with a
#   1 GiB limit: a 512 MB heap. (70% of 640 MB looked fine under Docker, which lets a container swap; on
#   Kubernetes, with no swap, it was OOMKilled: step 5.2.)
# - Out of memory: exit, so the orchestrator restarts a clean JVM instead of keeping a half-broken one.
# - JAVA_TOOL_OPTIONS adds or overrides flags per environment without rebuilding.
# Exec form: java is PID 1 and gets SIGTERM directly, so graceful shutdown runs (server.shutdown).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=50", "-XX:+ExitOnOutOfMemoryError", \
            "org.springframework.boot.loader.launch.JarLauncher"]
