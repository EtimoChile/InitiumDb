# Current state

- Project bootstrapped: governance, test prompts, Maven project (Java 25, `cl.etimo.initiumdb`).
- Implemented: `idb-init`, `idb-crypt` (DEC-003), `idb-capture` and `idb-prepare` (DBUnit `REFRESH`, declared table order) in `cli`, with `core` services, configuration loaders, `ConnectionResolver`/`CredentialProvider` and `DbUnitSession`.
- `idb-init --database [ENGINE:]NAME` (repeatable, required, upper-cased) is cumulative and never overwrites: it creates or extends `case.yml`, `<NAME>.queries.yml`, environment and secrets files, adding only missing databases, with empty `<NAME>.pre.sql`/`<NAME>.post.sql` hooks that `idb-prepare` accepts (append-only, verified by re-parsing; no `--overwrite`). Flat per-database files next to `case.yml`; `case.yml` format unchanged, explicit paths still win.
- Aliases and credential names: upper case, digits, `_` (`core/Names`), validated in case, environment, secrets files and `idb-init`; `credential` optional (default alias); `IDB_<ALIAS>_ENGINE/URL/USER` override or replace the environment file.
- Engines: `oracle` and `postgresql` as thin profiles (DEC-004); object names exact, SQL by engine rules (DEC-005).
- `idb-capture` rejects table or column names that are not valid XML names (PEND-006 tracks encoding them).
- Tooling: Maven, JUnit 5, AssertJ, Mockito 5.20.0 with byte-buddy 1.17.7 (needed on Java 25), Testcontainers 2.0.2, Spotless (google-java-format 1.28.0), `-Xlint:all -Werror`, pre-commit.
- Verified on JDK 25.0.2 (`C:\Util\Java\jdk-25.0.2`; the default `JAVA_HOME` is JDK 1.7, so set it explicitly): `mvn verify` (59 unit tests, 3 `DistributionIT`) and `mvn spotless:check` pass.
- JDBC drivers are user-supplied (`drivers/` folder, DEC-008).
- Packaging: `mvn package` builds the folder distribution and archives (DEC-008); `DistributionIT` (Failsafe, in `mvn verify`) runs the launchers; the shell launchers were checked on Git Bash only, not on a real Unix.
- Opt-in integration tests against disposable containers (Docker required): `-Ppostgresql-integration` and `-Poracle-integration`, 2 tests each, both pass.
