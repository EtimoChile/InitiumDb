# Current state

- Project bootstrapped: governance, test prompts, empty Maven project (Java 25, `cl.etimo.initiumdb`).
- Packages: `core`, `db` (engine contract; adapters in `db/<engine>`), `cli`; only `package-info.java` so far.
- No engines, CLI, or features implemented yet.
- Tooling: Maven, JUnit 5, AssertJ, Mockito, Spotless (google-java-format), `-Xlint:all -Werror`, pre-commit.
