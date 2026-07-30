# qits-cd — working notes

Read `README.md` first: it defines the flow (environment → green build → health-gated cutover) and
the conventions. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `mvn verify` is the gate. That is why the poms duplicate
versions instead of inheriting them, and why the one seam that needs real docker is faked
(`FakeDeploymentDriver`, a scripted fake behind the `DeploymentDriver` interface) rather than
skipped.

**`service/` compiles to a GraalVM native image** — the same rule every deployable sibling
carries. `.sdkmanrc` names `25.0.2-graalce`, so `sdk env` gives you a `native-image` and
`./mvnw verify -Dnative` produces `service/target/qits-cd` and runs `CdPackagedSurfaceIT` against
it. The consequences to keep in your head: a missing GraalVM does not fail the build (Quarkus
falls back to a container build — grep the log for `Cannot find the native-image`); prefer what is
already in the image (`ProcessBuilder` over a docker client library — the reason `CdProcess`
shells out); and every config default the app boots with is part of the native surface (the
AUTO_SERVER lesson: cd's H2 URL carries none, do not add it).

## Package and module conventions

`eu.wohlben.qits.cd.*`, split across maven modules with disjoint sub-packages so there is no split
package:

- `cd/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`. Framework-free in the
  sense that matters: no JAX-RS, no web stack. Entities are Panache with public fields; mappers
  are MapStruct `@Mapper(componentModel = "jakarta")`. `control` owns the two orchestrators
  (`EnvironmentService`, `DeployService`), the validation (`CdIdentifiers`), the shell-out
  (`CdProcess`), the image-reference convention (`ImageRefs`) and the docker seam
  (`DeploymentDriver`).
- `service/` — `api` (the JAX-RS routes and `CdExceptionMapper`), `security` (the forward-auth
  pair), and `dockerhost` (`DockerDeploymentDriver` — the sole implementation of the seam, kept
  here because it is cd's whole relationship with the host's docker daemon).

The directories are `cd/` and `service/`; the artifactIds are `qits-cd-domain` and
`qits-cd-service` — generic coordinates would collide in a shared `~/.m2`.

## The worker

`DeployService` runs every deployment on a single-threaded daemon worker (`cd-deploy-worker`), the
`CiRunService` shape: the intake returns immediately, each DB transition sits in its own
`QuarkusTransaction.requiringNew()` bracket, and everything the docker calls need is copied out of
the entities into a plain `Plan` record first — the worker thread has no request context and no
open session. Serial execution is load-bearing beyond simplicity: it is what makes "the previous
ACTIVE deployment" an uncontended read during cutover.

Transactions are programmatic everywhere in `control`, not `@Transactional` — partly for the
worker, partly because a `this.`-invocation never crosses the interceptor and a lost bracket fails
quietly.

The startup sweep (`DeployService.onStart`) fails rows left `QUEUED`/`STARTING` by a crash and
**deliberately reaps no containers** — a deployed application outlives its deployer, and whatever
was ACTIVE before the restart is still serving. Do not "complete" the sweep with a reap; the
asymmetry with qits-ci (whose step containers are ephemeral by definition) is the point.

## The cutover invariant

Replace, not overlap: whatever holds the application's alias is *stopped* before the fresh
container starts (one process per H2 file, one binder per published port — overlap cannot deploy
a stateful application), and *removed* only after the new one passed the health gate; a failed
gate removes the fresh container and **restarts** what was stopped, leaving the previous
deployment ACTIVE and serving. Pull precedes stop, so the registry's own application is
replaceable. cd never stops its own container — a `qits-cd` deployment records an honest FAILED
row until the planned successor-shuts-down-predecessor self-update exists. Every removal is a
decision recorded on a deployment row — a decommission, a failed cutover, a teardown — never a
side effect. The tests that hold this:
`CdDeploymentFlowTest.theReplaceCutoverStopsAliasHoldersBeforeStartingAndRemovesThemAfterTheGate`,
`.aFailedGateRestartsWhatTheCutoverStopped`, and `.theSelfGuardRefusesToStopItsOwnProcess`.

## Untrusted input

The write surface's strings end up in expensive places, so they are validated as untrusted at the
boundary regardless of who the caller is believed to be. `CdIdentifiers` holds all of it:

- **Environment and application names** become docker network names, network aliases, image path
  segments and container names — the dns-label charset, nothing else.
- **The commit sha** becomes an image tag in a `docker pull`/`docker run` argv — plain hex only.
- **The health path** is the one value interpolated into a string a shell runs (the container's
  own `--health-cmd`), so it gets the strictest allowlist and is re-checked at the last line
  before the argv (`DockerDeploymentDriver.buildArgv`). Never widen it, never add an exception.

Argvs are assembled for `ProcessBuilder`, which never re-splits — but do not lean on that:
validation stays at the boundary and the belt stays at the argv.

**cd executes nothing.** Its docker vocabulary is container lifecycle — `pull`, `run`, `inspect`,
`logs`, `rm`, `ps`, `network create/inspect/rm` — and `exec` is not in it. What a deployed
container runs is its image's own entrypoint; cd's relationship with it ends at lifecycle.

Mounts and extra env in a *started* container's argv exist, and their source is the invariant
(rewritten consciously when `qits.cd.run-args.<application>` landed — the same amendment qits-ci's
"no repo-controlled code gains a docker socket" went through for `docker: true`): they come from
the **deployment's own config and nowhere else**. Nothing arriving over HTTP — not the create
request, not the intake — may contribute a token to a `docker run` argv; the API is deliberately
open on qits-net, and config is the trust domain that already holds the socket.
`DockerDeploymentDriverTest.runArgsOfAnotherApplicationDoNotLeakIn` asserts the absence as the
security property. A `docker exec`, or run-args growing an HTTP-writable source, is the
regression.

## Addressing

`quarkus.rest.path=/cd/api` lives in `service/src/main/resources/application.properties` and the
suite inherits it — a resource's `@Path` is relative to it and must never repeat `cd`; tests
address the absolute path, which is what makes them catch a prefix regression.

There is **no machine token in this service** — nothing of cd is on the gateway's token-free
allowlist, so every `/cd/*` path is session-guarded at the front door, and qits-net callers are
trusted (that is where the intake's sender actually dials). The `qits.ci.token` pattern belongs to
paths that are allowlisted at the gateway; do not reintroduce it here without the allowlist entry
that would give it a job, and never the allowlist entry without the guard — qits-gateway's
`PublicPathsTest.nothingOfCdIsPublic` is what makes that a conscious pair of changes.

The intake path is a **cross-repo contract**: qits-ci's `CdBuildNotifier` POSTs
`/cd/api/events/build-succeeded` fire-and-forget via its `qits.cd.intake-url`. A mismatch raises
no error anywhere. Move one, move both.

## Adding a dependency on another context

Don't. This context has no compile-time dependency on any other qits module and should not grow
one. Things arrive as an HTTP payload on the intake, or as a URL in config, or not at all. Never
add a JPA relation to another context's entity — `cd_application.repo_id` is a plain `String`
column in cd's **own** physical database; the FKs inside that database (application → environment,
deployment → application) are fine and are not what the rule is about.

## Schema changes

`cd/src/main/resources/db/cd/migration/`, hand-written, its own lineage on its own datasource —
keep appending, never edit an applied migration.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and Quarkus merges
  it into the test config. **Never re-declare an app-level setting in test resources** — the test
  copy carries only the in-memory H2.
- `FakeDeploymentDriver` is `@Mock` and application-scoped, so it is shared across tests: reset it
  in `@BeforeEach`, use distinct environment names per test, and read its state through its
  **methods** — the injected reference is a CDI client proxy, and a field read on a proxy sees the
  proxy's fields, not the bean's. That one has already been paid for.
- Flow tests poll the read surface to a deadline rather than reaching into the service — the same
  way a caller experiences the API, and immune to the worker's timing.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit when the surface
  changes: `./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest
  -Dsurefire.failIfNoSpecifiedTests=false`. The intake is `@Operation(hidden = true)` (a wire
  API); the environment and deployment surfaces are the document. The test classpath is indexed
  too, which is why `IdentityEchoResource` is hidden.
- `CdPackagedSurfaceIT` runs the **packaged artifact** (fast-jar under `-DskipITs=false`, binary
  under `-Dnative`) and asserts what a native build can silently lose: the build-time route
  prefixes, the shipped ${user.home}-rooted H2 default (it relocates `user.home` rather than
  restating the URL), Flyway's migration surviving as a resource. It points
  `qits.cd.container-runtime` at a binary that does not exist, which both keeps it free of host
  side effects and proves every driver call degrades to a warning rather than a failure.
