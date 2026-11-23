# Current state

- Project bootstrapped: governance, test prompts, Maven project (Java 25, `cl.etimo.initiumdb`).
- Implemented: `idb-init`, `idb-crypt` (DEC-003), `idb-capture` and `idb-prepare` (DBUnit `REFRESH`, declared table order) in `cli`, with `core` services, configuration loaders, `ConnectionResolver`/`CredentialProvider` and `DbUnitSession`.
- Engines: `oracle` and `postgresql` as thin profiles (DEC-004); object names exact, SQL by engine rules (DEC-005).
- `idb-capture` rejects table or column names that are not valid XML names (PEND-006 tracks encoding them).
- Tooling: Maven, JUnit 5, AssertJ, Mockito 5.20.0 with byte-buddy 1.17.7 (needed on Java 25), Testcontainers 2.0.2, Spotless (google-java-format 1.28.0), `-Xlint:all -Werror`, pre-commit.
- Verified on JDK 25.0.2 (`C:\Util\Java\jdk-25.0.2`; the default `JAVA_HOME` is JDK 1.7, so set it explicitly): `mvn verify` (56 tests) and `mvn spotless:check` pass.
- Opt-in integration tests against disposable containers (Docker required): `-Ppostgresql-integration` and `-Poracle-integration`, 2 tests each, both pass.
