# Test organization

One test class per functional contract from the spec, not per source class.
The governing `README.md` section decides the class. Complements
`test-authoring.md`.

| Level | Location | Scope |
|---|---|---|
| core | `src/test/java/cl/etimo/initiumdb/*Test.java` | Engine-independent contract |
| adapter | `src/test/java/cl/etimo/initiumdb/adapters/<engine>/` | Engine SQL/DDL, mocked connection |
| integration | `src/test/java/cl/etimo/initiumdb/integration/<engine>/` | Real disposable DB; opt-in `@Tag("<engine>_integration")` |

Integration tests are excluded by default; run with `mvn test -Poracle-integration`
or `mvn test -Ppostgresql-integration` (tags `oracle_integration`, `postgresql_integration`). Test resources go in `src/test/resources`.

Error paths live in the class of the contract that raises them.

Contract-to-class map (extend as contracts are defined):

| Contract | Class |
|---|---|
| README > Initialization (`idb-init`) | `ProjectInitializationTest` |
| README > Local secrets (`idb-crypt`) | `SecretEncryptionTest` |
