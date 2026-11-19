# 20. The app image: a jlink'ed Java runtime on distroless, packaged from the Maven-built jar

Date: 2026-11-19
Status: Accepted

## Context

Step 5.1 needs an image for Kubernetes and a cloud VM, for amd64 and arm64. The first image (Phase 2) was the Temurin JRE image plus the layered jar: 481 MB on disk (251 MB to download), a full Ubuntu userland with a shell and a package manager, running as a non-root user.

What matters, in order: current Java security patches, a small attack surface, a reasonable size, and builds that are fast and reproducible.

## Decision

1. **Package the jar Maven built; don't build inside the image.** A jar is the same on every CPU, so a multi-arch build only repackages it per platform (CI's `image` job: QEMU for arm64 runs a few file copies, not Maven).
2. **A trimmed Java runtime from the current Temurin JDK** (21.0.12.1), made with `jlink`: the 20 modules `jdeps` finds across the app and every library, plus the ones loaded by name at runtime that `jdeps` can't see (`jdk.crypto.ec`, `jdk.charsets`, `jdk.zipfs`, `jdk.naming.dns`, `jdk.localedata` for English and Bangla).
3. **On `gcr.io/distroless/cc-debian12:nonroot`**: glibc, CA certificates and tzdata; no shell, no package manager; user 65532.
4. **Layered jar**: dependencies, loader, snapshot dependencies, then the application, so a code change replaces a 2 MB layer.
5. **Bases pinned by digest**, so a rebuild gets the same bases until someone updates them on purpose.
6. **JVM flags in the image:** heap at 70% of the container's memory limit, exit on out-of-memory; `JAVA_TOOL_OPTIONS` adds more per environment.

## Alternatives considered

| Option | Verdict |
|---|---|
| Temurin JRE image (Ubuntu) | 481 MB on disk, a shell and a package manager in production. Kept as the fallback if a missing module ever shows up: the Dockerfile's `JDK` stage is the same Temurin release. |
| `gcr.io/distroless/java21-debian12` | 341 MB on disk with no work, but its Java was 21.0.10, two quarterly security updates behind. Older Java is a worse trade than a bigger image. |
| Alpine (musl) | Smaller still, but native libraries in the dependencies (zstd-jni for Kafka compression) are built for glibc first; a musl surprise in production isn't worth the megabytes. |
| `spring-boot:build-image` (buildpacks) | Good defaults and no Dockerfile to keep, but less control over the runtime (the buildpack's own full JRE layer, no jlink), and the build lives in Maven, outside the plain `docker build` that compose and CI use. |
| Build the jar inside a multi-stage Dockerfile | Self-contained, but a multi-arch build would run Maven under emulation for arm64 (minutes, every time), and CI already builds the jar. |

## Consequences

- Measured the same way for all three (same app layers; `docker save`): Temurin JRE 481 MB on disk / 251 MB to download, distroless Java 341 / 199, this image **238 / 187**. Half the old size on disk, on the current Java patch release, with no shell. The runtime itself is 66 MB. (`docker image ls` shows larger numbers on Docker's containerd image store, which counts compressed and unpacked copies.)
- Proven against the real stack: both demo scripts pass on the image (payments, ledger, refunds, flags, MCP, uploads, Kafka, email), outbound TLS works (Gemini answered), the heap is 448 MB under a 640 MB limit, `docker stop` shuts down gracefully in milliseconds (exit 143).
- No shell to debug with: use `kubectl debug` with an ephemeral container, or run the `JDK` stage locally.
- A new library that loads a module by name needs that module added to the `jlink` list; the symptom would be a `ClassNotFoundException` for a `java.*`/`jdk.*` class at runtime, and the demos would catch the common paths.
