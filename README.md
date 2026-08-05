# qits-cd

Continuous deployment for the platform's own services: environments, health-gated cutover, and the
convention that turns a green build into a running container.

## What it does

An **environment** is a name (by convention an epic's slug), a branch, a docker network, and the
applications it tracks. The intended lifecycle, end to end:

1. qits-projects starts an epic and creates an environment here — `POST /cd/api/environments`
   with the epic's slug and the participating repositories. Conventions fill the rest: the branch
   is `epic/<name>`, the network is `qits-env-<name>`.
2. Pushes to `epic/<name>` reach the git host, qits-ci builds them, and on a green run notifies
   this service — `POST /cd/api/events/build-succeeded` with `{runId, repoId, branch, commitSha}`.
3. cd matches (repoId, branch) against its environments. For each tracked application it records a
   deployment — carrying `runId` verbatim, the row's one pointer back at the build that caused it
   (`/ci/runs/<runId>`); cd resolves it against nothing and it is null on every row recorded before
   the column existed — and, on its worker: derives the image reference by convention
   (`<registry>/qits/<application>:<sha>`), pulls it, starts it on the environment's network with a
   docker-native health gate, and — only once the fresh container reports healthy — decommissions
   the container it replaces.
4. Epic done, qits-projects deletes the environment: rows, containers, network.

A deployment that goes wrong is a recorded outcome, never a broken environment:

| status | meaning |
|---|---|
| `QUEUED` / `STARTING` | in flight (neither survives a restart; a startup sweep fails them) |
| `ACTIVE` | passed the health gate; its container serves the application |
| `IMAGE_MISSING` | the registry has no `qits/<application>:<sha>` — CI was green but nothing published an image |
| `FAILED` | docker refused, the container died, or the health gate expired — the old container stays |
| `DECOMMISSIONED` | was ACTIVE; replaced by a newer deployment that passed the gate |

**The cutover invariant:** the previous container is only *stopped* during the gate and removed
only after the new one passed it; a failed deployment removes the fresh container and restarts
what was stopped, so the previous deployment stays `ACTIVE` and serving. Stop-before-start —
rather than the overlapping cutover this service first shipped — is what makes stateful
applications deployable at all: one process per H2 file, one binder per published host port. The
pull happens before the stop, so even the registry's own application can be replaced. The
predecessor is *whatever holds the application's alias* on the environment's network, including
containers cd did not start — which is how a bootstrap's compose-seeded originals hand themselves
over to cd on their first pipeline deployment.

**Self-update is a handoff**, because the one predecessor cd never stops in-process is its own
container. Deploying `qits-cd` splits the cutover three ways: this instance starts the successor
(which retries on the H2 lock under its restart policy) and launches a detached **referee** — a
`--rm` container of the deployment's own image with the entrypoint swapped for the shell — then
returns with the row left `STARTING`. The referee stops the predecessor (freeing the lock),
waits up to the health timeout for the successor's gate, and removes whichever side lost — on
success the predecessor, on a missed gate the successor, restarting the predecessor. The
surviving instance records the outcome: the successor's startup sweep **adopts** a `STARTING`
row whose container is itself (ACTIVE, prior rows decommissioned), and a rolled-back
predecessor's sweep marks it FAILED as any interrupted row. There is no old↔new channel — the
H2 lock is the mutex, the deployment row is the shared state, and docker is the lifecycle;
neither instance referees its own succession.

## The conventions this service is made of

- **Image reference**:
  `<qits.artifacts.registry-host>/<qits.artifacts.image-repository>/<application>:<sha>`, shipped as
  `qits-artifacts:8080/qits/<application>:<commit-sha>`. The keys are named after their owner —
  qits-artifacts' registry — and qits-ci ships the same two, injecting them into pipeline step
  containers as `QITS_REGISTRY` / `QITS_IMAGE_REPOSITORY`. Nothing in the build notification names
  an image — cd derives the reference, which makes the tag a contract the publisher has to meet.
  Publishing is a repository's own last pipeline step (a `docker: true` step in qits-ci, tagging
  exactly this reference), so `IMAGE_MISSING` means that repository's pipeline publishes nothing or
  its tag broke the convention — not that nothing can publish.
- **One network per environment** (`qits-env-<name>`): two environments' stacks must never resolve
  each other's aliases. This is the documented two-stacks-collide-on-`qits-net` failure, avoided by
  construction rather than by discipline.
- **The network alias is the application name** and stays stable across deployments while
  container names (`qits-cd-<env>-<app>-<deployment-prefix>`) do not. Peers inside an environment
  address each other by application name, exactly as the platform's compose files do on qits-net.
- **The health gate runs inside the container** (`--health-cmd` curl'ing
  `http://localhost:8080<path>`, polled via `docker inspect`): cd never joins an environment's
  network, so the probe has to live where the network is. The image contract that buys: the image
  carries `curl` and listens on 8080 — both platform conventions. The default path is
  `/q/health/ready`; an application can name its own (`healthPath` per application — the platform's
  own services would name `/<segment>/q/health/ready`).
- **Application run arguments come from deployment config, never from the API.**
  `qits.cd.run-args.<application-name>` holds extra `docker run` arguments for that application —
  volumes, env, published ports, even a docker socket mount — whitespace-split and appended
  verbatim between cd's own flags and the image reference (no quoting: an argument that needs a
  space in it does not fit this seam). In compose that is
  `QITS_CD_RUN_ARGS_QITS_PROJECTS: "-v qits-projects-data:/data -e QUARKUS_DATASOURCE_PROJECTS_JDBC_URL=..."`.
  The source matters more than the feature: the environments API is deliberately open on qits-net,
  so nothing arriving over HTTP may shape a `docker run` argv — these arguments live in the same
  trust domain as the socket cd already holds, and changing them is a config diff on the
  deployment, visible like any other. Without an entry a container still gets exactly the
  identity variables below and nothing else. They are written *before* the run arguments, and
  docker keeps the last assignment of a repeated env key — so cd's variables are defaults an
  operator overrides by naming the same key, never the other way round.
- **The container is told who it is, in OpenTelemetry's vocabulary.** Every started container gets
  `QITS_ENVIRONMENT`, `QITS_APPLICATION`, and the resource identity the platform's logs and traces
  are bucketed by:

      OTEL_RESOURCE_ATTRIBUTES=service.version=<commit sha>,deployment.environment.name=<env>,service.instance.id=<container name>
      QUARKUS_OTEL_RESOURCE_ATTRIBUTES=<the same string>

  Three attributes, each a value cd genuinely holds: the deployment's sha (cd deploys
  sha-addressed images, so the sha *is* the released identity — it is not dressed up as a version
  number), the environment's name, and the container name cd just assigned. No workspace or
  repository ids: a platform service has neither.

  The second variable is not a duplicate by accident. Measured against Quarkus 3.34.6, the SDK
  resource is merged lowest-precedence-first as (1) the autoconfigured environment resource, where
  `OTEL_RESOURCE_ATTRIBUTES` lands, (2) Quarkus' build-time attributes — `service.name`,
  `service.version` **from the pom stamp**, `webengine.*` — over it, (3)
  `quarkus.otel.resource.attributes` over everything. So the neutral variable alone delivers the
  environment name and instance id but has its `service.version` silently replaced by the version
  baked into the image at build time, which is the stale identity this exists to correct. Both
  variables are built from one string, so they cannot disagree, and a non-Quarkus image ignores
  the second name.
- **Containers carry labels** (`qits.cd.environment`, `qits.cd.application`,
  `qits.cd.deployment`), and teardown finds them by label — so even containers whose rows are gone
  cannot be orphaned invisibly.
- **Deployed containers outlive the deployer**: `--restart unless-stopped`, and a qits-cd restart
  reaps nothing. The startup sweep fails in-flight *rows*; whatever was ACTIVE keeps serving.

## HTTP surface

Everything lives under this service's gateway segment, `/cd`; qits-gateway routes `/cd/*` verbatim,
and service-to-service calls on qits-net address the same paths. The machine surface is `/cd/api`
(`quarkus.rest.path`) and `/cd/q` (`quarkus.http.non-application-root-path`); the rest of the
segment is the client's.

| Path | Verbs | What |
|---|---|---|
| `/cd/` | GET | the Angular SPA, built from `service/src/main/webui` by Quinoa and served by this process (`quarkus.quinoa.ui-root-path`); unmatched paths under it fall back to `index.html`, so the client's own router gets its deep links — except under the prefixes below |
| `/cd` | GET, HEAD | a 301 to `/cd/`. Quinoa mounts at `/cd/*`, which does not match the bare segment (upstream quinoa #960); `webui/WebUiRedirect` is this service's answer |
| `/cd/api/environments` | GET, POST | list; create (machine call from the epic orchestration) |
| `/cd/api/environments/{id}` | GET, DELETE | one environment with its applications; teardown |
| `/cd/api/deployments?environmentId=` | GET | an environment's deployments, newest-first |
| `/cd/api/events/build-succeeded` | POST | the qits-ci intake (hidden from the OpenAPI document) |
| `/cd/q/openapi`, `/cd/q/swagger-ui` | GET | the API document |
| `/cd/q/health/ready` | GET | the readiness endpoint this service's own health gate curls on a peer |

The SPA takes the *whole* segment, so it is the one that can swallow the rest: the deep-link
fallback answers anything under `/cd` that matched no route, with `200 text/html`. That is right for
a person and wrong for a machine — and the machine this protects is qits-ci's intake, which is
fire-and-forget and would never report having been handed a web page. Quinoa **derives** the
exclusion list from `quarkus.rest.path` and `quarkus.http.non-application-root-path` when the key is
unset, and that derivation *is* exactly right today: every route here is JAX-RS under `/cd/api`, and
the only thing this service puts on the Vert.x router is the bare-segment redirect at `/cd`, which
is outside `/cd/*`. `quarkus.quinoa.ignored-path-prefixes=/api,/q` is spelled out anyway, ahead of
the need — the convention is that a new literal route and its prefix entry land in the same commit,
and a list already present makes that a one-line change. Two traps travel with it: setting the key
**replaces** the derivation rather than extending it (so `/api` and `/q` are repeated by hand), and
the values are matched **after** `ui-root-path` is stripped, so they are relative — `/cd/api`
written there matches nothing at all and is indistinguishable from not setting the key.

There is **no static machine token** in this service and there never was one. Two tracks of identity
reach it instead, and both come from the published `qits-auth-core` library:

- **Users** arrive through qits-gateway, which performs the login and asserts `X-Qits-User`. The
  environment surface is theirs; it authorizes nothing beyond that.
- **Machines** arrive with a bearer from qits-idp, validated here by `quarkus-oidc` against
  `aud=qits-cd`. The build-succeeded intake calls `MachineAuth.require()` — it is the one path
  nothing human reaches, so a bearer is the only credential its caller could ever hold. The
  environment writes stay unguarded for the mirror-image reason: a person drives them.

**The guard is off until a deployment turns it on.** `qits.auth.machine.required` defaults to
`false`, and off, `require()` returns at once — the intake accepts credential-free calls from
qits-net exactly as before. `QITS_AUTH_MACHINE_REQUIRED=true` flips it, and only after qits-ci is
actually sending a bearer: the notifier swallows delivery failures at debug, so a premature flip
stops deployments and says nothing.

The intake is a **fire-and-forget contract**: qits-ci's notifier swallows delivery failures at
debug, so a path mismatch between the two repos raises no error anywhere and deployments simply
stop. Both ends pin the literal path and both suites assert the absolute address.

## The client

[qits-spa-cd](https://github.com/QuicklyIterateTheSoftware/qits-spa-cd) — Angular 21, standalone
components, no SSR — is a submodule at `service/src/main/webui`, which is Quinoa's default
`web-ui-dir`, so the path is a convention rather than a setting. Its `angular.json` sets `baseHref`
to `"/cd/"`: the segment is spelled in **four** places that move together
(`quarkus.quinoa.ui-root-path`, `quarkus.rest.path`, `quarkus.http.non-application-root-path`, and
that one), and the fourth is in another repository where no build here can check it. A `baseHref`
that disagrees yields a page that loads and then fetches its own JavaScript from the wrong place,
and no server-side test can see it.

That gives this repo a clone rule with two halves:

    git clone … && git submodule update --init

- **The test suite needs neither node nor the client submodule.** Quinoa is disabled by default in
  test mode (`Quinoa is disabled by default in tests.`), so every `@QuarkusTest` here is green
  against an empty `webui/` on a machine with no node at all — `./mvnw test`.
- **Anything that reaches `package` needs the client**, and that includes `./mvnw verify`, which runs
  `package` on its way to failsafe. An uninitialised gitlink is an *empty directory*, and that is
  the one case Quinoa treats as a misconfiguration rather than "no client": augmentation stops at
  `No package.json found in Web UI directory`. This holds for every SPA-serving service, not just
  this one; `./mvnw test` is the command the clone-alone rule actually names.

The client depends on `@qits/ui-components`, which exists only on the platform's own npm registry —
reachable from a developer's host (its committed `.npmrc` names `localhost:8081`) and from
`qits-net`, and from **no address inside a docker build**. So the image build does not build the
client: `.config/qits/ci-post-receive.yml` builds it in a step container on `qits-net` and
`docker/Dockerfile` packages the bundle it was handed. Every SPA-serving service in the platform
does this, for the same reason.

## What is deliberately not here

- **Building or publishing images.** cd consumes the OCI registry; producing for it is the
  publisher's story. A green build with no image is `IMAGE_MISSING`, loudly.
- **DNS wiring.** qits-dns's plan names cd as the caller that will wire
  `<epic>.qits-dev.eu` on deploy (`PUT /dns/api/zones/{id}/records`, idempotent by design). That
  is a natural next seam here — after the more basic question of how a per-environment hostname
  becomes a reachable listener (the shared gateway routes by path, not Host) is settled.
- **The epic orchestration itself.** Creating `epic/<slug>` branches, switching a wrapper repo's
  submodule tracking, calling this service on epic start — all of that is qits-projects' side of
  the contract and does not exist yet; this service is the receiving half, complete and testable
  without it.
- **A templating engine for application config.** A deployed container gets `QITS_ENVIRONMENT`,
  `QITS_APPLICATION`, its OTel resource identity, and whatever the deployment's own
  `qits.cd.run-args.<name>` names (see the
  conventions above) — cd itself renders nothing, resolves nothing and stores nothing per
  application beyond that config family. Datasources, peer addresses and secrets stay the image's
  and the deployment's own story; cd only carries the deployment's words to `docker run`.

## Building and testing

    ./mvnw test                   # the clone-alone gate: no docker, no node, no submodule
    ./mvnw verify                 # + a package, so it needs the webui submodule and a node on PATH
    ./mvnw verify -DskipITs=false # + CdPackagedSurfaceIT against the packaged fast-jar
    ./mvnw verify -Dnative        # native binary (service/target/qits-cd) + the IT against it

The one seam that needs docker is faked in the suites (`FakeDeploymentDriver`); the docker CLI is
only ever exercised in a real deployment. `sdk env` (`.sdkmanrc`, GraalVM 25) gives `-Dnative` a
local native-image; without one Quarkus silently falls back to a container build — grep the log.

Everything from `verify` down runs `package`, and `package` is where Quinoa builds the client — so
those three lines want `git submodule update --init` and a node on `PATH` (the platform pin is
22.22.0; the Angular CLI at 21 wants `^20.19 || ^22.12 || >=24`). No port argument is needed
anywhere: the suite takes a free port (`quarkus.http.test-port=0`, in the service module's test
resources), because Quarkus' default test port 8081 is the published address of the platform's own
npm registry on the deployment host.

## Deploying it

See `docker/Dockerfile`'s header for the whole story. The short form: mount a volume at `/data`,
set `QUARKUS_DATASOURCE_CD_JDBC_URL=jdbc:h2:file:/data/cd/h2/cd`, mount the docker socket
(an explicit, root-equivalent decision), set `QITS_ARTIFACTS_REGISTRY_HOST` to where the *daemon*
pulls,
and put the container on qits-net as `qits-cd`. Then flip the gateway on:
`QITS_GATEWAY_PROXY_HOSTS_CD=qits-cd`.
