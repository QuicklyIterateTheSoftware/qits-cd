# qits-cd — working notes

Read `README.md` first: it defines the flow (environment → green build → health-gated cutover) and
the conventions. This file is the working conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials, **no network**. That is why the poms duplicate versions
instead of inheriting them, and why both seams that reach outside the process are faked rather than
skipped: `FakeDeploymentDriver` behind `DeploymentDriver` (docker) and `FakeSpecSource` behind
`CdSpecSource` (the git host).

**Which command is the gate depends on whether you have the client**, and this is worth getting
right because the clone rule now has two halves (`git clone … && git submodule update --init`):

- `./mvnw test` — needs **neither node nor the webui submodule**. Quinoa is disabled by default in
  test mode (it says so: `Quinoa is disabled by default in tests.`), so every `@QuarkusTest` here
  passes against an empty `webui/` on a machine with no node at all.
- `./mvnw verify` — runs `package` on its way to failsafe, and `package` is where Quinoa augments.
  So verify needs **both**, and against an uninitialised submodule it fails with
  `No package.json found in Web UI directory: 'src/main/webui'`. That is true of every SPA-serving
  service, not a wart of this one; `docs/project-setup-quinoa-angular.md` in the superproject
  states it correctly.

Neither command needs a port argument any more: `service/src/test/resources/application.properties`
sets `quarkus.http.test-port=0`, so the suite takes a free port instead of Quarkus' default 8081 —
which on the deployment host is the platform's own npm registry, and which `@QuarkusTest` restarts
race each other for anywhere. Failsafe passes the same 0 to the packaged artifact (`service/pom.xml`),
and the siblings carry both lines. `-Dquarkus.http.test-port=18081` is no longer needed and no longer
documented as a workaround.

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
  sense that matters: no JAX-RS, no web stack, **no HTTP client**. Entities are Panache with public
  fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`. `control` owns the two
  orchestrators (`EnvironmentService`, `DeployService`), the validation (`CdIdentifiers`), the
  shell-out (`CdProcess`), the image-reference and network-name conventions (`ImageRefs`,
  `CdNetworks`), the strict spec parser (`DeploymentSpecParser`) and the two seams
  (`DeploymentDriver`, `CdSpecSource`).
- `service/` — `api` (the JAX-RS routes and `CdExceptionMapper`), `dockerhost`
  (`DockerDeploymentDriver` — the sole implementation of the docker seam, kept here because it is
  cd's whole relationship with the host's docker daemon) and `githost` (`GitHostSpecSource`, the
  one outbound HTTP call). There is no `security` package any more: the forward-auth pair moved to
  the published `qits-auth-core` library.

**The seam rule is one rule, applied twice.** Both things `cd/` cannot do — shell out to docker,
fetch a file over HTTP — are interfaces there and implementations in `service/`, with a scripted
fake in the suite. Anything that grows a third of these follows the same shape; do not put a client
in the domain module.

One package sits outside that tree: `eu.wohlben.qits.webui`, holding `WebUiRedirect` and only that.
It keeps the sibling services' spelling rather than taking a `cd`-flavoured one, so the file is
recognisable across repos.

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

## The model: tiers, derived rows, and two planes

An **environment is a tier** (dev, preprod, prod), created deliberately over REST, branch
`environment/<name>` by convention. `main` stays the integration trunk and a release reaches dev by
fast-forwarding `environment/dev` onto it — so **a push to `main` alone builds and deploys nothing**
except a singleton.

**Application rows are derived and there is no write for them.** A green build sends cd to the
repository's `.config/qits/deployments.yml` at that sha (`CdSpecSource`), and the row is created or
brought up to date from it. Three consequences worth holding on to:

- A repository with **no file** gets every default, so it behaves exactly as it did before the file
  existed. That is not politeness — it is what let this change deploy onto a platform where no
  repository had the file yet, and it is why `FakeSpecSource` answers `DEFAULTS` unless a test says
  otherwise. Keep that default.
- A spec that cannot be **read or parsed** fails the deployment with the cause on the row. Never add
  a fallback that guesses: a guessed topology is a container on the wrong networks under the wrong
  name, which is worse than a recorded failure and much harder to see.
- `deployment_target: singleton` **converts** the repository's environment-scoped rows — their
  deployments move onto the singleton, the active ones decommissioned, the old rows go. That
  conversion is a one-time live migration (idp and cd) and the reason it moves rather than deletes
  is `sweepInFlight`: a `STARTING` self-update row must survive cd's own conversion.

The two planes are the whole of `CdDeploymentTarget`. A **singleton** has no environment (null
column, no `qits.cd.environment` label, no `QITS_ENVIRONMENT`, `deployment.environment.name=platform`),
carries its own branch, and is named `qits-cd-singleton-<app>-<id8>`. The absent label is
load-bearing: an environment teardown reaps by it.

## Networks are docker's bookkeeping, never a row

Hub and spoke. An application runs on `qits-env-<env>-<app>`; a public node (`available_on_env`)
joins its environment's bundle and every per-application network of that environment; a singleton
runs on `qits-platform` and joins every per-application network of every environment. `docker run`
takes one network, so everything else is a `network connect --alias <app>` after the start — and the
join set is recomputed from docker on every deployment rather than remembered, which is what makes
it the self-heal too.

**No membership is ever stored in H2.** It is written as labels and read back with
`--filter label=`. `qits.cd.app-name` is on containers as well as networks, and only for the
reconciliation: joining a running container to a new network needs the alias, and the application
*id* it already carried names a row rather than an address.

Two things to leave alone unless you mean it:

- **`aliasHolders` searches the union** of every network the fresh container will be on, legacy one
  included. Narrow it and a deploy starts a second copy beside a container that holds its alias on
  `qits-net` alone — which is every container on the platform until it has been redeployed once.
- **`qits.cd.legacy-network`** (default `qits-net`, `Optional<String>` because SmallRye reads an
  empty value as absent) is the transition membership. **Emptying it is the enforcement flip**, a
  later phase that needs every direct cross-application URL migrated to a gateway route first.
  `LegacyNetworkOffTest` already runs that posture.

## The cutover invariant

Replace, not overlap: whatever holds the application's alias is *stopped* before the fresh
container starts (one process per H2 file, one binder per published port — overlap cannot deploy
a stateful application), and *removed* only after the new one passed the health gate; a failed
gate removes the fresh container and **restarts** what was stopped, leaving the previous
deployment ACTIVE and serving. Pull precedes stop, so the registry's own application is
replaceable. cd never stops its own container in-process — deploying `qits-cd` takes the handoff
path (successor started, detached referee arbitrates, the surviving instance's sweep records the
outcome; README "Self-update is a handoff"). Every removal is a decision recorded on a
deployment row — a decommission, a failed cutover, a teardown — never a side effect, with one
stated exception: the referee's removals, which are the recorded-by-the-survivor arrangement.
The tests that hold this:
`CdDeploymentFlowTest.theReplaceCutoverStopsAliasHoldersBeforeStartingAndRemovesThemAfterTheGate`,
`.aFailedGateRestartsWhatTheCutoverStopped`,
`.aSelfUpdateStartsTheSuccessorAndHandsArbitrationToTheReferee`, and `CdSweepAdoptionTest`.

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

cd's own env flags (`QITS_ENVIRONMENT`, `QITS_APPLICATION`, `OTEL_RESOURCE_ATTRIBUTES` and its
`QUARKUS_`-spelled twin) are written **before** the run args, and docker keeps the **last**
assignment of a repeated key — measured, not assumed. So they are defaults an operator overrides,
and the ordering is the precedence rule: never reorder them past the run args.
`CdIdentifiers.requireAttributeValue` is the argv-side belt on the resource attributes, the same
stance as `requireHealthPath` — a comma or an equals sign in a value would forge an extra pair.

## Addressing

`quarkus.rest.path=/cd/api` lives in `service/src/main/resources/application.properties` and the
suite inherits it — a resource's `@Path` is relative to it and must never repeat `cd`; tests
address the absolute path, which is what makes them catch a prefix regression.

There is **no static machine token in this service** and none is ever to be added. Machine callers
present a qits-idp bearer, validated by `quarkus-oidc` against `aud=qits-cd` and checked by
`qits-auth-core`'s `MachineAuth`. Nothing of cd is on the gateway's token-free allowlist either, so
every `/cd/*` path is also session-guarded at the front door — qits-gateway's
`PublicPathsTest.nothingOfCdIsPublic` pins that.

**Where `machineAuth.require()` goes, and where it does not.** On a path nothing human reaches, so
a bearer is the only credential its caller could hold — today that is exactly the build-succeeded
intake. Not on the environment surface: a person drives it through the gateway's session, and a
guard there locks the humans out the day the gate flips on. Apply the same question to a new
write, and answer it in the commit that adds the write.

The guard is **gated off** by `qits.auth.machine.required` (default `false`, shipped by
`qits-auth-core`). Off, `require()` returns at once and the intake behaves exactly as it did before
the guard existed, which is what lets the code deploy before qits-idp does. A deployment sets
`QITS_AUTH_MACHINE_REQUIRED=true` only once qits-ci is actually sending a bearer — the notifier
swallows delivery failures at debug, so a premature flip stops deployments silently.

Validation follows that same gate: `quarkus.oidc.tenant-enabled=${qits.auth.machine.required:false}`.
Gate off, there is no OIDC tenant — nothing fetches a JWKS and a clone-alone build needs no issuer.
There is no third state. qits-ci and qits-artifacts carry the same line.

The intake path is a **cross-repo contract**: qits-ci's `CdBuildNotifier` POSTs
`/cd/api/events/build-succeeded` fire-and-forget via its `qits.cd.intake-url`. A mismatch raises
no error anywhere. Move one, move both.

**A new machine surface outside `/cd/api` needs a line in `quarkus.quinoa.ignored-path-prefixes`,
in the same commit.** Quinoa's SPA fallback is a catch-all at `/cd/*` registered near-last, so a
real route still wins — but a path matching *no* route is rerouted to `index.html` and answers
`200 text/html`, which a machine client parses as data. The caller that makes this concrete here is
the intake: it is fire-and-forget, so it would never report having been handed a web page. Three
facts about that key, all measured on sibling services:

- Setting it **replaces** Quinoa's derivation rather than extending it. The derivation reads
  `quarkus.rest.path` and `quarkus.http.non-application-root-path` and produces exactly `/api,/q` —
  which is why those two are repeated by hand in the key today. Naming a third alone would
  *un-ignore* both.
- The values are matched **after** `ui-root-path` is stripped, so they are **relative**. `/cd/api`
  written there matches nothing at all and is indistinguishable from leaving the key unset — the
  failure that hides.
- `@WebSocket` and anything registered straight onto the Vert.x router do **not** follow
  `quarkus.rest.path`; they take a literal path and need their own entry. websockets-next claims
  only the upgrade handshake, so a plain GET on a socket path falls through to the SPA (measured on
  qits-ci's `/ci/daemon`: `200 index.html` from a green build). cd has no socket and no other
  literal today — the one thing it puts on the router is `WebUiRedirect` at the bare `/cd`, which is
  outside `/cd/*` and so is not Quinoa's to swallow.

The segment itself is spelled in **four** places that move together: `quarkus.quinoa.ui-root-path`,
`quarkus.rest.path`, `quarkus.http.non-application-root-path`, and the client's `baseHref` in
qits-spa-cd's `angular.json` — the fourth in another repository, where no build here can check it.
A `baseHref` that disagrees yields a page that loads and then fetches its own JavaScript from the
wrong place, and no server-side test can see it.

## Adding a dependency on another context

Don't. This context has no compile-time dependency on any other qits module and should not grow
one. Things arrive as an HTTP payload on the intake, or as a URL in config, or not at all. Never
add a JPA relation to another context's entity — `cd_application.repo_id` is a plain `String`
column in cd's **own** physical database; the FKs inside that database (application → environment,
deployment → application) are fine and are not what the rule is about.

## Schema changes

`cd/src/main/resources/db/cd/migration/`, hand-written, its own lineage on its own datasource —
keep appending, never edit an applied migration.

## Dependencies

**The client is the only submodule.** `service/src/main/webui` is qits-spa-cd. An uninitialised
checkout fails with Quinoa's `No package.json found in Web UI directory`; `git submodule update
--init` is half of a clone here, and `.config/qits/ci-post-receive.yml` runs it for that reason.
Shared auth comes from the platform Maven repository as `qits-auth-core:1.0.0`.

**`quarkus-undertow` must never be on the classpath.** Its presence breaks Quinoa's production
static serving — the client 404s from a build that was green — and it arrives *transitively* from
anything servlet-shaped. Check before adding anything that sounds like a web framework:

    ./mvnw -pl service -am dependency:tree | grep -i undertow

**Quinoa is in no BOM**, so its version is pinned by hand, in the root pom's properties
(`quinoa.version`) rather than beside the dependency. 2.8.2 is the last release built against a
Quarkus *older* than the platform's 3.34.6; 2.8.3 is built against 3.36.2, ahead of us. Bump only
when the platform's Quarkus passes the version a release is built against.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and Quarkus merges
  it into the test config. **Never re-declare an app-level setting in test resources** — the test
  copy carries only what a test run genuinely needs to be different: the port, the in-memory H2,
  and `quarkus.devservices.enabled=false`.
- **No dev services, ever.** A dev service is a container start, and the first rule here is that a
  clone tests green with no docker. `quarkus-oidc` in particular launches a real Keycloak the
  moment a profile leaves `quarkus.oidc.auth-server-url` unset — measured, not feared. One line in
  the test resources shuts all of them off; keep it.
- **Machine-token tests mint their own tokens.** `MachineTokens` signs RS256 with the key pair in
  `service/src/test/resources/machine-token-*.pem`, and `MachineGuardEnforcedProfile` hands
  quarkus-oidc the public half, so the enforced path is exercised end to end with no qits-idp to
  reach. Those PEMs are **test fixtures, not credentials** — nothing outside the suite has ever
  seen them, and no deployment key belongs in this repo.
- `FakeDeploymentDriver` and `FakeSpecSource` are `@Mock` and application-scoped, so they are
  shared across tests: reset both in `@BeforeEach`, use distinct environment names **and repository
  ids** per test, and read their state through their **methods** — the injected reference is a CDI
  client proxy, and a field read on a proxy sees the proxy's fields, not the bean's. That one has
  already been paid for. Registration is derived now, so a repository id is as much a test's own
  namespace as an environment name is.
- Flow tests poll the read surface to a deadline rather than reaching into the service — the same
  way a caller experiences the API, and immune to the worker's timing. Singletons are the one thing
  that surface cannot show: `/cd/api/deployments` takes an environment, and a singleton has none —
  so those tests wait on the driver instead (`awaitStarted`) and read the row through
  `/cd/api/applications`.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit when the surface
  changes: `./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest
  -Dsurefire.failIfNoSpecifiedTests=false`. The intake is `@Operation(hidden = true)` (a wire
  API); the environment, application and deployment surfaces are the document. The test classpath is indexed
  too, so a new `@Path` resource under `src/test` lands in the committed document unless it is
  hidden.
- `CdPackagedSurfaceIT` runs the **packaged artifact** (fast-jar under `-DskipITs=false`, binary
  under `-Dnative`) and asserts what a native build can silently lose: the build-time route
  prefixes, the shipped ${user.home}-rooted H2 default (it relocates `user.home` rather than
  restating the URL), Flyway's migration surviving as a resource. It points
  `qits.cd.container-runtime` at a binary that does not exist, which both keeps it free of host
  side effects and proves every driver call degrades to a warning rather than a failure.
- **`CdPackagedSurfaceIT` is also the only test that ever sees the client.** Quinoa is disabled in
  test mode, so no `@QuarkusTest` here has a client in it at all — a unit test asserting anything
  about `/cd/` would pass against a process serving nothing. The probe list is the platform's, from
  `docs/project-setup-quinoa-angular.md` in the superproject, and any change touching the Quinoa
  setup re-runs it: `/cd/` → 200 HTML with the right `<base href>`; a deep link → 200 `index.html`;
  `/cd/api/<real>` → the API's own answer; `/cd/api/nope` → 404 and **not the client**; every
  literal machine path, mistyped → 404. Note the last ones are asserted as "404 and not
  `index.html`" rather than "404 and not HTML": what a mistyped path actually gets is Vert.x' own
  stock `<h1>Resource not found</h1>`, which is `text/html` and correct. The content type alone
  cannot tell the two apart, so the *absence of the client* is what is pinned.
- `WebUiRedirectTest` is a plain `@QuarkusTest` and can be, precisely because the bare-segment
  redirect is this service's own Vert.x route rather than Quinoa's — it must answer whether or not
  a client is packaged. Its `theSlashFormIsNotThisRoutesBusiness` leans on Quinoa being off under
  `%test`: `/cd/` falling through to a 404 is the proof that this route let it pass instead of
  looping the redirect onto itself.

## The image and the pipeline

`docker/Dockerfile` and `.config/qits/ci-post-receive.yml` are two halves of one thing, and the seam
between them is the only reason either is interesting: **the client cannot be built inside a docker
build.** It depends on `@qits/ui-components`, which lives only on the platform's own npm registry,
and a `RUN` step reaches the public internet but reaches that registry by no address at all. So the
pipeline step — which runs on `qits-net`, where it does resolve — installs and builds the bundle,
and the Dockerfile's builder stage neuters Quinoa's install/ci/build commands to `--version` and
packages what it was handed.

Three things follow, and each is load-bearing:

- **`.dockerignore` does NOT exclude the client's `dist/`.** That departs from the platform's Quinoa
  reference, which does — here `dist/` is the payload, and excluding it fails the build at the
  `test -f` guard. Every SPA-serving service in the platform carries the same departure.
- **The two `package-manager-install` flags exist only on the Dockerfile's `mvnw` line**, because the
  Mandrel builder image ships no node. They must never go into `application.properties`: a local or
  CI build must use the node on `PATH`, so that no build silently downloads a toolchain. `22.22.0` is
  the platform pin.
- **The bundle is `cp`'d onto itself before the build.** Quinoa *moves* `build-dir` rather than
  copying it, and overlayfs cannot rename a directory that still lives in a lower image layer — it
  answers EXDEV and the JDK's fallback refuses a non-empty directory, dying with
  `DirectoryNotEmptyException` seconds in. The `cp` re-materialises it in the layer that is about to
  move it, which is why it has to be in that same `RUN`.

The pipeline also rewrites `package-lock.json`'s `resolved` **origins** before `npm ci`: npm fetches
tarballs by the absolute URL in the lockfile and ignores the configured registry, and npm's own
`--replace-registry-host` is broken for a registry mounted under a path prefix. The committed
lockfile keeps the developer-host origin, which is correct locally.

That the *deployer* is also a deployed application is the one thing this repo's pipeline adds over
its siblings': a green run here announces `qits-cd` to `qits-cd`, which takes the self-update
handoff. The `/cd` surface blips mid-cutover; a successor that misses its health gate leaves the
predecessor serving.
