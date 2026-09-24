# Contributing to Agenvas

Agenvas is under active MVP development. Before changing behavior, read [AGENTS.md](AGENTS.md), the relevant parts of [MVP-SPEC.md](docs/MVP-SPEC.md), and [DEVELOPMENT-CHECKLIST.md](docs/DEVELOPMENT-CHECKLIST.md). They describe the intended design; the checklist distinguishes implemented behavior from release gates that are still open.

## Propose a change

Keep each change to a verifiable vertical slice. Describe the user-visible behavior, state and permission boundaries, failure/recovery cases, and the tests that will prove it. Do not add a second architecture or an empty controller/service/repository layer merely to anticipate future features. Product decisions that differ from the specification require an ADR and a specification update.

For code changes, include tests for new behavior. Use real PostgreSQL for transaction, lease, fencing, idempotency, and event-order claims; H2 does not prove those semantics. Fake HTTP servers can test protocol handling but do not prove a real LLM or ComfyUI model works. Never mark a real-provider gate complete without a recorded real-provider test.

## Build and test

```sh
cd frontend
corepack pnpm install --frozen-lockfile
corepack pnpm api:generate
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
```

```sh
cd backend
./mvnw verify
```

Frontend API types are generated from [contracts/openapi.yaml](contracts/openapi.yaml); do not edit generated files by hand. Add a new Flyway migration instead of changing one already applied. Keep DTOs separate from entities and enforce project authorization at the server boundary. Agent tools must call application services, never SQL, shell, arbitrary HTTP, or DOM/React Flow APIs.

## Before submitting

- State which behaviors, contracts, migrations, and docs changed.
- Report the exact checks you ran and their results; write “not run” for any required check you did not run.
- Note unverified limits, especially real-provider compatibility and failure recovery.
- Ensure no API keys, user media, model-private reasoning, local `.env`, or generated credentials are committed.
- Preserve existing user work and keep the Mock path operable without an external account or model.

Security issues should not be posted with working exploits or secrets in public discussions. Follow [SECURITY.md](SECURITY.md): use GitHub's private vulnerability reporting only if the repository owner has enabled it. A verified private reporting channel remains a release blocker.
