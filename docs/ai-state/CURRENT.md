# Current state

- Project bootstrapped: governance, test prompts, Maven project (Java 25, `cl.etimo.initiumdb`).
- Implemented: `idb-init` and `idb-crypt` (DEC-003) in `cli`, with `core` classes `SecretCipher`, `MasterKey`, `SecretsFile`, `ConfigLayout`, `ProjectInitializer`.
- Not implemented: engines, `idb-capture`, `idb-prepare`.
- Tooling: Maven, JUnit 5, AssertJ, Mockito, Spotless (google-java-format 1.28.0), `-Xlint:all -Werror`, pre-commit.
- Verified on JDK 25.0.2 (`C:\Util\Java\jdk-25.0.2`; the default `JAVA_HOME` is JDK 1.7, so set it explicitly): `mvn verify` and `mvn spotless:check` pass, 21 tests.
