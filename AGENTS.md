# InitiumDb constitution

InitiumDb is a Test Data Management (TDM) tool: it provisions, masks, subsets,
and refreshes test databases. It touches real data and schemas; rules below
are mandatory.

## Sources of truth (highest first)

1. `AGENTS.md`
2. `README.md`: public behavior, configuration, CLI.
3. Engine-independent contract in `initiumdb/db/` (adapters implement it).
4. Code, config, tests.
5. `CHANGELOG.md`: history only.

Same-rank conflict: do not choose silently; log it in `docs/ai-state/PENDING.md`
and change only what is unambiguous.

## State

Read first: `docs/ai-state/CURRENT.md`, `docs/ai-state/PENDING.md`. Read
`DECISIONS.md` only for related `DEC-NNN` IDs. At task end, edit these files
only if state, pending items, or decisions changed. No diary entries.

- `CURRENT.md`: verifiable current facts.
- `PENDING.md`: open work, each with owner artifact and next action.
- `DECISIONS.md`: durable decisions (`DEC-NNN`; `ACTIVE`/`REPLACED`/`DISCARDED`;
  link implementing artifacts; optional `changelog:` line for user-visible changes).

## Branches

- `development`: default branch; work branches start from it and PRs target it.
- `main`: releases only; changes arrive from `development` on explicit request.

## Language

All project content (code, docs, comments, commits, PRs, logs) is English.

## Architecture

- Maven project; sources in `src/main/java/cl/etimo/initiumdb/`.
- `cl.etimo.initiumdb.core`: engine-independent; no SQL or driver types.
- `cl.etimo.initiumdb.db` / `cl.etimo.initiumdb.db.<engine>`: engine contract and
  engine specifics. New capability: expose in the engine contract first, then
  implement and test per adapter.
- `cl.etimo.initiumdb.cli`: thin entry points; delegate to core; meaningful exit codes.
- Java 21, JDBC, DBUnit, YAML; conventions in `pom.xml` (compiler runs
  `-Xlint:all -Werror`; formatting by Spotless/google-java-format).
- Do not change behavior, config format, and docs together without tests.

## Security and data

- Never version credentials, DSNs, keys, data dumps, or real personal data.
- Never print decrypted secrets.
- Masking/anonymization must be irreversible by default and never log
  unmasked values.
- Treat DDL, grants, truncates, deletes, and data copies as destructive.
  Never run against a real database without explicit authorization and an
  identified environment; tests use doubles or user-declared disposable DBs.
- Do not weaken validation or error handling to make a test pass.

## Tests

Before creating or changing tests, read `docs/prompts/test-authoring.md` and
`docs/prompts/test-organization.md`. Verification, proportional to change:

```bash
mvn test
mvn spotless:check
mvn verify
```

Integration tests are opt-in (`-Poracle-integration`, `-Ppostgres-integration`)
and require a user-declared disposable DB.

Never claim a check passed unless it ran. Behavior changes need tests and docs.
No releases, tags, or publishing without explicit request.
