# Changelog

## 0.5.1 - 2026-11-25

- Update dependencies with published security fixes: jackson-databind and jackson-dataformat-yaml 2.18.12, PostgreSQL JDBC driver used for builds and tests 42.7.12 (drivers are not distributed, so users should update theirs too), AssertJ 3.27.7 (tests only).
- Fix: `idb-prepare` no longer buffers whole tables in memory while reading the dataset; it reads the file in two streaming passes, so memory does not grow with the dataset. A malformed dataset is now rejected before the pre hook runs.
- Fix: a column that is `NULL` in every captured row is no longer silently left unchanged; `idb-capture` declares the columns of each table in an internal DTD of the Flat XML file and `idb-prepare` applies them. Datasets written by earlier versions still load.
- Fix: the distribution archives now include `LICENSE`, `NOTICE`, `THIRD_PARTY_LICENSES.md`, `LICENSE-DUAL.md` and `COMMERCIAL-EDITION-NOTICE.md`.
- Fix: on PostgreSQL, a dataset that omits a NOT NULL column without a default (for example captured with `SELECT col1, col2`) no longer fails on rows that already exist; such tables are applied with DBUnit `REFRESH`.
- Fix: `.github/CODEOWNERS` names the `EtimoChile/maintainers` team.

## 0.5.0 - 2026-11-25

- Adopt the Apache License 2.0 (`LICENSE`, `NOTICE`, `LICENSE-DUAL.md`, `COMMERCIAL-EDITION-NOTICE.md`, `THIRD_PARTY_LICENSES.md`) and the collaboration model of the Etimo projects (`CONTRIBUTING.md`, `DEVELOPMENT.md`, `.github/CODEOWNERS`).
- Add a CI workflow (`spotless:check`, `mvn verify`, PostgreSQL and Oracle container jobs on pull requests) with JaCoCo coverage: unit coverage is combined with each integration job and uploaded as an artifact.
- Add `SECURITY.md`, `CODE_OF_CONDUCT.md`, issue and pull request templates.

## 0.4.0

- A `jdbcUrl` that does not start with `jdbc:` now fails with a clear message instead of a misleading "No JDBC driver found".
- On PostgreSQL, a `schema` that does not exist fails with "Schema ... does not exist" instead of a later "relation does not exist".
- README states that interrupting `idb-prepare` after the pre hook leaves the data half-prepared and that re-running it is the recovery.
- `idb-capture` reports elapsed time per table and per alias.
- **Breaking:** `idb-init` option `--database` is now `--alias`, matching the `alias` of `case.yml`.

## 0.3.0

- Distribution folder is `initiumdb` (no version in its name) inside `initiumdb-<version>-dist.zip`/`.tar.gz`; every command accepts `--version`; the project version is `0.3.0` and a `Release` GitHub workflow publishes the archives and `SHA256SUMS` when a `vX.Y.Z` tag is pushed from `main`.
- Project bootstrap (Java 25, Maven).
- Add `idb-init` and `idb-crypt` commands.
- `idb-capture` and `idb-prepare` encrypt cleartext secrets in place when they find them (previously they failed asking for `idb-crypt`).
- Logs and errors say `Alias GL (dev) 1 of 2` instead of `Step 1 (GL)`.
- `idb-prepare` reports elapsed time per hook, table, dataset and alias, and `--refresh dbunit` forces DBUnit's original `REFRESH` (default `--refresh optimized`).
- On Oracle (`MERGE`) and PostgreSQL (`INSERT ... ON CONFLICT`), `idb-prepare` applies the dataset with batched upserts instead of DBUnit's row-by-row `REFRESH` (a missing attribute is stored as `NULL`).
- Add `idb-capture` and `idb-prepare` commands (DBUnit Flat XML, `REFRESH`, declared table order) with an environment-variable and encrypted-secrets credential provider.
- Use object names (schema, tables) exactly as written, quote the session schema, and let the engine interpret SQL; generate quoted SQL for dataset operations.
- SQL failures now name the table query, hook statement or guard that failed and show its SQL (queries and hooks are trusted project files).
- `idb-prepare` reads the dataset as a stream and reports each hook and each table (with row count) as it goes.
- `idb-capture` streams rows into the dataset instead of loading every result in memory, and prints a row count per table as it goes.
- Make `idb-capture` reject table or column names that are not valid XML names before writing the dataset.
- Add per-engine profiles for Oracle and PostgreSQL.
- Run `idb-prepare` SQL hooks in autocommit and apply the dataset in a single transaction.
- Keep semicolons inside double-quoted identifiers when splitting SQL hooks.
- Package the four commands as a folder distribution (zip, tar.gz) with launcher scripts.
- JDBC drivers are not bundled; users place them in `drivers/`, and a missing driver gives a clear error.
- `idb-init` takes one or more `--database [ENGINE:]NAME` (upper-cased automatically, optional `--engine` default) instead of generating a fixed example; it is cumulative: new databases are added to existing case, environment and secrets files, with their queries template and empty pre/post SQL hook files, existing entries are never modified, and `--overwrite` is removed.
- SQL hooks keep their comments when executed (optimizer hints are no longer dropped); comment-only hooks run nothing.
- Every command accepts `--help`/`-h`; the `idb-init` usage now documents `[ENGINE:]NAME`.
- Aliases and credential names must be upper case (letters, digits, `_`), validated by every command that reads files; `credential` defaults to the alias; `IDB_<ALIAS>_ENGINE`, `_URL` and `_USER` environment variables can define or override a database, so CI needs no environment file.
- `sql` is optional in `<ALIAS>.queries.yml` tables: it defaults to `SELECT * FROM "<name>"`.
- **Breaking:** a case is a directory: `--case` names it and `case.yml` (now only `version` and `databases: [{alias}]`) is looked up inside; `name`, `queries`, `dataset`, `preSql` and `postSql` are gone and the files are found by convention as `<ALIAS>.queries.yml`, `<ALIAS>.dataset.xml`, `<ALIAS>.pre.sql` and `<ALIAS>.post.sql`; absent hooks run nothing.
- Add conditional SQL statements: a `-- idb:run-if <query>` or `-- idb:skip-if <query>` line comment before a statement runs it only when the guard query returns (or does not return) a row; malformed directives fail the run.
- `idb-init` creates the missing support files of databases that are already in the case.
