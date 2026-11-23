# InitiumDb

**Configuration-driven preparation of relational database test data.**

InitiumDb captures selected records from development or QA databases and prepares those records again before automated test suites. The datasets are stored as version-controlled files alongside the tests, making the initial data setup repeatable without requiring a database clone or proprietary test framework.

InitiumDb is intended **only for development and QA databases, never production**. It does not provision databases, create schemas or control objects, manage grants, or execute application tests.

> **Project status:** design specification. The CLI, configuration examples and behavior described below are the intended contract, not a claim that an implementation has already been released. Items explicitly marked *to be defined* remain open.

## Overview

- **Capture only what matters.** Developers first establish the desired data manually, using application interfaces or SQL. `capture` then executes configured `SELECT` statements and exports the selected rows.
- **Prepare test data on demand.** `prepare` applies saved datasets to a target development or QA environment before tests.
- **Keep test definitions in Git.** Case definitions, queries, SQL hooks and datasets live in the application's test repository.
- **Separate logical and physical configuration.** A case references logical database aliases; environment configuration maps them to JDBC connections.
- **Keep credentials outside test artifacts.** Secrets are resolved at runtime, for local executions and CI/CD alike.
- **Respect declared order.** Database steps and table rows are processed in the order specified; InitiumDb does not discover or reorder foreign-key dependencies.
- **Stay framework-agnostic.** Playwright is the current E2E framework at SII, but InitiumDb does not depend on it.
- **Fail simply.** On error, report the failure and return a nonzero exit code. There are no checkpoints or partial-run resumption mechanisms; the operator or pipeline reruns the command from the beginning.

## Scope and design principles

1. **Non-production use only.** Intended targets are development and QA databases. Access controls and independently verified connection settings should prevent accidental production access; a YAML environment label alone is not a security boundary.
2. **Two executables only.** `idb-capture` and `idb-prepare`. There is no `validate`, `replay`, or `--mode` interface.
3. **Sequential execution.** No parallelism or dependency graph. The author of the case controls the order of database steps, queries and dataset tables.
4. **No database installation.** No InitiumDb tables, locks, users, roles, grants, sequences or other objects are created.
5. **No automatic recovery.** No persisted execution state or resume-from-checkpoint logic.
6. **Database-enforced integrity.** Existing foreign keys and constraints remain authoritative. If an operation violates a constraint, the database reports an error and the command fails.
7. **Minimal privilege.** `capture` requires read access to selected tables; `prepare` requires only the DML privileges needed for the configured data and optional SQL hooks.
8. **No production-data-management guarantees.** InitiumDb is not an ILM, archiving, backup, masking or full database restoration tool.

## Commands

```bash
idb-capture --case path/to/testdata/case.yml --environment dev
idb-prepare --case path/to/testdata/case.yml --environment qa
```

Both executables may run manually. `idb-capture` is **not** part of CI/CD. `idb-prepare` is normally invoked by CI/CD before tests, but can also be invoked manually.

The `--environment` argument identifies an external environment configuration. An explicit configuration path option may be provided by the implementation; its exact CLI spelling is **to be defined**.

### `idb-capture`

1. Read the case definition and the selected environment configuration.
2. Resolve each logical database alias and its credentials.
3. Execute the configured `SELECT` statements in their declared order.
4. Write the selected records to the dataset files, using DBUnit Flat XML format.
5. Exit successfully only if the capture completes.

`idb-capture` does not construct the test scenario: the developer has already prepared the desired data. Captured datasets must be reviewed before committing them to Git; **InitiumDb does not automatically anonymize or mask data**.

### `idb-prepare`

1. Read the case definition and the selected environment configuration.
2. Resolve each logical database alias and its credentials.
3. Process the configured database steps sequentially.
4. For each step, run optional `preSql`, apply the dataset, then run optional `postSql`.
5. Exit successfully only if every step completes.

The initial dataset operation is DBUnit **`REFRESH`**: update matching rows and insert missing rows according to DBUnit's key matching. `REFRESH` **does not delete unrelated or extra rows**, so `idb-prepare` does not promise to restore an entire database or table to an exact snapshot. Optional SQL hooks can perform case-specific cleanup when needed.

A failure stops the run. Previously committed changes may remain, especially across multiple databases; there is **no distributed transaction**. Re-running the entire `idb-prepare` command is the recovery strategy. Consequently, datasets and SQL hooks should be designed to be safely repeatable.

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
        regression/
          case.yml
          db/
            CRM/
              queries.yml
              dataset.xml
              pre.sql
              post.sql
            BILLING/
              queries.yml
              dataset.xml
```

The location is illustrative: InitiumDb does not require a particular Playwright or application directory layout. Paths in `case.yml` are resolved relative to the case file.

## Case configuration

`case.yml` describes **what** to capture and prepare, not **where** the databases are hosted or **how** to authenticate.

```yaml
version: 1
name: regression

databases:
  - alias: CRM
    queries: db/CRM/queries.yml
    dataset: db/CRM/dataset.xml
    preSql: db/CRM/pre.sql
    postSql: db/CRM/post.sql

  - alias: BILLING
    queries: db/BILLING/queries.yml
    dataset: db/BILLING/dataset.xml
```

- `version`: configuration format version.
- `name`: human-readable case/suite identifier.
- `databases`: **ordered** list of steps. Multiple steps may reference the same logical database if necessary.
- `alias`: logical database identifier, resolved by the environment configuration.
- `queries`: ordered capture query definitions; used only by `idb-capture`.
- `dataset`: Flat XML file written by `idb-capture` and consumed by `idb-prepare`.
- `preSql` / `postSql`: optional SQL scripts executed only by `idb-prepare`, immediately before and after the dataset operation.

The precise YAML schema, optional fields and error behavior for missing files will be finalized during implementation.

### Capture queries

Example `db/CRM/queries.yml`:

```yaml
schema: CRM_OWNER
tables:
  - name: CLIENTE
    sql: |
      SELECT * FROM CLIENTE WHERE ID_CLIENTE IN (1001, 1002)
  - name: CUENTA
    sql: |
      SELECT * FROM CUENTA WHERE ID_CLIENTE IN (1001, 1002)
```

The order of `tables` is significant. Query results must be serialized into a dataset preserving the configured table order. For deterministic data files, queries should specify an `ORDER BY` where row order matters. Queries are supplied by trusted project maintainers; they are not a general-purpose untrusted SQL input interface.

### Captured dataset

Example `db/CRM/dataset.xml`:

```xml
<dataset>
  <CLIENTE ID_CLIENTE="1001" NOMBRE="Cliente Demo" ESTADO="ACTIVO"/>
  <CUENTA ID_CUENTA="2001" ID_CLIENTE="1001" SALDO="50000"/>
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
    credential: "crm"

  BILLING:
    engine: postgresql
    jdbcUrl: "jdbc:postgresql://dev-billing:5432/billing"
    username: "tdm_user"
    credential: "billing"
```

A corresponding `qa.yml` would define the same logical aliases with QA endpoints and credentials. Environment files may be managed centrally or supplied locally; they need not be committed to each application repository.

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

A generic JDBC resolver may obtain credentials from environment variables or an external secret provider. A corporate resolver may integrate with **BYD** without coupling the open-source core to BYD. The exact credential-provider interface, provider selection, local secret-file format (if any), and TeamCity-to-BYD access mechanism are **to be defined**.

A plain environment label such as `environment: qa` cannot establish that the JDBC endpoint is truly non-production. Deployments should use restricted database accounts and enforce environment boundaries outside InitiumDb as well.

## Integration with CI/CD and Playwright

At SII, the intended sequence is:

```text
Application deployment (BYD)
          |
          v
idb-prepare (TeamCity)
          |
          v
Playwright E2E tests
          |
          v
Test results
```

TeamCity invokes InitiumDb as a separate command. Playwright tests do not need to import InitiumDb or call its API. `idb-prepare` is executed **once before the configured suite**, rather than between individual tests.

If `idb-prepare` exits with a nonzero status, the pipeline should fail that step and not proceed to E2E tests.

## Runtime and database support

- **Implementation language:** Java.
- **Database access:** JDBC.
- **Dataset engine:** DBUnit.
- **Dataset format:** DBUnit Flat XML.
- **Configuration format:** YAML.
- **Initial target engines:** Oracle and PostgreSQL.
- **Distribution:** two Java CLI executables (`idb-capture` and `idb-prepare`); exact packaging and minimum Java version **to be defined**.

Engine-specific behavior should be isolated behind adapters where required. There is no requirement for database-resident InitiumDb code or objects.

## Errors, repeatability and observability

- Commands execute sequentially and stop on failure.
- Successful execution returns exit code `0`; failure returns a nonzero code.
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

1. Transaction boundaries for each database step and each optional SQL hook.
2. Precise DBUnit table-order handling and metadata requirements for Oracle/PostgreSQL.
3. Exact environment configuration discovery and CLI option names.
4. Secret-provider contract and integration with TeamCity/BYD.
5. Handling of case-specific cleanup, sequence values, triggers and generated identifiers.
6. Portability rules for datasets captured in one environment and applied to another.
7. Java baseline, packaging, installation and release process.

## License and governance

The intended governance model draws on the established LimitsDb project: an open-source core, public issue tracking and contributions, documented releases, and maintenance by **Inversiones Etimo SpA**. **Apache License 2.0 is proposed but not yet finalized for InitiumDb.**

Any future proprietary extensions (such as an internal BYD integration) should remain separate from the open-source core. Ownership, trademark and commercial-edition statements must be reviewed for consistency with the selected open-source license before publication.

## Project intent

InitiumDb aims to make **initial test data a reproducible, versioned part of automated testing**, without turning development and QA database preparation into a database administration platform.
