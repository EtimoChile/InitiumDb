# Decisions

Format: `DEC-NNN | ACTIVE/REPLACED/DISCARDED | title` — decision, why,
artifacts, optional `changelog:`.

DEC-001 | REPLACED by DEC-002 | Python/poetry stack, engine-independent core
Bootstrapped as Python by mistake; `README.md` specifies Java.

DEC-002 | ACTIVE | Java 25 / Maven stack, engine-independent core
`core/` free of engine-specific SQL/driver types (standard JDBC allowed),
`db/<engine>/` minimal adapters.
Maven, Java 25 (LTS) baseline. Base package `cl.etimo.initiumdb`,
Maven `groupId` `cl.etimo` (set by the owner). Engine names follow the industry
name: `oracle`, `postgresql` (tags `<engine>_integration`).
Exit codes: `0` success, `1` failure.
Artifacts: `pom.xml`, `AGENTS.md`, `README.md`, `src/main/java/cl/etimo/initiumdb/`.

DEC-003 | ACTIVE | `idb-init` and `idb-crypt`
Replaces the README's "two executables only" with four. Usage:
`--case`/`--environment` select the case and environment.
Local secrets: `<config-dir>/<env>.secrets.json` (default `~/.initiumdb`),
AES-256-GCM tokens, key from `IDB_MASTER_KEY` or `~/.initiumdb/idb.key`.
A missing secrets file fails; the secrets file is never overwritten by
`idb-init`; no `--log-level`. Local encryption is one provider, not the only
one (see PEND-005).
Artifacts: `README.md`, `cl.etimo.initiumdb.cli.IdbInit`, `IdbCrypt`,
`cl.etimo.initiumdb.core.SecretCipher`, `MasterKey`, `SecretsFile`.
changelog: Add `idb-init` and `idb-crypt` commands.

DEC-004 | ACTIVE | Thin per-engine profile
Each engine is a minimal `DatabaseEngine` (`name`, `configure(DatabaseConfig)`,
`useSchema`): it sets the DBUnit `IDataTypeFactory` and properties and the
session schema; `Engines` resolves it by name. No adapter hierarchy.
Reconsider only for an engine DBUnit cannot cover through `DatabaseConfig`, or
for logic that is not configuration.
Artifacts: `db/DatabaseEngine`, `db/Engines`, `OracleEngine`, `PostgresqlEngine`.
changelog: Add per-engine profiles for Oracle and PostgreSQL.

DEC-005 | ACTIVE | Object names exact, SQL by engine rules
Fields naming objects (`schema`, `tables[].name`) are used exactly as written
(same case as the data dictionary); the session schema is double-quoted.
`sql`/`preSql`/`postSql` go to the engine unchanged. DBUnit runs with
`escapePattern` `"?"` and case-sensitive table names. DBUnit folds the schema
name in `DatabaseConnection` (`SQLHelper.correctCase`), so `DbUnitSession`
returns the exact schema from `getSchema()`. Verified with opt-in container
tests (Docker).
Artifacts: `README.md`, `db/SqlIdentifiers`, `OracleEngine`, `PostgresqlEngine`,
`integration/*RoundTripTest`.
changelog: Use object names exactly as written; quote the session schema.

DEC-006 | ACTIVE | Prepare runs in autocommit; dataset in one transaction
`idb-prepare` is recovered by re-running it from the first step, so manual
commit boundaries add nothing and make hook behavior depend on the engine
(Oracle DDL commits implicitly). `preSql`/`postSql` run in autocommit as written.
DBUnit `REFRESH` runs statement by statement (verified in 3.0.0), so under
autocommit it would commit per row; it is applied in one short transaction
instead (no user SQL inside). Hooks must be idempotent (README).
Artifacts: `README.md`, `core/PrepareService`, `PrepareTest`.
changelog: Run SQL hooks in autocommit and apply the dataset in a single transaction.

DEC-007 | ACTIVE | SQL hooks are plain statements, no procedural blocks
A hook script is a sequence of SQL statements ending in `;`. InitiumDb does
not parse or run procedural blocks (PL/SQL, `DO $$ ... $$`): that would make it
a scripting runtime and tie hooks to each engine's procedural dialect. Hooks
stay declarative; repeatability comes from idempotent statements (DEC-006). Any
conditional execution must be a small notation over plain statements, not
procedural code.
Artifacts: `README.md`, `core/SqlScript`.
