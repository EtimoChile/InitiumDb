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
