#!/bin/bash
# =============================================================================
# Secman EKS — create AWS Secrets Manager secrets + the IRSA role that lets
# External Secrets Operator read them
# =============================================================================
#   export AWS_REGION=eu-central-1
#   export EKS_CLUSTER_NAME=secman        # must match docker/eks/cluster.yaml
#   ./docker/eks/secrets-setup.sh
#   ./docker/eks/secrets-setup.sh --optional   # also prompts for SMTP/OpenRouter
#
# Idempotent — skips secrets/policies that already exist. Uses the same
# secman/* secret names as docs/DOCKER_AWS_DEPLOY_RUNBOOK.md (Step 5), so an
# ECS deployment and this EKS deployment can share one set of secrets.
#
# Requires: the EKS cluster already exists with iam.withOIDC: true (see
# docker/eks/cluster.yaml / cluster-up.sh) — the OIDC provider must be
# associated before the IRSA trust policy below can reference it.
# =============================================================================

set -euo pipefail

: "${AWS_REGION:?Set AWS_REGION}"
: "${EKS_CLUSTER_NAME:?Set EKS_CLUSTER_NAME (must match docker/eks/cluster.yaml metadata.name)}"
ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"

create_secret_if_missing() {
  local name="$1" value="$2"
  if aws secretsmanager describe-secret --secret-id "$name" --region "$AWS_REGION" >/dev/null 2>&1; then
    echo "[secrets-setup] $name already exists — skipping."
  else
    aws secretsmanager create-secret --name "$name" --secret-string "$value" --region "$AWS_REGION" >/dev/null
    echo "[secrets-setup] Created $name."
  fi
}

echo "[secrets-setup] Creating secman/* secrets in Secrets Manager ($AWS_REGION)..."
read -rsp "RDS password for the 'secman' DB user (Step 3 of docs/DOCKER_EKS.md): " DB_PW; echo
create_secret_if_missing secman/db-password "$DB_PW"
create_secret_if_missing secman/jwt-secret "$(openssl rand -base64 32)"
create_secret_if_missing secman/enc-password "$(openssl rand -hex 32)"
create_secret_if_missing secman/enc-salt "$(openssl rand -hex 8)"

if [[ "${1:-}" == "--optional" ]]; then
  read -rsp "SMTP password (blank to skip): " SMTP_PW; echo
  [[ -n "$SMTP_PW" ]] && create_secret_if_missing secman/smtp-password "$SMTP_PW"
  read -rsp "OpenRouter API key (blank to skip): " OR_KEY; echo
  [[ -n "$OR_KEY" ]] && create_secret_if_missing secman/openrouter-key "$OR_KEY"
fi

echo "[secrets-setup] Creating IAM policy scoped to secman/* secrets..."
POLICY_FILE="$(mktemp)"
cat > "$POLICY_FILE" <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Action": ["secretsmanager:GetSecretValue", "secretsmanager:DescribeSecret"],
    "Resource": "arn:aws:secretsmanager:$AWS_REGION:$ACCOUNT_ID:secret:secman/*"
  }]
}
JSON

aws iam create-policy \
  --policy-name secmanExternalSecretsPolicy \
  --policy-document "file://$POLICY_FILE" >/dev/null 2>&1 \
  || echo "[secrets-setup] Policy secmanExternalSecretsPolicy already exists — skipping."
rm -f "$POLICY_FILE"

echo "[secrets-setup] Creating IRSA role for the secman-external-secrets ServiceAccount..."
OIDC_ISSUER=$(aws eks describe-cluster --name "$EKS_CLUSTER_NAME" --region "$AWS_REGION" \
  --query "cluster.identity.oidc.issuer" --output text)
OIDC_ID="${OIDC_ISSUER#https://}"
OIDC_PROVIDER_ARN="arn:aws:iam::$ACCOUNT_ID:oidc-provider/$OIDC_ID"

TRUST_FILE="$(mktemp)"
cat > "$TRUST_FILE" <<JSON
{
  "Version": "2012-10-17",
  "Statement": [{
    "Effect": "Allow",
    "Principal": { "Federated": "$OIDC_PROVIDER_ARN" },
    "Action": "sts:AssumeRoleWithWebIdentity",
    "Condition": {
      "StringEquals": {
        "$OIDC_ID:sub": "system:serviceaccount:secman:secman-external-secrets",
        "$OIDC_ID:aud": "sts.amazonaws.com"
      }
    }
  }]
}
JSON

aws iam create-role \
  --role-name secmanExternalSecretsRole \
  --assume-role-policy-document "file://$TRUST_FILE" >/dev/null 2>&1 \
  || echo "[secrets-setup] Role secmanExternalSecretsRole already exists — trust policy NOT updated, delete+re-run if the OIDC provider changed."
rm -f "$TRUST_FILE"

aws iam attach-role-policy \
  --role-name secmanExternalSecretsRole \
  --policy-arn "arn:aws:iam::$ACCOUNT_ID:policy/secmanExternalSecretsPolicy"

echo ""
echo "[secrets-setup] Done. Set this role ARN in docker/eks/serviceaccount.yaml"
echo "(eks.amazonaws.com/role-arn annotation):"
echo "  arn:aws:iam::$ACCOUNT_ID:role/secmanExternalSecretsRole"
