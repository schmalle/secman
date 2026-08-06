#!/bin/bash
# =============================================================================
# Secman EKS — apply manifests, wait for rollout, print the ALB hostname
# =============================================================================
#   ./docker/eks/deploy.sh
#
# Assumes: the cluster exists (cluster-up.sh), the AWS Load Balancer
# Controller + External Secrets Operator are installed (docs/DOCKER_EKS.md),
# secrets-setup.sh has run, and every REPLACE_WITH_* placeholder in
# docker/eks/*.yaml has been filled in (RDS endpoint, ECR image refs, ACM
# cert ARN, IRSA role ARN).
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if grep -rl 'REPLACE_WITH_' "$SCRIPT_DIR"/*.yaml >/dev/null 2>&1; then
  echo "✗ Unfilled REPLACE_WITH_* placeholders remain in docker/eks/*.yaml:" >&2
  grep -rl 'REPLACE_WITH_' "$SCRIPT_DIR"/*.yaml >&2
  echo "Fill these in (RDS endpoint, ECR image refs, ACM cert ARN, IRSA role ARN) before deploying." >&2
  exit 1
fi

echo "[deploy] kubectl apply -k $SCRIPT_DIR"
kubectl apply -k "$SCRIPT_DIR"

echo "[deploy] Waiting for rollout..."
kubectl -n secman rollout status deployment/secman-backend --timeout=300s
kubectl -n secman rollout status deployment/secman-frontend --timeout=300s

echo "[deploy] Waiting for the ALB hostname (can take a few minutes on first apply)..."
HOSTNAME=""
for _ in $(seq 1 60); do
  HOSTNAME=$(kubectl -n secman get ingress secman -o jsonpath='{.status.loadBalancer.ingress[0].hostname}' 2>/dev/null || true)
  [[ -n "$HOSTNAME" ]] && break
  sleep 5
done

if [[ -n "$HOSTNAME" ]]; then
  echo "[deploy] ALB hostname: $HOSTNAME"
  echo "[deploy] Point your DNS record at this hostname, then verify: curl -sk https://$HOSTNAME/health"
else
  echo "[deploy] ALB hostname not yet assigned — check: kubectl -n secman describe ingress secman"
fi
