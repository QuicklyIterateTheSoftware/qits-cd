# qits-cd as a GraalVM-native container IMAGE — the same shape as its siblings' docker/Dockerfile:
# a Mandrel builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces `service/target/qits-cd`
# in about two minutes with no container involved.
#
# Build from the repo root:
#
#     docker build -t qits/cd:latest -f docker/Dockerfile .
#
# --- this image does NOT run with an empty environment, and that is not a packaging bug ------------
# `docker run --rm qits/cd:latest` dies before the HTTP listener: uid 1001 has no /etc/passwd entry
# in ubi-minimal, a native image resolves user.home through getpwuid(2) rather than $HOME, and the
# ${user.home}-rooted H2 default the `cd` jar ships has nowhere to land. IT IS DELIBERATELY LEFT
# THAT WAY (the qits-ci stance): an image that comes up healthy on an empty environment resolves
# the prototype H2 path into the container's own writable layer — presenting the
# one-file-database-next-to-the-process arrangement as a decided architecture, and silently
# discarding every recorded environment and deployment on the next redeploy. A container that
# refuses to start is the failure you see; one that starts on ephemeral storage is the failure that
# hides.
#
# So a deployment mounts a volume at /data (which this image owns, see below) and names the
# database:
#
#   docker run -v qits-cd-data:/data \
#     -e QUARKUS_DATASOURCE_CD_JDBC_URL=jdbc:h2:file:/data/cd/h2/cd \
#     ...
#
# Two more have defaults that are wrong QUIETLY — nothing will tell you they are unset:
#
#   QITS_CD_REGISTRY_HOST   where the DOCKER DAEMON pulls images from. The qits-net default
#                           (qits-artifacts:8080) only resolves for a daemon whose containers sit
#                           on qits-net, and the daemon must additionally trust it
#                           (insecure-registries) while the platform speaks plain HTTP. Wrong, every
#                           deployment records FAILED with a pull error.
#   QITS_OBSERVABILITY_URL  where OTLP export goes; absent qits-observability, the exporter logs a
#                           warning per export loudly enough to bury real lines
#                           (OTEL_SDK_DISABLED=true is the escape hatch).
#
# There is no QITS_CD_TOKEN: nothing of cd is on the gateway's token-free allowlist, so every
# /cd/* path is session-guarded at the front door, and qits-net callers are trusted.
#
# --- READ THIS BEFORE DEPLOYING: cd does its work by shelling out --------------------------------
# The docker CLI is installed below, but it is only half of what a deployment has to supply, and
# the other half is a decision, not a default:
#
#   THE DOCKER SOCKET IS NOT IN THIS IMAGE. Run with
#   `-v /var/run/docker.sock:/var/run/docker.sock`, or cd can deploy nothing.
#
# That mount hands this container control of the host's docker daemon, which is root-equivalent —
# the same trust boundary qits-ci draws, and the reason the containers cd STARTS never get the
# socket. It is left as an explicit deployment act rather than baked in. Without it the service
# still starts and serves: network ensures log one WARN and each deployment records FAILED with a
# docker connection error instead of a result.
#
# Only docker's CLI is here. `qits.cd.container-runtime` names the binary cd invokes, so pointing
# it at anything else makes supplying that CLI the deployment's problem.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds `service`'s dependency from source —
# `qits-cd-domain` is a 1.0.0-SNAPSHOT this repo never publishes, so a builder container with an
# empty ~/.m2 has nowhere else to get it. `.dockerignore` is what keeps this cheap.
COPY . .

# Tests are skipped because `./mvnw verify` on a clone is their gate (AGENTS.md); stopping at
# `package` rather than `verify` keeps CdPackagedSurfaceIT out of the image build. Cap native-image
# RAM so the build is deterministic on small machines.
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# The docker CLI comes from Docker's own repository because no UBI repository carries a
# docker-compatible client at all; the repo definition is removed again afterwards so the shipped
# image does not carry a third-party package source it has no reason to consult. No git here —
# unlike qits-ci, cd fetches nothing from a repository; everything it needs arrives as an HTTP
# payload or an image pull the daemon performs.
RUN printf '[docker-ce-stable]\nname=Docker CE Stable\nbaseurl=https://download.docker.com/linux/centos/9/$basearch/stable\nenabled=1\ngpgcheck=1\ngpgkey=https://download.docker.com/linux/centos/gpg\n' \
      > /etc/yum.repos.d/docker-ce.repo \
 && microdnf -y --setopt=install_weak_deps=0 --nodocs install docker-ce-cli \
 && microdnf -y clean all \
 && rm -f /etc/yum.repos.d/docker-ce.repo

# `quarkus.package.output-name` names the binary `qits-cd` (no -runner suffix), so the process is
# identifiable in `ps`/`docker top` — which matters here because this service's own work is
# spawning docker children. That name is spelled a third time in service/pom.xml as failsafe's
# `native.image.path`; all three move together.
COPY --from=build --chown=1001:root /src/service/target/qits-cd /work/qits-cd
RUN chmod +x /work/qits-cd

# The mount point for the H2 url, created here so it is OWNED. Docker seeds a fresh named volume
# from whatever the image has at the mount path, ownership included; with no such directory it
# makes one root-owned 0755, and the boot then dies on an AccessDeniedException rather than on the
# config error the header documents. Group-writable and group-root because a ubi image is meant to
# survive being assigned an arbitrary UID with GID 0.
RUN mkdir -p /data/cd && chown -R 1001:root /data && chmod -R 775 /data

EXPOSE 8080
USER 1001

# Quarkus binds 127.0.0.1 outside dev mode, which inside a container means nothing can reach it —
# not the gateway, not a peer on qits-net.
ENTRYPOINT ["/work/qits-cd", "-Dquarkus.http.host=0.0.0.0"]
