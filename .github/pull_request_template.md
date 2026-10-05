## ClickUp task

<!-- Paste the task ID, e.g. CU-86abc123. ClickUp's GitHub integration links this PR to the task automatically
     when the ID appears in the branch name, a commit message or this description. Write "none" for chores. -->
CU-

## What changed

<!-- One or two sentences for the reviewer: the user-visible result, not the list of files. -->

## How it was tested

- [ ] `npm run lint && npm test && npm run typecheck` (frontend)
- [ ] `./mvnw verify` (backend, needs Docker)
- [ ] Checked in the browser (screenshot or short clip below for UI changes)

## Checklist

- [ ] Database change? A new Flyway migration (`V<n>__*.sql`); existing migrations are never edited
- [ ] GraphQL schema change? Backwards compatible, or the frontend is updated in this PR
- [ ] New configuration or secret? Added to `.env.example` / `k8s/base` and described, with no real values committed
- [ ] Docs updated if behaviour or setup changed
