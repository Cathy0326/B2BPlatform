# Contributing

Work is planned and tracked in **ClickUp**; code lives in **GitHub**. ClickUp's GitHub integration links the two automatically when a task ID appears in a branch name, commit message or pull request, so the task shows its branches, commits and PR status without anyone updating it by hand.

## From task to merge

```
ClickUp task (CU-86abc123)            GitHub
  To do        ──► branch  feature/CU-86abc123-rental-quote-banner
  In progress  ──► commits "CU-86abc123 Show the cheapest rental mix on the quote"
  In review    ──► pull request (template asks for the task ID) ──► CI green ──► review
  Done         ◄── merged to main ──► deployed to staging automatically (docs/DEPLOYMENT.md)
```

| Step | Rule |
|---|---|
| Branch | `<type>/CU-<task id>-<short-name>`; type is `feature`, `fix`, `docs` or `chore`. ClickUp's *Create branch* button in the task's GitHub section produces a matching name |
| Commits | Start the subject with the task ID, then the change in the imperative: `CU-86abc123 Validate rental dates on the server` |
| Pull request | One task per PR; fill in the template. Keep PRs small enough to review in 15 minutes |
| Merge | Only with CI green (tests, coverage gates, linters, security scans, Kubernetes smoke test) and one approval |
| Chores without a task | Use `none` in the template and skip the ID |

## Definition of done

- Tests for the new behaviour, and the existing suites still pass (`npm run lint && npm test && npm run typecheck`, `./mvnw verify`)
- Database changes are a **new** Flyway migration; existing migrations are never edited
- GraphQL schema changes are backwards compatible, or the frontend changes in the same PR
- No secrets in the code or in Git; new configuration is documented in `.env.example` or `k8s/base`
- UI changes include a screenshot in the PR

## Running the project

See [README → Run it](README.md#run-it).
