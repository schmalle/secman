#!/bin/bash
# =============================================================================
# Secman EKS — build the backend/frontend images and push them to ECR
# =============================================================================
#   export AWS_REGION=eu-central-1
#   ./docker/eks/build-push.sh
#
# Apple Silicon (build for x86 Fargate, the default architecture):
#   export BUILD_PLATFORM=linux/amd64
#   ./docker/eks/build-push.sh
#
# After pushing, update the `image:` field in deployment-backend.yaml and
# deployment-frontend.yaml (or set IMAGE_TAG to a pinned tag, e.g. a git SHA,
# instead of the default `latest` for an auditable rollback trail).
# =============================================================================

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

: "${AWS_REGION:?Set AWS_REGION, e.g. export AWS_REGION=eu-central-1}"
ACCOUNT_ID="${AWS_ACCOUNT_ID:-$(aws sts get-caller-identity --query Account --output text)}"
TAG="${IMAGE_TAG:-latest}"
REGISTRY="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"

PLATFORM_FLAG=()
if [[ -n "${BUILD_PLATFORM:-}" ]]; then
  PLATFORM_FLAG=(--platform "$BUILD_PLATFORM")
fi

echo "[build-push] Building backend JAR..."
( cd "$PROJECT_ROOT" && ./gradlew :backendng:shadowJar -x test --no-daemon )
cp "$PROJECT_ROOT"/src/backendng/build/libs/*-all.jar "$PROJECT_ROOT/docker/backend/app.jar"

echo "[build-push] Authenticating docker to ECR ($REGISTRY)..."
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY"

for repo in secman-backend secman-frontend; do
  aws ecr describe-repositories --repository-names "$repo" --region "$AWS_REGION" >/dev/null 2>&1 \
    || aws ecr create-repository --repository-name "$repo" --region "$AWS_REGION" >/dev/null
done

echo "[build-push] Building secman-backend..."
docker build "${PLATFORM_FLAG[@]}" -f "$PROJECT_ROOT/docker/backend/Dockerfile" \
  -t "$REGISTRY/secman-backend:$TAG" "$PROJECT_ROOT"
docker push "$REGISTRY/secman-backend:$TAG"

echo "[build-push] Building secman-frontend..."
docker build "${PLATFORM_FLAG[@]}" -f "$PROJECT_ROOT/docker/frontend/Dockerfile" \
  -t "$REGISTRY/secman-frontend:$TAG" "$PROJECT_ROOT"
docker push "$REGISTRY/secman-frontend:$TAG"

echo ""
echo "[build-push] Pushed:"
echo "  $REGISTRY/secman-backend:$TAG"
echo "  $REGISTRY/secman-frontend:$TAG"
echo ""
echo "Update the image: field in docker/eks/deployment-backend.yaml and"
echo "docker/eks/deployment-frontend.yaml with these references, then re-run"
echo "docker/eks/deploy.sh."
