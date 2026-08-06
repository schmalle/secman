#!/bin/bash
# =============================================================================
# Secman EKS — tear down the app, optionally the whole cluster
# =============================================================================
#   ./docker/eks/teardown.sh                   # delete only the app (this kustomization)
#   ./docker/eks/teardown.sh --delete-cluster   # also delete the EKS cluster
#
# Cluster deletion is irreversible and removes billable AWS resources —
# gated behind an explicit flag and a typed confirmation, never silent.
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "[teardown] kubectl delete -k $SCRIPT_DIR"
kubectl delete -k "$SCRIPT_DIR" --ignore-not-found

if [[ "${1:-}" == "--delete-cluster" ]]; then
  CLUSTER_NAME=$(awk '/^metadata:/{f=1} f && /name:/{print $2; exit}' "$SCRIPT_DIR/cluster.yaml")
  echo "[teardown] About to delete the ENTIRE EKS cluster '$CLUSTER_NAME' ($SCRIPT_DIR/cluster.yaml)."
  echo "[teardown] This is irreversible and removes the control plane + Fargate profile."
  read -rp "Type the cluster name to confirm: " CONFIRM_NAME
  if [[ "$CONFIRM_NAME" != "$CLUSTER_NAME" ]]; then
    echo "[teardown] Name did not match '$CLUSTER_NAME' — aborting cluster deletion."
    exit 1
  fi
  eksctl delete cluster -f "$SCRIPT_DIR/cluster.yaml"
fi
