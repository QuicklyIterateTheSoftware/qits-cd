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
   deployment and, on its worker: derives the image reference by convention
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
over to cd on their first pipeline deployment. The one predecessor cd refuses to stop is its own
container: the planned self-update mechanism is the successor shutting down the predecessor, and
until it exists a `qits-cd` deployment records a FAILED row saying exactly that.

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
  deployment, visible like any other. Without an entry a container still gets exactly
  `QITS_ENVIRONMENT` and `QITS_APPLICATION` and nothing else.
- **Containers carry labels** (`qits.cd.environment`, `qits.cd.application`,
  `qits.cd.deployment`), and teardown finds them by label — so even containers whose rows are gone
  cannot be orphaned invisibly.
- **Deployed containers outlive the deployer**: `--restart unless-stopped`, and a qits-cd restart
  reaps nothing. The startup sweep fails in-flight *rows*; whatever was ACTIVE keeps serving.

## HTTP surface

Everything lives under `/cd/api` (`quarkus.rest.path`); qits-gateway routes `/cd/*` verbatim, and
service-to-service calls on qits-net address the same paths.

| Path | Verbs | What |
|---|---|---|
| `/cd/api/environments` | GET, POST | list; create (machine call from the epic orchestration) |
| `/cd/api/environments/{id}` | GET, DELETE | one environment with its applications; teardown |
| `/cd/api/deployments?environmentId=` | GET | an environment's deployments, newest-first |
| `/cd/api/events/build-succeeded` | POST | the qits-ci intake (hidden from the OpenAPI document) |
| `/cd/q/openapi`, `/cd/q/swagger-ui` | GET | the API document |

There is **no machine token** in this service, and that is a decision, not an omission. Nothing of
cd is on the gateway's token-free allowlist, so every `/cd/*` path — the intake included — is
session-guarded at the front door; on qits-net, callers are trusted, and that is where qits-ci's
notifier and the epic orchestration actually dial. The `qits.ci.token` pattern exists for paths
that *are* allowlisted at the gateway (ci's intake is; cd's is not, because its sender never
traverses the gateway). Hardening intra-network machine surfaces is a later, platform-wide
decision, deliberately not half-solved here — and if the intake ever needs a session-free
front-door spelling, the gateway allowlist entry and a write guard here land in the same change.
User identity is the gateway's `X-Qits-User` header (see `service/…/security/`); this service
authenticates nothing and authorizes nothing.

The intake is a **fire-and-forget contract**: qits-ci's notifier swallows delivery failures at
debug, so a path mismatch between the two repos raises no error anywhere and deployments simply
stop. Both ends pin the literal path and both suites assert the absolute address.

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
  `QITS_APPLICATION`, and whatever the deployment's own `qits.cd.run-args.<name>` names (see the
  conventions above) — cd itself renders nothing, resolves nothing and stores nothing per
  application beyond that config family. Datasources, peer addresses and secrets stay the image's
  and the deployment's own story; cd only carries the deployment's words to `docker run`.

## Building and testing

    ./mvnw verify                 # the gate: green on a clone, no docker, no credentials
    ./mvnw verify -DskipITs=false # + CdPackagedSurfaceIT against the packaged fast-jar
    ./mvnw verify -Dnative        # native binary (service/target/qits-cd) + the IT against it

The one seam that needs docker is faked in the suites (`FakeDeploymentDriver`); the docker CLI is
only ever exercised in a real deployment. `sdk env` (`.sdkmanrc`, GraalVM 25) gives `-Dnative` a
local native-image; without one Quarkus silently falls back to a container build — grep the log.

## Deploying it

See `docker/Dockerfile`'s header for the whole story. The short form: mount a volume at `/data`,
set `QUARKUS_DATASOURCE_CD_JDBC_URL=jdbc:h2:file:/data/cd/h2/cd`, mount the docker socket
(an explicit, root-equivalent decision), set `QITS_ARTIFACTS_REGISTRY_HOST` to where the *daemon*
pulls,
and put the container on qits-net as `qits-cd`. Then flip the gateway on:
`QITS_GATEWAY_PROXY_HOSTS_CD=qits-cd`.
