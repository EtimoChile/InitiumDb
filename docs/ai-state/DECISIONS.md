# Decisions

Format: `DEC-NNN | ACTIVE/REPLACED/DISCARDED | title` — decision, why,
artifacts, optional `changelog:`.

DEC-001 | REPLACED by DEC-002 | Python/poetry stack, engine-independent core
Bootstrapped as Python by mistake; `README.md` specifies Java.

DEC-002 | ACTIVE | Java 25 / Maven stack, engine-independent core
`core/` free of engine-specific SQL/driver types (standard JDBC allowed),
`db/<engine>/` minimal adapters (same layout idea as LimitsDb).
Maven, Java 25 (LTS) baseline. Base package `cl.etimo.initiumdb`,
Maven `groupId` `cl.etimo` (set by the owner). Engine names follow the industry
name: `oracle`, `postgresql` (tags `<engine>_integration`).
Exit codes: `0` success, `1` failure. The build tool is provisional until confirmed.
Artifacts: `pom.xml`, `AGENTS.md`, `README.md`, `src/main/java/cl/etimo/initiumdb/`.

DEC-003 | ACTIVE | `idb-init` and `idb-crypt`, mirroring LimitsDb
Replaces the README's "two executables only" with four. Same usage as
`ldb-init`/`ldb-crypt`, with `--case`/`--environment` in place of
`--schema`/`--profile`. Local secrets: `<config-dir>/<env>.secrets.json`
(default `~/.initiumdb`), AES-256-GCM tokens, key from `IDB_MASTER_KEY` or
`~/.initiumdb/idb.key`. Divergences from LimitsDb: a missing secrets file
fails; the secrets file is never overwritten by `idb-init`; no `--log-level`.
Local encryption is one provider, not the only one (see PEND-005).
Artifacts: `README.md`, `cl.etimo.initiumdb.cli.IdbInit`, `IdbCrypt`,
`cl.etimo.initiumdb.core.SecretCipher`, `MasterKey`, `SecretsFile`.
changelog: Add `idb-init` and `idb-crypt` commands.
