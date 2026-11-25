# Pending

States: `OPEN`, `IN_PROGRESS`, `DEFERRED`, `FUTURE`. Remove when resolved.

| ID | Status | Pending | Owner artifact | Next action |
|---|---|---|---|---|
| PEND-005 | FUTURE | External secret-provider contract (proprietary CD mechanism, CI delivery of the master key); the built-in environment-variable and secrets-file provider is implemented (DEC-003) | `README.md` | Owner defines the provider contract |
| PEND-006 | FUTURE | Support table and column names that are not valid XML names (spaces, symbols) by encoding them in the Flat XML dataset (ISO 9075 style `_xHHHH_`, literal `_x` as `_x005F_x`) with encode/decode `IDataSet` wrappers in capture and prepare; replaces the capture-time rejection | `CaptureService`, `PrepareService` | Owner schedules; round-trip test with a `"Full Name"` column on PostgreSQL and Oracle |
| PEND-011 | OPEN | Verify data types per engine: nulls, binary/LOB, dates and timestamps, generated keys (only numeric and text columns are tested) | `integration/*RoundTripTest` | Add container round-trip tests per type |
| PEND-012 | OPEN | Run the `examples/oracle` and `examples/postgresql` cases end to end with the commands | `examples/` | Add integration tests or a documented manual run |
| PEND-013 | OPEN | Verify DBUnit table-order handling and metadata requirements beyond the simple cases (README open question) | `PrepareService`, `integration/` | Add tests with several tables and foreign keys on both engines |
| PEND-015 | FUTURE | Case-specific cleanup, sequence values, triggers and generated identifiers (README open question) | `README.md` | Decide from real usage |
| PEND-016 | FUTURE | Portability rules for datasets captured in one environment and applied to another (README open question) | `README.md` | Decide from real usage |
