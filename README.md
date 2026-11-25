# InitiumDb

**Configuration-driven preparation of relational database test data.**

InitiumDb captures selected records from development or QA databases and prepares those records again before automated test suites. The datasets are stored as version-controlled files alongside the tests, making the initial data setup repeatable without requiring a database clone or proprietary test framework.

InitiumDb is intended **only for development and QA databases, never production**. It does not provision databases, create schemas or control objects, manage grants, or execute application tests.

> **Project status:** under development. `idb-init`, `idb-crypt`, `idb-capture` and `idb-prepare` are implemented for Oracle and PostgreSQL, but no release has been published. Items explicitly marked *to be defined* remain open.

## Overview

- **Capture only what matters.** Developers first establish the desired data manually, using application interfaces or SQL. `capture` then executes configured `SELECT` statements and exports the selected rows.
- **Prepare test data on demand.** `prepare` applies saved datasets to a target development or QA environment before tests.
- **Keep test definitions in Git.** Case definitions, queries, SQL hooks and datasets live in the application's test repository.
- **Separate logical and physical configuration.** A case references logical database aliases; environment configuration maps them to JDBC connections.
- **Keep credentials outside test artifacts.** Secrets are resolved at runtime, for local executions and CI/CD alike.
- **Respect declared order.** Database steps and table rows are processed in the order specified; InitiumDb does not discover or reorder foreign-key dependencies.
- **Stay framework-agnostic.** Playwright is a typical E2E framework to pair it with, but InitiumDb does not depend on it.
- **Fail simply.** On error, report the failure and return exit code `1`. There are no checkpoints or partial-run resumption mechanisms; the operator or pipeline reruns the command from the beginning.

## Scope and design principles

1. **Non-production use only.** Intended targets are development and QA databases. Access controls and independently verified connection settings should prevent accidental production access; a YAML environment label alone is not a security boundary.
2. **Four executables only.** `idb-capture` and `idb-prepare` operate on data; `idb-init` and `idb-crypt` set up configuration and local secrets. There is no `validate`, `replay`, or `--mode` interface.
3. **Sequential execution.** No parallelism or dependency graph. The author of the case controls the order of database steps, queries and dataset tables.
4. **No database installation.** No InitiumDb tables, locks, users, roles, grants, sequences or other objects are created.
5. **No automatic recovery.** No persisted execution state or resume-from-checkpoint logic.
6. **Database-enforced integrity.** Existing foreign keys and constraints remain authoritative. If an operation violates a constraint, the database reports an error and the command fails.
7. **Minimal privilege.** `capture` requires read access to selected tables; `prepare` requires only the DML privileges needed for the configured data and optional SQL hooks.
8. **No production-data-management guarantees.** InitiumDb is not an ILM, archiving, backup, masking or full database restoration tool.

## Installation

`mvn package` builds the distribution folder `target/initiumdb-<version>-dist/initiumdb-<version>/` and the archives `initiumdb-<version>-dist.zip` and `.tar.gz`:

```text
initiumdb-<version>/
  bin/   idb-capture, idb-prepare, idb-init, idb-crypt   (shell scripts)
         idb-capture.cmd, idb-prepare.cmd, ...           (Windows)
  lib/   initiumdb jar and its runtime jars (no JDBC drivers)
  drivers/  empty; put your JDBC driver jars here
```

Unpack an archive anywhere and put `bin/` on the `PATH`. The launchers use `JAVA_HOME` when it is set and otherwise `java` from the `PATH`; Java 25 or later is required. Extra JVM options can be passed in `IDB_JAVA_OPTS`. JDBC drivers are not distributed, so their licenses never constrain the Apache 2.0 distribution: download the driver for your engine (PostgreSQL, Oracle, ...) from its vendor and copy the jar into `drivers/`. Without it, the commands fail with a message pointing to that folder.

## Commands

```bash
idb-capture --case path/to/testdata/regression --environment dev [--config-dir dir]
idb-prepare --case path/to/testdata/regression --environment qa [--config-dir dir]
idb-init --case path/to/testdata/regression --environment dev --database CRM [--database postgresql:BILLING ...] [--engine oracle|postgresql] [--config-dir dir]
idb-crypt --environment dev [--case path/to/testdata/regression] [--config-dir dir]
```

`idb-init` and `idb-crypt` are described under [Initialization](#initialization) and [Local secrets](#local-secrets). `idb-capture` and `idb-prepare` may run manually. `idb-capture` is **not** part of CI/CD. `idb-prepare` is normally invoked by CI/CD before tests, but can also be invoked manually.

The `--environment` argument selects `<environment>.yml` in the configuration directory, which is `~/.initiumdb` unless `--config-dir` is given.

Every command accepts `--help` (or `-h`), which prints its usage to standard output and exits with code 0 without touching any file.

### `idb-capture`

1. Read the case definition and the selected environment configuration.
2. Resolve each logical database alias and its credentials.
3. Execute the configured `SELECT` statements in their declared order.
4. Write the selected records to the dataset files, using DBUnit Flat XML format.
5. Exit successfully only if the capture completes.

`idb-capture` does not construct the test scenario: the developer has already prepared the desired data. Captured datasets must be reviewed before committing them to Git; **InitiumDb does not automatically anonymize or mask data**.

Rows are streamed from the database straight into a temporary file (`.idb-capture-*.tmp`, next to the dataset) and never held in memory; no extra query is run to count them. Each table is reported with its row count when it finishes. The temporary file replaces the dataset only when every table succeeded, so a failed or interrupted capture keeps the previous dataset.

### `idb-prepare`

1. Read the case definition and the selected environment configuration.
2. Resolve each logical database alias and its credentials.
3. Process the configured database steps sequentially.
4. For each step, run the optional hook `<ALIAS>.pre.sql`, apply the dataset, then run the optional hook `<ALIAS>.post.sql`.
5. Exit successfully only if every step completes.

The dataset is read as a stream and never held fully in memory, so a malformed XML file is detected when the reader reaches it, after the pre hook has run. Progress is printed as it happens: the statements run by each hook (`pre hook: 3 of 4 statement(s) run`, guards excluded from the count) and each table with its row count (`CUSTOMER: 120 row(s)`), followed by the step line. No query is run to count rows.

The initial dataset operation is DBUnit **`REFRESH`**: update matching rows and insert missing rows according to DBUnit's key matching. `REFRESH` **does not delete unrelated or extra rows**, so `idb-prepare` does not promise to restore an entire database or table to an exact snapshot. Optional SQL hooks can perform case-specific cleanup when needed.

SQL hooks (`<ALIAS>.pre.sql`, `<ALIAS>.post.sql`) run in **autocommit**, exactly as written: each statement is committed as it runs, and a hook that contains its own `COMMIT`, `ROLLBACK` or DDL behaves as the engine defines. The dataset is applied in **one short transaction** (all or nothing), because DBUnit issues its statements row by row and autocommit would otherwise commit once per row. Nothing is rolled back across hooks, the dataset or steps: a failure stops the run, previously committed changes remain, and there is **no distributed transaction**. Re-running the entire `idb-prepare` command is the recovery strategy, so SQL hooks must be **idempotent**, or guarded so that a repeat does nothing (see [Conditional statements](#conditional-statements)): for example `DELETE` then `INSERT`, `MERGE`, or `TRUNCATE`; avoid relative updates such as `balance = balance + 100` and statements that advance sequences.

**SQL hooks are plain SQL, by design.** A hook script is a sequence of SQL statements, each ending in `;`. InitiumDb does not run procedural blocks (PL/SQL, `DO $$ ... $$`): it is not a scripting runtime, and hooks stay declarative and comparable across engines. Repeatability comes from writing idempotent statements, not from procedural logic. Comments (`-- ...`, `/* ... */`, including optimizer hints such as `/*+ ... */`) are sent to the database exactly as written and never split a statement; a script made only of comments is valid and runs nothing. A hook file that is absent or empty runs nothing.

### Conditional statements

A statement that is not idempotent can be made safe to repeat with a guard: a line comment that starts with `idb:`, placed before the statement it controls.

```sql
-- idb:skip-if SELECT 1 FROM app.config WHERE k = 'seeded'
INSERT INTO app.config (k, v) VALUES ('seeded', '1');

-- idb:run-if SELECT 1 FROM app.customer WHERE customer_id = 1001
UPDATE app.customer SET status = 'ACTIVE' WHERE customer_id = 1001;
```

- `-- idb:run-if <query>` runs the next statement only if the query returns at least one row; `-- idb:skip-if <query>` runs it only if the query returns none. The query is the rest of the line (a trailing `;` is optional) and is run in autocommit like any hook statement.
- A directive applies to **one** statement, the next one. There is no nesting, `else` or procedural logic (see above). Statements without a directive always run.
- The directive line is not sent to the database. Every other comment keeps no special meaning, including block comments, and the file remains valid SQL for other tools, which simply ignore the directive.
- To keep a typo from silently running a statement that was meant to be conditional, a `-- idb:` comment must be a valid directive: an unknown name, a missing query, two directives for one statement, a directive after the statement has begun or a directive with no statement after it fail the run before any hook of that step runs.


## Example repository layout

```text
repo-app/
  e2e/
    playwright/
      tests/
        regression/
          invoice.spec.ts
          credit-limit.spec.ts
      testdata/
        regression/            # the directory is the case
          case.yml
          CRM.queries.yml
          CRM.dataset.xml
          CRM.pre.sql
          CRM.post.sql
          BILLING.queries.yml
          BILLING.dataset.xml
```

Ready-to-copy examples for Oracle and PostgreSQL live in [`examples/`](examples): each has a `schema.sql` (disposable dev database only), a `config/dev.yml` environment, a `config/secrets.example.json` and a `testdata/regression` case. Copy `secrets.example.json` to `dev.secrets.json`, set the password and run `idb-crypt --environment dev --config-dir examples/<engine>/config`.

The location is illustrative: InitiumDb does not require a particular Playwright or application directory layout. **The directory is the case**: `--case` names it, its name is the case name, and it holds a `case.yml` plus, for each database alias, files with fixed names (below).

## Case configuration

`case.yml` (always this name, inside the case directory) lists **which** databases to process and in what order, not **where** they are hosted or **how** to authenticate. What to capture and prepare is in files whose names are fixed by convention.

```yaml
version: 1
databases:
  - alias: CRM
  - alias: BILLING
```

- `version`: configuration format version.
- `databases`: **ordered** list of steps, each with the logical database `alias`, resolved by the environment configuration.

For each alias the case directory holds:

| File | Used by | Purpose |
| --- | --- | --- |
| `<ALIAS>.queries.yml` | `idb-capture` (and `idb-prepare` for the schema, when present) | ordered capture queries |
| `<ALIAS>.dataset.xml` | both | Flat XML dataset written by `idb-capture` and applied by `idb-prepare` |
| `<ALIAS>.pre.sql` | `idb-prepare` | optional hook run before the dataset; absent or empty runs nothing |
| `<ALIAS>.post.sql` | `idb-prepare` | optional hook run after the dataset; absent or empty runs nothing |

A missing queries file fails `idb-capture`; a missing dataset file fails `idb-prepare`. Two cases never share files: each lives in its own directory.

### Capture queries

Example `CRM.queries.yml`:

```yaml
schema: CRM_OWNER
tables:
  - name: CUSTOMER
    sql: |
      SELECT * FROM CUSTOMER WHERE CUSTOMER_ID IN (1001, 1002)
  - name: ACCOUNT
    sql: |
      SELECT * FROM ACCOUNT WHERE CUSTOMER_ID IN (1001, 1002)
  - name: PRODUCT
```

`sql` is optional: without it the table is captured whole, as `SELECT * FROM "<name>"` (the name is double-quoted, so it is matched exactly as written, per the identifier rules below). Give `sql` to filter rows or order them.

The order of `tables` is significant. Query results must be serialized into a dataset preserving the configured table order. For deterministic data files, queries should specify an `ORDER BY` where row order matters. Queries are supplied by trusted project maintainers; they are not a general-purpose untrusted SQL input interface.

### SQL identifiers and quoting

Two kinds of values follow two different rules:

- **Fields that name database objects** (`schema`, `tables[].name`) are written **exactly as in the data dictionary**, with the same case. InitiumDb never folds their case. When it must put one into a statement of its own (such as the session schema), it double-quotes it so the engine takes it literally. For example, `schema: CRM_OWNER` is right for an Oracle schema created unquoted, and `schema: crm_owner` for a PostgreSQL one. Table names may have mixed case (`Customer`) and are matched exactly.
- **SQL and SQL fragments** (`sql` in `queries.yml` when given, hook `.sql` scripts) are sent to the engine unchanged, so the engine's own rules for case and quoting apply: unquoted identifiers fold (upper case in Oracle, lower case in PostgreSQL) and names with mixed case or special characters need double quotes.

When applying a dataset, DBUnit resolves table and column names against the database metadata and generates quoted SQL, so names with mixed case or special characters are handled. Table and column names become element and attribute names in the Flat XML dataset, so they must be valid XML names (no spaces). `idb-capture` fails with a message naming the table or column, and leaves any existing dataset untouched.

DBUnit folds a schema name to the engine's case on its own; InitiumDb overrides that so schemas created with quoted mixed-case names (for example `"CrmOwner"`) work too. This is verified against PostgreSQL and Oracle containers (`-Ppostgresql-integration`, `-Poracle-integration`; they need a running Docker).

### Captured dataset

Example `CRM.dataset.xml`:

```xml
<dataset>
  <CUSTOMER CUSTOMER_ID="1001" NAME="Demo Customer" STATUS="ACTIVE"/>
  <ACCOUNT ACCOUNT_ID="2001" CUSTOMER_ID="1001" BALANCE="50000"/>
</dataset>
```

DBUnit Flat XML is the intended interchange format. Actual handling of nulls, types, binary data, generated keys and table metadata must be verified for each supported database engine. The implementation must preserve dataset table order when applying `REFRESH`; it must not silently invoke a dependency-based reordering strategy.

## Environment configuration

Environment configuration maps case-level aliases to physical databases. It is kept **separate from case definitions** so the same case can target development or QA without editing its datasets.

Illustrative `dev.yml`:

```yaml
version: 1
environment: dev

databases:
  CRM:
    engine: oracle
    jdbcUrl: "jdbc:oracle:thin:@//dev-crm:1521/CRMDEV"
    username: "TDM_USER"

  BILLING:
    engine: postgresql
    jdbcUrl: "jdbc:postgresql://dev-billing:5432/billing"
    username: "tdm_user"
```

A corresponding `qa.yml` would define the same logical aliases with QA endpoints and credentials. Environment files may be managed centrally or supplied locally; they need not be committed to each application repository.

**Names.** Database aliases (in `case.yml` and the environment file) and credential names (in the secrets file and in `credential`) must be written in upper case: letters `A-Z`, digits and `_`, starting with a letter. Every command that reads those files rejects other names, and lookups are exact. This also keeps the matching environment variable names (below) identical to the name, so they can never collide. Database object names (schema, tables, columns) are a different matter and stay exact (see [SQL identifiers and quoting](#sql-identifiers-and-quoting)).

**`credential` is optional.** It names the secret holding the database password and defaults to the alias. Set it only to share one secret between aliases or to use another name.

**Environment variables.** Each field of a database can be given by `IDB_<ALIAS>_ENGINE`, `IDB_<ALIAS>_URL` and `IDB_<ALIAS>_USER` (the alias as written); the password comes from `IDB_CREDENTIAL_<NAME>`. A variable wins over the file, and the environment file may be absent when every database of the case is fully defined by variables, which is the intended setup for CI/CD:

```bash
IDB_CRM_ENGINE=postgresql IDB_CRM_URL=jdbc:postgresql://dev-crm:5432/crm IDB_CRM_USER=tdm_user \
IDB_CREDENTIAL_CRM=... idb-prepare --case testdata/regression --environment dev
```


**Environment portability has constraints:** a dataset captured in DEV is not automatically valid in QA if identifiers collide, referenced records are absent, or environment-specific values differ. The first design target is portable datasets without automatic value transformations; cases must be constructed accordingly. Per-environment datasets or value substitutions are **not currently defined**.

## Connections and secrets

Secrets are never stored in `case.yml` or `dataset.xml`, and must not appear in logs.

InitiumDb separates connection resolution from data operations. The intended Java abstraction is:

```java
public interface ConnectionResolver {
    java.sql.Connection resolve(
        String environment,
        String databaseAlias
    ) throws java.sql.SQLException;
}
```

The built-in resolver reads each password from the `IDB_CREDENTIAL_<NAME>` environment variable (the credential name as written), falling back to the encrypted local secrets file (see [Local secrets](#local-secrets)). A corporate resolver may integrate with a **proprietary CD mechanism** without coupling the open-source core to it. The exact external credential-provider contract and CI-to-CD-mechanism access are **to be defined**.

### Initialization

```bash
idb-init --case path/to/testdata/regression --environment dev --database CRM [--database postgresql:BILLING ...] [--engine oracle|postgresql] [--config-dir dir]
```

Initialization is **cumulative and never overwrites**. `--database [ENGINE:]NAME` is repeatable (at least one). `NAME` is converted to upper case and may use letters, digits and `_`; `ENGINE` is `oracle` or `postgresql` (case-insensitive) and defaults to `--engine` (default `oracle`). `--case` is the case directory. For each database it ensures a step `- alias: NAME` in `case.yml`, and, in the case directory, a `<NAME>.queries.yml` template and empty `<NAME>.pre.sql` and `<NAME>.post.sql` hook files (`idb-prepare` accepts empty hooks; the files are created when missing even if the database was already in the case), and in the configuration directory an entry in `<environment>.yml` (engine and a JDBC URL placeholder for that engine) and an empty entry in `<environment>.secrets.json` (for example `{"CRM": ""}`). Files that do not exist are created; files that exist only receive the databases they do not contain yet; an existing database entry, query file or secret is never modified. Case, environment and queries files are read and written with a YAML library and the secrets file with a JSON library, never as text: a rewritten YAML file keeps its content and key order but **not its comments or layout**. If an existing file cannot be read or extended (for example the environment file declares another environment, or `databases` in the case is not a list) nothing is written. The command reports each file as Created, Updated or Unchanged. It also creates the master key if it does not exist (see below). Templates contain no credentials.

The configuration directory defaults to `~/.initiumdb`. The environment name accepts letters, digits, `_` and `-`.

### Local secrets

```bash
idb-crypt --environment dev [--case path/to/testdata/regression] [--config-dir dir]
```

`<config-dir>/<environment>.secrets.json` maps each credential name (the `credential` of the environment file, by default the alias) to its password. `idb-crypt` encrypts every non-empty cleartext value in place with AES-256-GCM (`enc:v1:aes256gcm:<nonce>:<ciphertext>`); already encrypted and empty values are left untouched, so it is idempotent. `idb-capture` and `idb-prepare` do the same on the fly: when the secrets file is the source of a password and holds cleartext values, they encrypt the whole file in place (creating the master key if none exists), report it on stderr without printing any secret, and carry on with the password; a password given by `IDB_CREDENTIAL_<NAME>` never touches the file. A missing, malformed or non-string-valued secrets file fails with exit code `1`. `--case` is accepted so an `idb-prepare` command line can be reused, but it is not read.

Master key: the `IDB_MASTER_KEY` environment variable (hex of 64 chars, base64 of 32 bytes, or a raw 32-byte value); otherwise `~/.initiumdb/idb.key`, created with owner-only permissions where the file system supports POSIX permissions. This is protection of secrets at rest on one machine, not a vault: anyone holding the key can decrypt. Neither command prints decrypted secrets, and error messages never echo secrets or key material. How CI/CD supplies the key or integrates a corporate provider (e.g. a proprietary CD mechanism) remains **to be defined**.

A plain environment label such as `environment: qa` cannot establish that the JDBC endpoint is truly non-production. Deployments should use restricted database accounts and enforce environment boundaries outside InitiumDb as well.

## Integration with CI/CD and Playwright

The intended sequence is:

```text
Application deployment (CD)
          |
          v
idb-prepare (CI server)
          |
          v
Playwright E2E tests
          |
          v
Test results
```

The CI server invokes InitiumDb as a separate command. Playwright tests do not need to import InitiumDb or call its API. `idb-prepare` is executed **once before the configured suite**, rather than between individual tests.

If `idb-prepare` exits with a nonzero status, the pipeline should fail that step and not proceed to E2E tests.

## Runtime and database support

- **Implementation language:** Java.
- **Database access:** JDBC.
- **Dataset engine:** DBUnit.
- **Dataset format:** DBUnit Flat XML.
- **Configuration format:** YAML.
- **Initial target engines:** Oracle and PostgreSQL.
- **Distribution:** four Java CLI executables (`idb-capture`, `idb-prepare`, `idb-init`, `idb-crypt`); minimum Java version is 25 (LTS); distributed as a folder with launcher scripts (see [Installation](#installation)).

Engine-specific behavior is isolated in a thin per-engine profile (DBUnit data type factory and properties, session schema). There is no requirement for database-resident InitiumDb code or objects.

## Errors, repeatability and observability

- Commands execute sequentially and stop on failure.
- Successful execution returns exit code `0`; any failure returns `1`.
- Logs identify the case, environment, database alias, current step and error cause without revealing credentials.
- There is no dedicated `validate` command, `--mode` switch, preview mode, persistent status table or execution checkpoint.
- Input validation occurs naturally when commands read configuration and files; invalid input fails the command.
- Re-running `idb-prepare` starts from the first configured step, even if a prior run partially completed.
- Repeatability depends on the configured dataset and SQL hooks. SQL hooks that are not idempotent can prevent safe reruns.

## Limitations

- **Not for production databases.**
- **Not a full rollback or snapshot restore.** `REFRESH` does not remove all changes left by previous tests.
- **No automatic referential dependency discovery.** The case author supplies the correct order; database constraints enforce integrity.
- **No parallelism or resume support.**
- **No distributed transactions.** Changes across databases may be partially committed on failure.
- **No automatic masking, synthetic data generation or data transformation.**
- **No creation or management of schemas, permissions or control tables.**

## Open questions

The following items should be settled before considering this README an implementation contract:

1. Precise DBUnit table-order handling and metadata requirements for Oracle/PostgreSQL.
2. Whether the configuration directory needs a discovery mechanism beyond `--config-dir` and `~/.initiumdb`.
3. Secret-provider contract and integration with CI/CD and proprietary CD mechanisms.
4. Handling of case-specific cleanup, sequence values, triggers and generated identifiers.
5. Portability rules for datasets captured in one environment and applied to another.
6. Release process.

## License and governance

The intended governance model is an open-source core, public issue tracking and contributions, documented releases, and maintenance by **Inversiones Etimo SpA**. **Apache License 2.0 is proposed but not yet finalized for InitiumDb.**

Any future proprietary extensions (such as an integration with an internal CD mechanism) should remain separate from the open-source core. Ownership, trademark and commercial-edition statements must be reviewed for consistency with the selected open-source license before publication.

## Project intent

InitiumDb aims to make **initial test data a reproducible, versioned part of automated testing**, without turning development and QA database preparation into a database administration platform.
