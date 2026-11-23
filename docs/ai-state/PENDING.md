# Pending

States: `OPEN`, `IN_PROGRESS`, `DEFERRED`, `FUTURE`. Remove when resolved.

| ID | Status | Pending | Owner artifact | Next action |
|---|---|---|---|---|
| PEND-002 | OPEN | Confirm Maven as build tool (DEC-002) | `pom.xml` | Owner confirms or changes DEC-002 |
| PEND-003 | OPEN | Verify the build on JDK 25: Spotless/google-java-format and Mockito versions in `pom.xml` were not run on it (local JDK is 1.7) | `pom.xml` | Install JDK 25, run `mvn verify`, bump plugin versions as needed |
| PEND-004 | OPEN | Decide per-engine component: thin engine profile (DBUnit `IDataTypeFactory` and `DatabaseConfig` per engine) vs full adapter hierarchy | `cl.etimo.initiumdb.db` | Owner decides; record as DEC-003 |
