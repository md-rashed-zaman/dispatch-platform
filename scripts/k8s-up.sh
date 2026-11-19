#!/usr/bin/env bash
# Dispatch on a local Kubernetes cluster (step 5.2): a kind cluster, the infrastructure as plain manifests
# (infra/k8s), the app from its Helm chart (infra/helm/dispatch), and the seed. Run it again to update;
# every step is safe to repeat. Afterwards the app answers at http://localhost:8080, as with compose, so
# scripts/demo-phase4.sh works unchanged.
#
# Needs: docker, kind, kubectl, helm. Stops the compose stack first (same ports, and memory).
# Remove it all: kind delete cluster --name dispatch
set -euo pipefail
cd "$(dirname "$0")/.."

CLUSTER=dispatch
COMPOSE=(docker compose -f infra/compose/docker-compose.yml)
say() { printf '\e[1m== %s\e[0m\n' "$1"; }

say "Stopping the compose stack (its data stays in its volumes)"
"${COMPOSE[@]}" stop > /dev/null 2>&1 || true

if ! kind get clusters 2> /dev/null | grep -qx "$CLUSTER"; then
  say "Creating the kind cluster"
  kind create cluster --config infra/kind/cluster.yaml --wait 2m
fi
kubectl config use-context "kind-$CLUSTER" > /dev/null

say "Images: the app (from the Maven jar, step 5.1) and Postgres with PostGIS and pgvector"
if [[ ${BUILD:-1} == 1 ]]; then
  (cd app && ./mvnw -q -DskipTests package)
  docker build -q -t dispatch-app:local app > /dev/null
fi
docker image inspect dispatch-postgres:local > /dev/null 2>&1 || "${COMPOSE[@]}" build postgres
# Tagged by content: a new build changes the pod template, so Helm rolls the pods; an unchanged one doesn't.
TAG=$(docker image inspect -f '{{.Id}}' dispatch-app:local | cut -d: -f2 | cut -c1-12)
docker tag dispatch-app:local "dispatch-app:$TAG"
kind load docker-image --name "$CLUSTER" "dispatch-app:$TAG" dispatch-postgres:local

say "Secrets and configuration files (local values only; production: External Secrets)"
apply() { kubectl apply -f - > /dev/null; }
secret_args=(--from-literal=POSTGRES_USER=dispatch --from-literal=POSTGRES_PASSWORD=dispatch
  --from-literal=DB_APP_PASSWORD=dispatch_app --from-literal=S3_ACCESS_KEY=dispatch
  --from-literal=S3_SECRET_KEY=dispatch-secret)
# The Gemini key only from the environment (export $(grep ^GEMINI_API_KEY= .env)); without it the AI features
# answer "unavailable" and the rest works.
[[ -n ${GEMINI_API_KEY:-} ]] && secret_args+=(--from-literal=GEMINI_API_KEY="$GEMINI_API_KEY")
kubectl create secret generic dispatch-secrets "${secret_args[@]}" --dry-run=client -o yaml | apply
kubectl create secret generic seaweedfs-s3 --from-file=infra/compose/seaweedfs/s3.json --dry-run=client -o yaml | apply
kubectl create configmap keycloak-realm --from-file=infra/compose/keycloak/dispatch-realm.json \
  --dry-run=client -o yaml | apply

say "Infrastructure: Postgres, Kafka, Keycloak, Redis, SeaweedFS, Mailpit"
kubectl apply -k infra/k8s > /dev/null
for workload in statefulset/postgres statefulset/kafka statefulset/seaweedfs deployment/redis deployment/mailpit \
  deployment/keycloak; do
  kubectl rollout status "$workload" --timeout=5m
done

say "The app, from its Helm chart"
helm upgrade --install dispatch infra/helm/dispatch --set image.tag="$TAG" --wait --timeout 8m

if [[ $(kubectl exec postgres-0 -- psql -U dispatch -d dispatch -tAc 'select count(*) from company') == 0 ]]; then
  say "Seed data (the app's migrations ran first)"
  kubectl exec -i postgres-0 -- psql -q -U dispatch -d dispatch -v ON_ERROR_STOP=1 < infra/seed/dev-seed.sql
fi

kubectl get pods -o wide
say "Ready: http://localhost:8080 (API), http://localhost:8180 (Keycloak), http://localhost:8025 (Mailpit)"
