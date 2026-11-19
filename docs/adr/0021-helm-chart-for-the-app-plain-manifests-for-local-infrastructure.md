# 21. A Helm chart for the app; plain manifests for the local infrastructure

Date: 2026-11-20
Status: Accepted

## Context

Step 5.2 runs Dispatch on Kubernetes, locally first (kind), so that the same app definition can later move to a real cluster. Two different things need describing:

- **The app**: ours, deployed often, configured per environment (replicas, image tag, settings, resources).
- **Its infrastructure**: Postgres with PostGIS and pgvector, Kafka, Keycloak, Redis, SeaweedFS and Mailpit. Locally it has to exist; in production most of it would be managed services or operators.

The machine has 7.6 GB of RAM, which rules out anything heavy that we don't need.

## Decision

1. **A Helm chart for the app** (`infra/helm/dispatch`): a Deployment, a Service, a ConfigMap of settings, a PodDisruptionBudget, and an optional HorizontalPodAutoscaler. Values change per environment; a settings change rolls the pods (a checksum annotation).
2. **Secrets stay outside the chart**: the chart names an existing Secret. No password sits in values files or in Helm's release history. Locally `scripts/k8s-up.sh` creates the Secret; in production External Secrets would.
3. **Plain manifests for the local infrastructure** (`infra/k8s`, applied with `kubectl apply -k`): one instance each, StatefulSets with volumes for Postgres, Kafka and SeaweedFS.
4. **The pod contract** (what T37's image makes possible):
   - Probes:
     - startup: `/livez`, up to 3 minutes;
     - liveness: `/livez`, the process only;
     - readiness: `/readyz`, the app and the database.
   - Resources: memory request equal to the limit (1 GiB; the heap is half of it, since the app needs ~420 MB outside the heap: 640 Mi was OOMKilled), and a CPU request with no CPU limit.
   - Shutdown: a 5 s preStop sleep, then up to 20 s of graceful shutdown, inside the 30 s grace period.
   - Rollout: a rolling update with `maxUnavailable: 0`, so an old pod leaves only once a new one is ready.
   - Security: non-root uid 65532, a read-only root filesystem, no capabilities, no service account token.
   - `enableServiceLinks: false`, so Kubernetes' `REDIS_PORT=tcp://…` variables can't override the app's settings.
5. **Same URLs as compose.** kind maps the app's and Keycloak's NodePorts to `localhost:8080` and `localhost:8180`, so the demo scripts, the web app and the token issuer don't change. The app fetches Keycloak's keys inside the cluster (`KEYCLOAK_JWKS_URI`) while it checks the issuer users see.

## Alternatives considered

| Option | Verdict |
|---|---|
| Plain manifests or Kustomize for the app too | Fine for one environment, but per-environment values (image tag, replicas, settings) and release history with `helm rollback` are what Helm is for. Kustomize stays for the infra, where there is nothing to vary. |
| CloudNativePG for Postgres | The right production choice: replicas, failover, backups to S3, rolling minor upgrades. Locally it adds an operator, its webhooks and its own Postgres images, which would need PostGIS and pgvector rebuilt into them, for a single instance. Production would use it (or a managed database) instead of `infra/k8s/postgres.yaml`. |
| Strimzi for Kafka | Same reasoning: for three brokers with rolling restarts and topic resources, yes. For one local broker, an operator costs more memory than the broker. |
| Bitnami charts for the infrastructure | Their free images have moved behind a subscription, and they hide settings we want visible while learning. |
| An Ingress controller | Adds a controller pod for one host. NodePorts mapped by kind are enough locally; the cloud VM (5.3) puts a reverse proxy with TLS in front. |

## Consequences

- Running two replicas found three bugs that a single instance (compose, tests) never shows. Each is fixed, with a test:
  - two pods racing to create the S3 bucket;
  - a JVM too big for its limit, hidden by Docker's swap;
  - the flag admin API reading a per-pod snapshot (stale ETags, 412 on save).
- A rolling update under constant traffic answered 1,026 of 1,026 requests. With Postgres stopped, the pods went unready without restarting (T38).
- One command (`scripts/k8s-up.sh`) builds the whole cluster, and running it again updates it. `kind delete cluster --name dispatch` removes it.
- Photo uploads don't work from outside the cluster: presigned URLs name `http://seaweedfs:8333`, which only pods can reach. The storage settings have a single endpoint; a separate public endpoint (or a public host for SeaweedFS behind the 5.3 proxy) fixes it when photos are needed there.
- There is no observability collector in the cluster, so tracing and the OTLP metrics push are off there. The compose stack keeps Grafana.
- Production still needs real choices: managed or operator-run Postgres and Kafka, Keycloak in production mode with its own database, an Ingress with TLS, and secrets from a secret store.
