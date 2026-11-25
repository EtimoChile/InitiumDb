# InitiumDb constitution

InitiumDb captures selected records from development/QA databases and
prepares them again before automated test suites (`idb-capture`,
`idb-prepare`; see `README.md`). It touches real data; rules below are
mandatory.

## Sources of truth (highest first)

1. `AGENTS.md`
2. `LICENSE`, `NOTICE`, `LICENSE-DUAL.md`, `COMMERCIAL-EDITION-NOTICE.md`,
   `THIRD_PARTY_LICENSES.md`, `CONTRIBUTING.md`, `.github/CODEOWNERS`:
   authoritative for licensing, ownership and collaboration. Do not
   reinterpret or modify those terms as part of an ordinary technical task.
3. `README.md`: public behavior, configuration, CLI.
4. `docs/prompts/test-authoring.md`, `docs/prompts/test-organization.md`:
   normative for tests. `ACTIVE` decisions in `docs/ai-state/DECISIONS.md`.
5. Engine-independent contract in `cl.etimo.initiumdb.db`.
6. Code, config, tests.
7. `CHANGELOG.md`: history only.

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

## Governance and ownership

InitiumDb is maintained by Inversiones Etimo SpA, which owns the project and
brand; the code is Apache 2.0 (`LICENSE`). Do not add files, headers, or text
that change license, ownership, or commercial-edition statements, and do not
add dependencies whose license conflicts with Apache 2.0 distribution without
recording it in `THIRD_PARTY_LICENSES.md` and `docs/ai-state/PENDING.md`.
Changes to `.github/` and the files listed in `.github/CODEOWNERS` need the
maintainer's review.

## Language

All project content (code, docs, comments, commits, PRs, logs) is English.

## Architecture

- Maven project; sources in `src/main/java/cl/etimo/initiumdb/`.
- `cl.etimo.initiumdb.core`: engine-independent; standard JDBC (`java.sql`)
  is allowed, engine-specific SQL or driver types are not.
- `cl.etimo.initiumdb.db` / `cl.etimo.initiumdb.db.<engine>`: engine contract and
  engine specifics, kept minimal: DBUnit covers most engine-specific behavior.
  New capability: expose in the engine contract first, then implement and
  test per adapter.
- `cl.etimo.initiumdb.cli`: thin entry points; delegate to core; exit code
  `0` on success, `1` on any failure.
- Java 25 (LTS), JDBC, DBUnit, YAML; conventions in `pom.xml` (compiler runs
  `-Xlint:all -Werror`; formatting by Spotless/google-java-format).
- Do not change behavior, config format, and docs together without tests.

## Security and data

- Never version credentials, DSNs, keys, data dumps, or real personal data.
- Never print decrypted secrets.
- InitiumDb does not mask or anonymize; never present it as doing so. Captured
  datasets must be reviewed before versioning.
- Never log credentials or JDBC secrets.
- Treat dataset application, SQL hooks, deletes, and data copies as destructive.
  Never run against a real database without explicit authorization and an
  identified environment; never target production; tests use doubles or
  user-declared disposable DBs.
- Do not weaken validation or error handling to make a test pass.

## Tests

Before creating or changing tests, read `docs/prompts/test-authoring.md` and
`docs/prompts/test-organization.md`. Verification, proportional to change:

```bash
mvn test
mvn spotless:check
mvn verify
```

Integration tests are opt-in (`-Poracle-integration`, `-Ppostgresql-integration`)
and require a user-declared disposable DB.

Never claim a check passed unless it ran. Behavior changes need tests and docs.
No releases, tags, or publishing without explicit request. Before tagging, the
`Unreleased` section of `CHANGELOG.md` becomes the dated version entry, derived
from the `changelog:` lines of `DECISIONS.md`.
