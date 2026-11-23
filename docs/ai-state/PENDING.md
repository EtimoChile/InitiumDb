# Pending

States: `OPEN`, `IN_PROGRESS`, `DEFERRED`, `FUTURE`. Remove when resolved.

| ID | Status | Pending | Owner artifact | Next action |
|---|---|---|---|---|
| PEND-005 | FUTURE | External secret-provider contract (proprietary CD mechanism, CI delivery of the master key); the built-in environment-variable and secrets-file provider is implemented (DEC-003) | `README.md` | Owner defines the provider contract |
| PEND-006 | FUTURE | Support table and column names that are not valid XML names (spaces, symbols) by encoding them in the Flat XML dataset (ISO 9075 style `_xHHHH_`, literal `_x` as `_x005F_x`) with encode/decode `IDataSet` wrappers in capture and prepare; replaces the capture-time rejection | `CaptureService`, `PrepareService` | Owner schedules; round-trip test with a `"Full Name"` column on PostgreSQL and Oracle |
| PEND-007 | OPEN | Packaging: executable jar with dependencies and launcher scripts for `idb-capture`, `idb-prepare`, `idb-init`, `idb-crypt` (today `pom.xml` builds a plain jar) | `pom.xml`, `README.md` | Owner chooses packaging; implement and document |
| PEND-008 | OPEN | Add a `LICENSE` file; Apache 2.0 is proposed in the README but not finalized | `README.md` | Owner confirms the license; add file and update README |
| PEND-009 | OPEN | CI workflow for the project: `mvn verify` and `spotless:check`, plus the container integration profiles (`.github/` has no workflow) | `.github/` | Owner chooses CI service; add workflow |
| PEND-010 | OPEN | Release process: versioning, tags and publishing | `README.md`, `pom.xml` | Owner defines; no release without explicit request |
| PEND-011 | OPEN | Verify data types per engine: nulls, binary/LOB, dates and timestamps, generated keys (only numeric and text columns are tested) | `integration/*RoundTripTest` | Add container round-trip tests per type |
| PEND-012 | OPEN | Run the `examples/oracle` and `examples/postgresql` cases end to end with the commands | `examples/` | Add integration tests or a documented manual run |
| PEND-013 | OPEN | Verify DBUnit table-order handling and metadata requirements beyond the simple cases (README open question) | `PrepareService`, `integration/` | Add tests with several tables and foreign keys on both engines |
| PEND-015 | FUTURE | Case-specific cleanup, sequence values, triggers and generated identifiers (README open question) | `README.md` | Decide from real usage |
| PEND-016 | FUTURE | Portability rules for datasets captured in one environment and applied to another (README open question) | `README.md` | Decide from real usage |
| PEND-017 | OPEN | Conditional execution in SQL hooks without procedural code (DEC-007): a comment directive `-- idb:if-rows` / `-- idb:if-no-rows` marks the next statement as a guard query and runs the statement after it only if the guard returns (or does not return) a row; one statement per directive, no nesting or `else`; the splitter must read the directive before discarding comments; a directive without a following statement fails | `core/SqlScript`, `PrepareService`, `README.md` | Owner confirms the notation; implement with H2 and container tests; record as DEC |
