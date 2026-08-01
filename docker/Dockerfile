# qits-cd as a GraalVM-native container IMAGE — the same shape as its siblings' docker/Dockerfile:
# a Mandrel builder stage compiles the binary, a minimal runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names a
# GraalVM (25.0.2-graalce), so `sdk env && ./mvnw package -Dnative` produces `service/target/qits-cd`
# in about two minutes with no container involved.
#
# Build from the repo root:
#
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/cd:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the cd client
# (service/src/main/webui = the qits-spa-cd submodule), and the builder stage below packages a
# bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the mvnw line for
# why the build cannot happen inside that stage.
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
#   QITS_ARTIFACTS_REGISTRY_HOST
#                           where the DOCKER DAEMON pulls images from. The qits-net default
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
#
# `localhost:8081/quay/…` is qits-artifacts' pull-through mirror of quay.io: the first build fetches
# the image from upstream and caches it, every later build is served from local disk. The tag is
# upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay moves it.
FROM localhost:8081/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
#
# --- the Quinoa flags, and why this stage does not build the client -------------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a local or CI `mvnw` build must use the node on PATH, so that no
# build silently downloads a toolchain. 22.22.0 is the PLATFORM pin
# (docs/project-setup-quinoa-angular.md in the qits superproject).
#
# The three command overrides are the load-bearing part, and the reason this file deviates from that
# recipe (which has the builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-cd, and it depends on @qits/ui-components. That package
#   exists ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a docker build
#   reaches the public internet — Maven Central, npmjs.org, nodejs.org — and can reach the platform
#   registry by NO address: not the qits-net alias, not localhost:8081, not host.docker.internal. So
#   the install cannot happen here, and an install that "worked" here would have resolved the @qits
#   scope against npmjs.org, where that name does not exist and never will.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore, which for that
# reason does NOT exclude it — the one place this repo's ignore file departs from the reference
# recipe). `--version` is the null command: it proves the package manager is really there and does
# nothing else.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. (The same
# guard covers an uninitialised submodule, which git leaves as an empty directory and Quinoa rejects
# with `No package.json found in Web UI directory`.)
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-cd/browser`, which names the source and explains
# nothing. A builder stage that *creates* dist itself is immune by accident; ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
RUN d=service/src/main/webui/dist/qits-spa-cd/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
# `localhost:8081/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM localhost:8081/redhat/ubi9/ubi-minimal:9.6
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
