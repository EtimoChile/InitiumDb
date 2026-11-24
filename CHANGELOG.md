# Changelog

## Unreleased

- Project bootstrap (Java 25, Maven).
- Add `idb-init` and `idb-crypt` commands.
- Add `idb-capture` and `idb-prepare` commands (DBUnit Flat XML, `REFRESH`, declared table order) with an environment-variable and encrypted-secrets credential provider.
- Use object names (schema, tables) exactly as written, quote the session schema, and let the engine interpret SQL; generate quoted SQL for dataset operations.
- Make `idb-capture` reject table or column names that are not valid XML names before writing the dataset.
- Add per-engine profiles for Oracle and PostgreSQL.
- Run `idb-prepare` SQL hooks in autocommit and apply the dataset in a single transaction.
- Keep semicolons inside double-quoted identifiers when splitting SQL hooks.
- Package the four commands as a folder distribution (zip, tar.gz) with launcher scripts.
- JDBC drivers are not bundled; users place them in `drivers/`, and a missing driver gives a clear error.
- `idb-init` takes one or more `--database [ENGINE:]NAME` (upper-cased automatically, optional `--engine` default) instead of generating a fixed example; it is cumulative: new databases are added to existing case, environment and secrets files, with their queries template and empty pre/post SQL hook files, existing entries are never modified, and `--overwrite` is removed.
- Aliases and credential names must be upper case (letters, digits, `_`), validated by every command that reads files; `credential` defaults to the alias; `IDB_<ALIAS>_ENGINE`, `_URL` and `_USER` environment variables can define or override a database, so CI needs no environment file.
