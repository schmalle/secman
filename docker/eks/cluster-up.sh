#!/bin/bash
# =============================================================================
# Secman EKS — create the cluster from docker/eks/cluster.yaml
# =============================================================================
# Provisions a real, billable EKS control plane + Fargate profile. Takes
# roughly 15-20 minutes. See docs/DOCKER_EKS.md for what to do before and
# after (VPC prerequisites, IRSA roles, add-on installs).
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v eksctl >/dev/null 2>&1; then
  echo "✗ eksctl not found. Install it first: https://eksctl.io/installation/" >&2
  exit 1
fi

echo "This will run: eksctl create cluster -f $SCRIPT_DIR/cluster.yaml"
echo "That creates real, billable AWS resources (EKS control plane + Fargate profile)."
read -rp "Continue? [y/N] " CONFIRM
[[ "$CONFIRM" == "y" || "$CONFIRM" == "Y" ]] || { echo "Aborted."; exit 1; }

eksctl create cluster -f "$SCRIPT_DIR/cluster.yaml"
