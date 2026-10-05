# Environments & Deployment

QuipMarket runs in three environments, **dev**, **staging** and **production**. All three are built from the same Kubernetes manifests and the same OpenTofu code; only the settings at the edges differ. Every image is built once and then moved through the environments by its digest, so production runs the exact bytes that were tested on staging.

---

## 1. The pipeline

```
 pull request ──► CI: lint · unit + integration tests · coverage gates · security scans · kind deploy + smoke test
       │
   merge to main
       ▼
 CI on main ──► images pushed to GHCR, tagged sha-<commit> ──► Deploy workflow ──► STAGING (automatic)
                                                                                     │
 git tag v1.4.0 on that commit ──► checks: on main? live on staging? ──► approval ──► PRODUCTION
                                                                                     (same digests)
 Actions → Deploy → Run workflow ──► DEV (any commit CI built on main)
```

| | dev | staging | production |
|---|---|---|---|
| Purpose | Try a feature end to end in the cloud | Final check, identical setup to production | Customers |
| Deployed by | Manual (*Run workflow*) | Every merge to `main`, automatically | A `v*` tag, after a reviewer approves |
| Kubernetes overlay | `k8s/overlays/dev` | `k8s/overlays/staging` | `k8s/overlays/prod` |
| Namespace | `quipmarket-dev` | `quipmarket-staging` | `quipmarket` |
| Replicas | 1 of each, no disruption budget | 2 of each, as production | 2 of each |
| Data | Demo data and bots (`demo` profile) | Empty, as production | Real |
| Auth0 | Dev tenant | Staging tenant | Production tenant |
| Stripe | Test mode | Test mode | Live mode |
| OpenTofu | `environments/dev.tfvars`, workspace `dev` | `staging.tfvars`, workspace `staging` | `prod.tfvars`, workspace `prod` |
| Hosts | `dev.` / `api.dev.` | `staging.` / `api.staging.` | apex / `api.` |

## 2. Why each rule exists

| Rule | What goes wrong without it |
|---|---|
| **Build once, deploy by digest** (`image@sha256:…`, never `:latest`) | A tag can be moved; rebuilding for production can pull a newer base image or dependency than the one staging tested |
| **Production only accepts a commit with a successful staging deployment** | A hotfix tag on an untested commit goes straight to customers |
| **Only commits on `main` are deployed** | A tag pushed on an unreviewed branch would ship code nobody reviewed |
| **Staging skips a run if `main` has already moved on** | Two quick merges finish CI out of order and staging goes *backwards* |
| **One rollout per environment at a time, never cancelled halfway** (`concurrency`, `cancel-in-progress: false`) | Two applies interleave, or a cancelled job leaves half the pods on the old version |
| **Wait for `rollout status`** | The job is green while the new pods crash-loop |
| **One OpenTofu workspace and state file per environment**, and a precondition that the workspace matches `environment` | `tofu apply -var-file=prod.tfvars` in the staging workspace would resize and rename the staging cluster |
| **Encrypted OpenTofu state** | The state file contains the database root password in plain text |
| **Remote state in Linode Object Storage** | State on one laptop is lost with the laptop, and two people cannot work on the infrastructure |

## 3. What the deploy job does

```
resolve ─► environment + commit (must be on main)
        ─► production only: GitHub Deployments API says this commit succeeded on staging
        ─► docker buildx imagetools inspect ghcr.io/…:sha-<commit>  →  sha256 digests
deploy  ─► (GitHub Environment: secrets, required reviewer for production)
        ─► kustomize: overlay + images pinned by digest  →  rendered.yaml
        ─► kubeconform validates it · uploaded as a run artifact
        ─► if the environment has KUBECONFIG_B64: check backend-secrets exists → kubectl apply → rollout status
        ─► summary: commit, both digests, result
```

Without cluster credentials the job stops after validation and says so in its summary ("rendered and validated only"). It never reports a deploy that did not happen. Each run's *Artifacts* contain the exact manifest it applied, or would have applied.

## 4. One-time setup on GitHub (repository owner)

1. **Settings → Environments → New environment**, create `dev`, `staging` and `production`. The Deploy workflow also creates them on first use, but without protection rules.
2. In **`production`**:
   - **Required reviewers**: add yourself. Every production deploy then waits for a click on *Review deployments*.
   - **Deployment branches and tags → Selected branches and tags → Add rule**: tag pattern `v*`.
3. In each environment, once the cluster exists:
   - **Secret** `KUBECONFIG_B64`: the kubeconfig, base64-encoded. OpenTofu already outputs it that way (`tofu output -raw kubeconfig`).
   - **Variable** `APP_URL`, for example `https://staging.quipmarket.example.com`. It appears as a link on the Deployments page.

Until step 3 is done, the pipeline runs fully and validates everything but does not apply.

## 5. Creating an environment's infrastructure

```bash
cd infra
export TF_VAR_linode_token=...            # Linode API token
export TF_VAR_cloudflare_api_token=...    # Cloudflare token with Zone.DNS edit
export TF_VAR_state_passphrase=...        # 16+ characters; keep it in a password manager
export AWS_ACCESS_KEY_ID=... AWS_SECRET_ACCESS_KEY=...   # Linode Object Storage key, for the state bucket

tofu init                                  # once: create the bucket quipmarket-tofu-state first
tofu workspace select -or-create staging
tofu apply -var-file=environments/staging.tfvars
tofu output -raw kubeconfig                # → the KUBECONFIG_B64 secret of the staging environment
```

Windows PowerShell: use `$env:TF_VAR_linode_token = "..."` instead of `export`.

Then, once per cluster:
- Install ingress-nginx and cert-manager (Helm).
- Create the `backend-secrets` Secret in the environment's namespace (see `k8s/base/secret.example.yaml.txt`).
- Set `ingress_ip` in the tfvars file and apply again, so the Cloudflare DNS records are created.

Infrastructure changes are applied by a person, one environment at a time (dev → staging → prod), after reading the plan. Application deploys are fully automated.

## 6. Releasing to production

```bash
git checkout main && git pull
git tag -a v1.4.0 -m "Rental quote banner, deposit fix"
git push origin v1.4.0
```

Then go to **Actions → Deploy**, open the run and click **Review deployments → Approve**. To roll back, tag the previous good commit again under a new version (`v1.4.1`). That commit has already passed staging, so it is promoted the same way.

## 7. How to check it locally

```bash
kubectl kustomize k8s/overlays/staging | kubeconform -strict -summary   # what CI runs for every overlay
kubectl diff -k k8s/overlays/staging                                     # against a real cluster: what would change
```
