# Pending

States: `OPEN`, `IN_PROGRESS`, `DEFERRED`, `FUTURE`. Remove when resolved.

| ID | Status | Pending | Owner artifact | Next action |
|---|---|---|---|---|
| PEND-005 | OPEN | External secret-provider contract (proprietary CD mechanism, CI delivery of the master key); the built-in environment-variable and secrets-file provider is implemented (DEC-003) | `README.md` | Owner defines the provider contract |
| PEND-006 | FUTURE | Support table and column names that are not valid XML names (spaces, symbols) by encoding them in the Flat XML dataset (ISO 9075 style `_xHHHH_`, literal `_x` as `_x005F_x`) with encode/decode `IDataSet` wrappers in capture and prepare; replaces the capture-time rejection | `CaptureService`, `PrepareService` | Owner schedules; round-trip test with a `"Full Name"` column on PostgreSQL and Oracle |
