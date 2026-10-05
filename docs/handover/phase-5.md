# Phase 5 Handover: closing the gaps to the target stack

Phase 5 brings the project in line with a typical early-stage product stack: Nuxt, Spring Boot + GraphQL, PostgreSQL + Flyway, Kotlin Multiplatform Mobile, Linode/Akamai, Cloudflare, Auth0, Kubernetes on containerd, OpenTofu, GitHub, CI/CD across development, staging and production, hardened infrastructure and secrets management, with work tracked in ClickUp.

It was delivered as four pull requests. **Merge them in this order**; each one builds on the previous:

| # | Branch | What it closes |
|---|---|---|
| W | `feature/clickup-workflow-onboarding` | ClickUp ↔ GitHub workflow: task IDs in branches, commits and PRs; PR template; definition of done |
| E | `feature/environments-and-deploy` | dev / staging / production environments, deploy pipeline (staging automatic, production on a tag after approval, images by digest) |
| S | `feature/secrets-and-hardening` | SOPS-encrypted secrets per environment, Pod Security `restricted`, NetworkPolicies, node firewall, containerd docs |
| M | `feature/kmm-mobile` | Kotlin Multiplatform shared module, Android app, iOS framework, money-rule contract shared by all clients |

---

## 1. Before vs after

| Requirement | Before | After |
|---|---|---|
| Mobile: KMM | none | `mobile/`: shared Kotlin module (pricing, fees, Apollo GraphQL client), Compose Android app, `Shared.xcframework`; tested on JVM, Android and the iOS simulator |
| Environments | `k8s/base` + CI overlay, one tfvars file | Overlays and tfvars for dev, staging, prod; one OpenTofu workspace and state per environment |
| CI/CD: deployment | images pushed, no deploy | Deploy workflow: staging on every green `main`, production on `v*` after approval, only commits that ran on staging, pinned by digest |
| Secrets management | example file, created by hand | Encrypted in Git with SOPS + age, one key per environment plus an admin key; CI rejects plain-text secrets |
| Hardened infrastructure | hardened pods | + Pod Security `restricted`, default-deny NetworkPolicies, Linode Cloud Firewall, DB allow-list validation and patch window, encrypted OpenTofu state |
| containerd | not mentioned | Documented runtime with a `crictl` cheat sheet |
| ClickUp | one sentence | Branch/commit/PR conventions that ClickUp links automatically, PR template |

## 2. Big picture

```
                     ClickUp task CU-123 ◄──── linked automatically ────┐
                                                                         │
 feature/CU-123-…  ──► PR (template) ──► CI ─────────────────────────────┤
                                         ├─ web: lint · 176 tests · coverage gate
                                         ├─ backend: 146 unit + 69 integration · SpotBugs · coverage gate
                                         ├─ mobile: shared tests on JVM, Android, iOS · APK · XCFramework
                                         ├─ security: CodeQL · Trivy · gitleaks
                                         └─ kind: deploy + smoke (Pod Security + NetworkPolicy proven)
                           merge to main
                                ▼
            images sha-<commit> ──► staging (auto) ──► tag v1.2.0 ──► approval ──► production
                                     same digest ────────────────────────────────► same digest
            secrets: sops -d (environment's own key) ──► kubectl apply, never written to disk
```

The money-rule contract ties the three clients together:

```
contracts/money-rules.json  (13 fee cases, 54 rental quotes)
      ├─► backend   MoneyRulesContractTest (JUnit, parameterized)
      ├─► web       moneyRulesContract.test.ts (Vitest, it.each)
      └─► mobile    generated MoneyRuleCases.kt → MoneyRulesContractTest on JVM · Android · iOS
```

## 3. What was verified, and how

| Claim | Evidence |
|---|---|
| Overlays are valid Kubernetes | `kubectl kustomize` + `kubeconform -strict` for dev, staging, prod, ci (locally and in CI) |
| Wrong workspace cannot touch another environment | Local OpenTofu run: `environment=prod` in workspace `staging` fails with *Resource precondition failed* |
| OpenTofu state is encrypted | Local run: the state file does not contain the planted password; the wrong passphrase gives *decryption failed* |
| Staging key cannot read production secrets | `SOPS_AGE_KEY_FILE=staging.txt sops -d prod/...` fails; the admin key succeeds |
| CI rejects leaked secrets | Planted an unencrypted value and a plain `kind: Secret`: both fail the guard step |
| Encrypted files do not trip scanners | gitleaks: no leaks found |
| Pod Security and NetworkPolicy are enforced, not just declared | Smoke test: a privileged pod is rejected (`violates PodSecurity "restricted"`), and an unlisted pod gets `BLOCKED` while DNS works |
| One price everywhere | 67 contract cases pass in Java, TypeScript and Kotlin (JVM locally; Android and iOS in CI) |
| The contract test catches real bugs | Changed the Kotlin rounding constant 5,000 → 4,999: `fee(10) expected 1 but was 0` |
| Mobile build uses the backend schema | Apollo generated `RentalEquipmentQuery` from `schema.graphqls` |

**Not verifiable in this sandbox:** Android and iOS compilation (Android SDK and Kotlin/Native downloads are blocked here). Both run in the Mobile workflow on GitHub-hosted runners. A real deployment needs a Linode account (section 5).

## 4. Interview story (STAR)

> **Situation:** The project matched the posting's backend and frontend, but had no mobile app, one environment, no deploy step and hand-made secrets.
> **Task:** Close those gaps using only the stack in the posting.
> **Action:** Added dev/staging/prod overlays and per-environment OpenTofu state, with a guard that refuses to apply one environment's settings to another. Built a deploy pipeline that promotes the same image digest from staging to production behind an approval. Committed secrets encrypted with SOPS, one key per environment, so a staging job can never read production secrets. Made Pod Security and NetworkPolicies part of the smoke test, so CI proves they're enforced. For KMM, I shared the money rules and a GraphQL client generated from the backend's own schema, and made all three clients pass the same 67-case contract file.
> **Result:** A schema change or a rounding change now fails in the pull request that causes it, on every platform, and the pipeline gives you one tested artifact that moves through all three environments.

Numbers to remember: **3 environments · 1 image digest · 4 age keys · 67 contract cases × 3 languages · 391 tests**.

## 5. Your next steps (owner actions)

1. Merge W → E → S → M. After E, the Deploy workflow runs on every merge to `main` (render-and-validate only until a cluster exists).
2. Save the age private keys from the session's scratchpad (`age-keys/`) into your password manager, add each as `SOPS_AGE_KEY` to its GitHub Environment, then delete the folder. They protect placeholder values only; rotate to self-generated keys before using real credentials (`docs/DEPLOYMENT.md` §8).
3. Settings → Environments → `production`: add yourself as a required reviewer and restrict deployments to tags `v*` (§4 of DEPLOYMENT.md).
4. Optional, costs money: create a Linode account and follow DEPLOYMENT.md §5 for one environment, then set `KUBECONFIG_B64`.
5. Try the Android app: download `quipmarket-android-debug-apk` from a Mobile workflow run, or run it from Android Studio against `docker compose --profile app up`.

## 6. Self-test (retrieval practice)

Answer without looking, then check the docs:

1. Why does production deploy by digest instead of the tag `latest`? What can go wrong with rebuilding for production?
2. The staging deploy job is compromised. Which secrets can the attacker read, and why not the others?
3. What exactly does the smoke test do to prove a NetworkPolicy is enforced, and why does it check DNS first?
4. If someone renames `rentalRates` in the GraphQL schema, which CI job fails first, and at which step?
5. Why is the Kotlin contract test generated into source code instead of reading the JSON file at test time?
6. What stops `tofu apply -var-file=environments/prod.tfvars` from running against the staging state?
