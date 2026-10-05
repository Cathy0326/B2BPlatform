#!/usr/bin/env bash
# Smoke test for the stack deployed by `kubectl apply -k k8s/overlays/ci` (run by the CI "kubernetes" job).
# Proves more than "pods are Running":
#   1. every Deployment rolls out and passes its readiness probe (Flyway migrations included)
#   2. both backend replicas are Ready (the production manifest runs 2)
#   3. the backend answers GraphQL with data it read from PostgreSQL through the Service DNS name
#   4. the frontend's server-side render fetched that data from the backend over cluster DNS
#   5. the hardening is enforced, not just declared: Pod Security rejects a privileged pod, and the
#      NetworkPolicies block a pod that is neither ingress-nginx nor the frontend from reaching the backend
set -euo pipefail
KUBECTL=${KUBECTL:-kubectl}
NS=quipmarket
k() { $KUBECTL -n "$NS" "$@"; }

echo "== Rollouts"
for d in postgres backend frontend; do
  k rollout status "deployment/$d" --timeout=300s
done
ready=$(k get deployment backend -o jsonpath='{.status.readyReplicas}')
echo "backend ready replicas: $ready"
[ "$ready" = "2" ] || { echo "expected 2 ready backend replicas"; exit 1; }

echo "== Port-forward"
k port-forward svc/backend 8080:8080 >/tmp/pf-backend.log 2>&1 &
k port-forward svc/frontend 3000:3000 >/tmp/pf-frontend.log 2>&1 &
trap 'kill $(jobs -p) 2>/dev/null || true' EXIT
for _ in $(seq 1 30); do
  curl -fs http://localhost:8080/actuator/health/readiness >/dev/null && curl -fs -o /dev/null http://localhost:3000/ && break
  sleep 1
done

echo "== Backend readiness"
curl -fsS http://localhost:8080/actuator/health/readiness | tee /tmp/readiness.json; echo
grep -q '"UP"' /tmp/readiness.json

echo "== GraphQL (data from PostgreSQL)"
curl -fsS -H 'Content-Type: application/json' \
  -d '{"query":"{ equipment { id title } auctions { id } }"}' http://localhost:8080/graphql >/tmp/gql.json
equipment=$(grep -o '"id":"eq-[^"]*"' /tmp/gql.json | wc -l)
auctions=$(grep -o '"id":"au-[^"]*"' /tmp/gql.json | wc -l)
echo "equipment: $equipment, auctions: $auctions"
[ "$equipment" -gt 0 ] && [ "$auctions" -gt 0 ] || { cat /tmp/gql.json; exit 1; }

echo "== Frontend SSR -> backend over cluster DNS"
curl -fsS http://localhost:3000/ >/tmp/home.html
grep -q 'live GraphQL API' /tmp/home.html || { echo "frontend is not in GraphQL mode"; exit 1; }
first_id=$(grep -o '"id":"eq-[^"]*"' /tmp/gql.json | head -1 | cut -d'"' -f4)
grep -q "/equipment/$first_id" /tmp/home.html || { echo "SSR page does not list $first_id from the API"; exit 1; }
echo "server-rendered home page lists $first_id from the backend"

echo "== Pod Security Admission rejects a privileged pod"
if out=$(k run psa-probe --image=busybox:1.37 --privileged --restart=Never --dry-run=server 2>&1); then
  echo "a privileged pod was admitted: $out"; exit 1
fi
echo "$out" | grep -q 'violates PodSecurity' || { echo "unexpected error: $out"; exit 1; }
echo "rejected: $(echo "$out" | grep -o 'violates PodSecurity "restricted[^"]*"')"

echo "== NetworkPolicy blocks an unlisted pod from the backend"
probe='{"spec":{"securityContext":{"runAsNonRoot":true,"runAsUser":65534,"seccompProfile":{"type":"RuntimeDefault"}},
  "containers":[{"name":"np-probe","image":"busybox:1.37",
  "command":["sh","-c","nslookup backend >/dev/null 2>&1 || { echo NO-DNS; exit 0; }; wget -q -T 5 -O /dev/null http://backend:8080/actuator/health/readiness && echo REACHED || echo BLOCKED"],
  "securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}'
result=$(k run np-probe --image=busybox:1.37 --restart=Never --rm -i --quiet --pod-running-timeout=2m --overrides="$probe" 2>&1 | tail -1)
echo "probe: $result"
# BLOCKED only counts if the name resolved, i.e. the connection itself was refused by the policy.
[ "$result" = BLOCKED ] || { echo "expected BLOCKED (DNS works, connection denied), got: $result"; exit 1; }

echo "✅ Smoke test passed"
