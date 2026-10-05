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
        ─► if the environment has KUBECONFIG_B64:
             namespace (Pod Security labels) → sops -d backend-secrets.enc.yaml | kubectl apply (in memory only)
             → kubectl apply → rollout status
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
   - **Secret** `SOPS_AGE_KEY`: that environment's private age key (`AGE-SECRET-KEY-1…`), which decrypts only its own Secret file (section 8).
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
- Put the real values into the environment's encrypted Secret: `sops k8s/overlays/<env>/backend-secrets.enc.yaml` (section 8), using the database outputs (`tofu output -raw db_password` and so on).
- Add the cluster's node IPs to `db_allow_list` and apply again.
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

## 8. Secrets: SOPS + age

Secrets are committed to Git, **encrypted**. [SOPS](https://github.com/getsops/sops) encrypts only the values, so the file still shows which keys exist and a pull request diff shows which value changed, but not the value itself.

```
k8s/overlays/staging/backend-secrets.enc.yaml
  stringData:
    DATABASE_PASSWORD: ENC[AES256_GCM,data:pY35cUiW…]   ← readable only with the staging or the admin key
```

| Key | Who holds the private half | Can decrypt |
|---|---|---|
| dev | GitHub Environment `dev` (`SOPS_AGE_KEY`) | dev only |
| staging | GitHub Environment `staging` | staging only |
| prod | GitHub Environment `production`, released only after the approval | production only |
| admin | The owner's password manager, offline | everything, to edit and for break-glass access |

Why this design:
- **No extra server:** SOPS needs no component in the cluster (unlike Sealed Secrets) and no paid secret manager (Linode has none).
- **Blast radius per environment:** a leaked staging key exposes staging only, and the staging deploy job never receives the production key.
- **Never on disk:** the deploy job pipes `sops -d` straight into `kubectl apply`. The rendered manifests attached to each run contain no Secret.
- **Enforced by CI:** the infrastructure job fails if a `kind: Secret` manifest is committed outside a `*.enc.yaml` file, or if any value in an encrypted file is in plain text. gitleaks and Trivy still scan for keys anywhere else.

Everyday commands (with `SOPS_AGE_KEY_FILE` pointing to your key file):

```bash
sops k8s/overlays/staging/backend-secrets.enc.yaml          # opens decrypted in your editor; re-encrypts on save
sops -d k8s/overlays/staging/backend-secrets.enc.yaml       # print decrypted (never redirect into the repository)
sops updatekeys k8s/overlays/staging/backend-secrets.enc.yaml   # after changing .sops.yaml (rotation, new admin)
```

OpenTofu has its own secrets (API tokens, the state passphrase). They are passed as `TF_VAR_*` environment variables and never written to a file in the repository. The state file is encrypted (section 2).

## 9. Hardening

| Layer | Control | Where |
|---|---|---|
| Pods | Non-root user, read-only root filesystem, no privilege escalation, all Linux capabilities dropped, `RuntimeDefault` seccomp profile, resource requests and limits | `k8s/base/backend.yaml`, `frontend.yaml` |
| Namespace | Pod Security Admission `restricted` (`enforce`, `warn`, `audit`): the API server rejects any pod that breaks the rules above | `k8s/base/namespace.yaml` |
| Pod network | Default-deny ingress. Only ingress-nginx may reach the frontend; only ingress-nginx and the frontend may reach the backend | `k8s/base/network-policy.yaml` |
| Nodes | Linode Cloud Firewall: inbound `DROP` except the control-plane, Calico and NodeBalancer ranges LKE needs | `infra/main.tf` (`linode_firewall.nodes`) |
| Database | Managed PostgreSQL reachable only from listed node IPs (`0.0.0.0/0` is rejected by validation), TLS required in the JDBC URL, weekly patch window | `infra/main.tf`, `variables.tf` |
| Secrets | Encrypted in Git per environment, decrypted only in the deploy job (section 8) | `.sops.yaml` |
| Infrastructure state | Encrypted, remote, one file per environment | `infra/versions.tf` |
| Supply chain | Images deployed by digest; scanners and SOPS installed as checksum-verified release binaries | `.github/workflows/` |
| Continuous checks | CodeQL, Trivy (dependencies, secrets, Kubernetes/OpenTofu misconfiguration, images), gitleaks, SpotBugs | [QUALITY.md §7](QUALITY.md#7-security-scanning-and-static-analysis) |

The CI kind cluster enforces the same Pod Security labels and NetworkPolicies, so the smoke test also proves that the hardened manifests work: the backend still reaches PostgreSQL and the frontend still renders data from the backend.

## 10. Container runtime: containerd

Kubernetes runs containers through containerd on Linode Kubernetes Engine nodes and in the CI kind cluster. Docker is only used to *build* images; Kubernetes never talks to Docker. Images are standard OCI images, so the same image runs under both.

| Task | Command |
|---|---|
| Load a locally built image into kind (it goes into the node's containerd image store) | `kind load docker-image quipmarket-backend:ci --name quipmarket-ci` |
| List images in containerd on a node | `crictl images` (on the node: `docker exec -it quipmarket-ci-control-plane crictl images` in kind) |
| List running containers / read logs below Kubernetes | `crictl ps`, `crictl logs <container-id>` |
| Debug a pod without SSH to the node | `kubectl debug node/<node> -it --image=busybox` then `chroot /host crictl ps` |
| Check the runtime of every node | `kubectl get nodes -o wide` (column CONTAINER-RUNTIME, e.g. `containerd://1.7.x`) |
