# Deploying Secman to AWS EKS/Fargate — Step-by-Step Runbook

A single, sequential, copy-paste procedure for getting Secman running on
**Amazon EKS with Fargate**: two Kubernetes Deployments (frontend, backend)
on serverless Fargate compute, fronted by an ALB, with the database on
**Amazon RDS** and secrets synced from **AWS Secrets Manager** via the
**External Secrets Operator**.

This is the Kubernetes-native counterpart to
[docs/DOCKER_AWS_DEPLOY_RUNBOOK.md](DOCKER_AWS_DEPLOY_RUNBOOK.md) (which
deploys the same app to ECS/Fargate instead). Read
[EKS vs ECS/Fargate](#eks-vs-ecsfargate) first if you haven't already decided
which one you want — for most Secman-only deployments, **ECS/Fargate is the
simpler default**; use this runbook if you're standardizing on Kubernetes.

```
                                     ┌────────────────── EKS Fargate ──────────────────┐
ALB (TLS :443, ACM cert)  ─────────► │  Deployment: secman-frontend (nginx + Astro SSR) │
  re-encrypts HTTPS to :8443         │    Service: secman-frontend :8443                │
                                     │       │                                          │
                                     │       ▼ (nginx proxies /api,/oauth,/mcp,/health)  │
                                     │  Deployment: secman-backend (Micronaut)           │
                                     │    Service: secman-backend :8080                  │
                                     └───────┼──────────────────────────────────────────┘
                                             ▼
                                     Amazon RDS (MariaDB 11.4)

External Secrets Operator syncs secman/* AWS Secrets Manager entries into a
native `secman-secrets` Kubernetes Secret (IRSA, no static AWS keys).
```

---

## Step 0 — Prerequisites

- **Tools**: `aws` CLI v2, `eksctl`, `kubectl`, `helm`, `docker`, `openssl`.
- **A VPC** with private subnets (for the Fargate pods and RDS) and public
  subnets (for the ALB) — same requirement as the ECS runbook's Step 0.
- **An ACM certificate** for your public hostname (e.g. `secman.example.com`)
  in the deployment region.
- **Build host RAM**: the backend JAR build needs ~4 GB (same Gradle/Kotlin
  compile as every other Secman image).

Set these shell variables once and reuse them throughout:

```bash
export AWS_REGION=eu-central-1
export EKS_CLUSTER_NAME=secman
export ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
export PUBLIC_URL=https://secman.example.com   # your real public hostname
```

---

## Step 1 — Build and push the images

```bash
docker/eks/build-push.sh
```

Builds the backend JAR, builds `docker/backend/Dockerfile` and
`docker/frontend/Dockerfile` (the same split images used by
`docker-compose.split.yaml` for local/macOS use — see `docker/README.md`),
and pushes both to ECR (`secman-backend`, `secman-frontend`).

**Apple Silicon**: Fargate defaults to x86_64 — `export
BUILD_PLATFORM=linux/amd64` before running the script, or run Fargate on
ARM64 (add `runtimePlatform: { cpuArchitecture: ARM64 }` per-pod isn't a
thing in K8s the way it is in ECS task defs; instead set
`spec.template.spec.nodeSelector.kubernetes.io/arch: arm64` in
`deployment-*.yaml` if you build ARM64 images and want Fargate to match).

---

## Step 2 — Provision the database (RDS MariaDB)

Identical to the ECS runbook's Step 3: an RDS **MariaDB 11.4** instance
(`db.t3.small` to start), an **empty** `secman` database (Hibernate
auto-DDL creates the schema on first boot — leave
`FLYWAY_DATASOURCES_DEFAULT_ENABLED=false`), and a security group that will
allow inbound 3306 from the EKS pods' security group (Fargate pods get their
own ENI/security group via `awsvpc`-style networking — the exact SG is
determined by the cluster's Fargate pod execution role/subnet config; the
simplest approach is to allow the whole VPC CIDR on 3306, or the specific
subnets the Fargate profile uses).

```
jdbc:mariadb://mydb.xyz.eu-central-1.rds.amazonaws.com:3306/secman
```

---

## Step 3 — Create the cluster

```bash
# Edit docker/eks/cluster.yaml: set region to $AWS_REGION.
docker/eks/cluster-up.sh
```

Creates the EKS control plane + a Fargate profile scoped to the `secman` and
`kube-system` namespaces, and associates an OIDC provider (`iam.withOIDC:
true`) for IRSA. Takes ~15-20 minutes.

**Fargate constraint to know up front**: pods only schedule into namespaces
matched by a Fargate profile. Every add-on below (`aws-load-balancer-controller`,
`external-secrets`) is installed into `kube-system` specifically so it lands
on Fargate too — installing an add-on into its own namespace (a common Helm
chart default) will leave it permanently `Pending` on a Fargate-only
cluster, since there are no EC2 nodes to fall back to.

---

## Step 4 — Install cluster add-ons

### AWS Load Balancer Controller (provisions the ALB from `Ingress`)

```bash
curl -O https://raw.githubusercontent.com/kubernetes-sigs/aws-load-balancer-controller/v2.9.0/docs/install/iam_policy.json
aws iam create-policy --policy-name AWSLoadBalancerControllerIAMPolicy \
  --policy-document file://iam_policy.json

eksctl create iamserviceaccount \
  --cluster "$EKS_CLUSTER_NAME" --region "$AWS_REGION" \
  --namespace kube-system --name aws-load-balancer-controller \
  --attach-policy-arn "arn:aws:iam::$ACCOUNT_ID:policy/AWSLoadBalancerControllerIAMPolicy" \
  --approve

helm repo add eks https://aws.github.io/eks-charts && helm repo update
helm install aws-load-balancer-controller eks/aws-load-balancer-controller \
  -n kube-system \
  --set clusterName="$EKS_CLUSTER_NAME" \
  --set serviceAccount.create=false \
  --set serviceAccount.name=aws-load-balancer-controller
```

### External Secrets Operator (syncs Secrets Manager → Kubernetes Secret)

```bash
helm repo add external-secrets https://charts.external-secrets.io && helm repo update
# -n kube-system (not --create-namespace into its own namespace) — see the
# Fargate constraint above.
helm install external-secrets external-secrets/external-secrets -n kube-system
```

Verify both are running (should be `Running`, not `Pending`):

```bash
kubectl -n kube-system get pods -l app.kubernetes.io/name=aws-load-balancer-controller
kubectl -n kube-system get pods -l app.kubernetes.io/name=external-secrets
```

---

## Step 5 — Create secrets and the ESO IRSA role

```bash
docker/eks/secrets-setup.sh
# or, to also set SMTP/OpenRouter secrets:
docker/eks/secrets-setup.sh --optional
```

Creates the same `secman/db-password`, `secman/jwt-secret`,
`secman/enc-password`, `secman/enc-salt` secrets in Secrets Manager as the
ECS runbook (Step 4 there), an IAM policy scoped to `secman/*`, and an IRSA
role (`secmanExternalSecretsRole`) trusting the cluster's OIDC provider for
the `system:serviceaccount:secman:secman-external-secrets` identity.

> ⚠️ **Never rotate** `SECMAN_ENCRYPTION_PASSWORD` (`enc-password`) or
> `SECMAN_ENCRYPTION_SALT` (`enc-salt`) after data exists — same warning as
> the ECS runbook.

---

## Step 6 — Fill in the manifest placeholders

Every `REPLACE_WITH_*` token in `docker/eks/*.yaml` needs a real value:

| File | Placeholder | Value |
|---|---|---|
| `configmap.yaml` | `DB_CONNECT` | the RDS JDBC URL from Step 2 |
| `configmap.yaml` | `FRONTEND_URL`, `SECMAN_BACKEND_URL` | `$PUBLIC_URL` |
| `serviceaccount.yaml` | `role-arn` | the role ARN printed by `secrets-setup.sh` |
| `secretstore.yaml` | `region` | `$AWS_REGION` |
| `deployment-backend.yaml`, `deployment-frontend.yaml` | `image:` | the ECR image URIs printed by `build-push.sh` |
| `ingress.yaml` | `certificate-arn` | your ACM certificate ARN |

`docker/eks/deploy.sh` refuses to run while any `REPLACE_WITH_*` token
remains, so it's safe to just try it and fix whatever it flags.

---

## Step 7 — Deploy

```bash
docker/eks/deploy.sh
```

Applies `docker/eks/` via `kubectl apply -k`, waits for both Deployments to
roll out, and polls for the ALB hostname (provisioning the ALB itself can
take a few minutes on first apply). Point your DNS record (`secman.example.com`)
at the printed hostname.

---

## Step 8 — Verify

```bash
kubectl -n secman get pods                 # both Deployments Running
kubectl -n secman get externalsecret        # secman-secrets: SecretSynced
kubectl -n secman describe ingress secman   # ALB provisioned, target groups healthy
curl -s "$PUBLIC_URL/health"                # => {"status":"UP"}
```

Then open `$PUBLIC_URL/` in a browser and log in.

---

## Updating to a new version

```bash
export IMAGE_TAG=$(git rev-parse --short HEAD)   # or any tag scheme you prefer
docker/eks/build-push.sh
# update image: in deployment-backend.yaml / deployment-frontend.yaml to $IMAGE_TAG
docker/eks/deploy.sh
```

Kubernetes does a rolling replacement of each Deployment; the ALB only shifts
traffic to a new pod once its readiness probe passes. Pinning an immutable
tag (a git SHA, not `:latest`) is safer for auditable rollbacks — the same
guidance as the ECS runbook.

---

## Fargate constraints

- **No persistent volumes.** Fargate pods get ephemeral storage only — no
  EBS-backed `PersistentVolume`. This is *why* the database is RDS here
  rather than a third in-cluster container; see [Appendix A](#appendix-a-running-the-database-in-cluster-instead-of-rds)
  if you specifically want the database as a real container anyway.
- **No privileged pods, no `hostPort`, no `hostNetwork`.** None of this
  app's manifests use them, so this is a non-issue here — just don't add any.
- **`resources.requests` is mandatory**, not advisory — Fargate uses it to
  pick a supported vCPU/memory combination for the pod. `limits == requests`
  in this repo's manifests keeps `JAVA_OPTS`'s `MaxRAMPercentage` math
  (`configmap.yaml`) predictable.
- **Namespace-scoped scheduling.** A pod only runs on Fargate if its
  namespace matches a Fargate profile selector (Step 3's constraint,
  affects any future add-on you install too).
- **Subnet/ENI sizing.** Each Fargate pod gets its own ENI; make sure the
  subnets in your Fargate profile have enough free IPs for
  `replicas × (frontend + backend)` plus headroom for rolling deploys.

---

## Troubleshooting

| Symptom | Check |
|---|---|
| Add-on pod stuck `Pending` forever | Its namespace isn't matched by the Fargate profile (Step 3) — Fargate has no node to fall back to |
| `kubectl apply -k` fails with a `REPLACE_WITH_*` error from `deploy.sh` | Fill in the placeholder table in Step 6 |
| Backend pod `CrashLoopBackOff` | `kubectl -n secman logs deploy/secman-backend` — almost always DB unreachable (`DB_CONNECT`, RDS security group) or a malformed `JAVA_OPTS` |
| `ExternalSecret` status isn't `SecretSynced` | `kubectl -n secman describe externalsecret secman-secrets` — usually the IRSA role ARN in `serviceaccount.yaml` is wrong, or the trust policy's namespace/name doesn't match `system:serviceaccount:secman:secman-external-secrets` |
| ALB never gets a hostname | `kubectl -n kube-system logs deploy/aws-load-balancer-controller` — usually a missing ACM cert ARN, or the controller's IAM policy is missing a permission |
| `502`/`504` from the ALB | Backend still booting (raise `initialDelaySeconds` on `deployment-backend.yaml`'s probes) or crashed — check backend logs |
| Pages render but API calls return 401 | `SECMAN_AUTH_COOKIE_SECURE=true` while reaching the app over plain HTTP — go through the HTTPS ALB |
| OAuth callback loops / wrong redirect, or emails contain `localhost` links | `FRONTEND_URL`/`SECMAN_BACKEND_URL` in `configmap.yaml` must be `$PUBLIC_URL`, not `localhost` |

---

## EKS vs ECS/Fargate

Both run the exact same images and both are "serverless" (no EC2 instances
to patch). The difference is entirely in operational surface:

| | ECS/Fargate (`docs/DOCKER_AWS.md`) | EKS/Fargate (this doc) |
|---|---|---|
| Control plane cost | None (ECS control plane is free) | ~$0.10/hr per cluster |
| Extra components to run | None — task def + ALB is the whole stack | AWS Load Balancer Controller, External Secrets Operator (both need installing/upgrading) |
| Secrets | Directly in the task definition's `secrets` block | Via `ExternalSecret`/`SecretStore` CRDs (one more layer) |
| Tooling | AWS CLI / Console | kubectl, Helm, eksctl — useful if you already run other K8s workloads |
| Best fit | Secman is your only (or first) container workload on AWS | You're already standardizing infrastructure on Kubernetes |

**Recommendation**: stay on ECS/Fargate unless you have another reason to
run Kubernetes — EKS adds real operational surface (IRSA per add-on, add-on
version upgrades, one more control plane to reason about) for an equivalent
running workload.

---

## Appendix A — Running the database in-cluster instead of RDS

Fargate cannot back a stateful `MariaDB` pod with persistent storage (see
[Fargate constraints](#fargate-constraints)), so a genuine third
"database container" that survives pod restarts needs a small **managed EC2
node group** in the same cluster, running alongside the Fargate profile:

```
Cluster
├── Fargate profile (namespace: secman)
│    ├── Deployment: secman-frontend
│    └── Deployment: secman-backend
└── Managed node group (e.g. 1-2 × t3.small, EBS gp3)
     └── StatefulSet: secman-db (MariaDB 11.4, PVC via the EBS CSI driver)
```

This repo's default manifests don't ship this — RDS is simpler and has
better durability/backup/patching built in — but if you want it:

1. Add a managed node group to `docker/eks/cluster.yaml` (`nodeGroups:`),
   sized for MariaDB's working set.
2. Install the [EBS CSI driver](https://docs.aws.amazon.com/eks/latest/userguide/ebs-csi.html)
   add-on (`eksctl create addon --name aws-ebs-csi-driver ...`) with its own
   IRSA role.
3. Add a `StatefulSet` (reuse `docker/database/Dockerfile`'s image) with a
   `volumeClaimTemplate` requesting a `gp3` `StorageClass`, and a headless
   `Service` for stable DNS (`secman-db-0.secman-db.secman.svc.cluster.local`).
4. Point `configmap.yaml`'s `DB_CONNECT` at that service instead of RDS, and
   drop the RDS provisioning step (Step 2) entirely.
5. Take EBS volume snapshots on a schedule (there is no RDS-style automated
   backup here) — see the backup suggestion in `docker/README.md`'s
   [Suggestions](../docker/README.md#suggestions--other-deployment-targets) section.

---

## Appendix B — Optional feature secrets/config

Add these to `configmap.yaml` (non-secret) or as extra `ExternalSecret` data
entries pulling from a new `secman/*` Secrets Manager entry (secret) — see
`docs/ENVIRONMENT.md` for the full catalog:

| Purpose | Variables |
|---|---|
| E-mail notifications | `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` (secret), `SMTP_FROM_ADDRESS`, `SMTP_ENABLE_TLS` |
| AI-assisted risk assessment | `OPENROUTER_API_KEY` (secret) + `AI_RISK_ASSESSMENT_ENABLED=true` |
| CrowdStrike Falcon | `FALCON_CLIENT_ID`, `FALCON_CLIENT_SECRET` (secret), `FALCON_CLOUD_REGION` |

---

*See also: `docker/README.md` (all Docker/Kubernetes options + non-AWS
suggestions), `docs/DOCKER_AWS.md` + `docs/DOCKER_AWS_DEPLOY_RUNBOOK.md`
(the ECS/Fargate equivalent), `docs/ENVIRONMENT.md` (full variable
catalog), `docs/AWS_SECRETS_SETUP.md`.*
