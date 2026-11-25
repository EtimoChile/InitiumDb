# Changelog

## Unreleased

- Project bootstrap (Java 25, Maven).
- Add `idb-init` and `idb-crypt` commands.
- `idb-capture` and `idb-prepare` encrypt cleartext secrets in place when they find them (previously they failed asking for `idb-crypt`).
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
