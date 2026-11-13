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
corepack pnpm format:check
corepack pnpm typecheck
corepack pnpm lint
corepack pnpm test
corepack pnpm build
```

```sh
cd backend
./mvnw spotless:check
./mvnw verify
```

## Code formatting

Use the pinned project tools rather than an IDE's built-in defaults:

| Scope | Write formatting | Check without writing |
| --- | --- | --- |
| Frontend (from `frontend/`) | `corepack pnpm format` | `corepack pnpm format:check` |
| Backend (from `backend/`) | `./mvnw spotless:apply` | `./mvnw spotless:check` |

Prettier handles frontend source, tests/browser fixtures, scripts and top-level JS/TS, CSS, HTML, JSON and YAML configuration. It follows the existing two-space, double-quote, semicolon style with a 120-column target. Embedded language formatting is disabled to preserve template content. ESLint continues to check code quality and project rules; `eslint-config-prettier` disables conflicting formatting rules (including `no-unexpected-multiline` for wrapped property access). ESLint does not run Prettier or perform formatting fixes.

Spotless uses the checked-in Eclipse JDT profile for hand-written `src/main/java` and `src/test/java` only: four spaces, a 120-column target and preserved text-block contents and comments. It does not sort imports or members, remove unused code, or rewrite strings. The profile can also be imported into Eclipse or IntelliJ's Eclipse formatter integration.

Generated OpenAPI types (`frontend/src/shared/api/schema.ts`), jOOQ sources (`backend/src/jooq`), the pnpm lockfile, dependencies, caches, build output, database migrations, backend resources/prompts and non-code assets are outside formatting scope. Regenerate generated code with its owning tool; do not format it separately. The frontend globs in `package.json` define the format/check scope, with `.prettierignore` providing exclusions.

CI runs both read-only checks in the existing frontend and backend-unit jobs. Formatting is an explicit Maven goal, independent of compilation, database access and production packaging. To verify idempotence, run the write command twice and confirm the second run leaves the same diff, then run the check command. Keep formatting-only changes separate from business changes when practical.

Frontend API types are generated from [contracts/openapi.yaml](contracts/openapi.yaml); do not edit generated files by hand. Add a new Flyway migration instead of changing one already applied. Keep DTOs separate from entities and enforce project authorization at the server boundary. Agent tools must call application services, never SQL, shell, arbitrary HTTP, or DOM/React Flow APIs.

## Before submitting

- State which behaviors, contracts, migrations, and docs changed.
- Report the exact checks you ran and their results; write “not run” for any required check you did not run.
- Note unverified limits, especially real-provider compatibility and failure recovery.
- Ensure no API keys, user media, model-private reasoning, local `.env`, or generated credentials are committed.
- Preserve existing user work and keep the Mock path operable without an external account or model.

Security issues should not be posted with working exploits or secrets in public discussions. Follow [SECURITY.md](SECURITY.md): use GitHub's private vulnerability reporting only if the repository owner has enabled it. A verified private reporting channel remains a release blocker.
