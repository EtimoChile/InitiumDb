# Contributing to InitiumDb

Thank you for your interest in contributing to **InitiumDb**.

This project is maintained by **Inversiones Etimo SpA** and welcomes
community contributions that improve stability, usability, and functionality.

---

## Types of Contributions

Contributions are welcome in the following areas:

- Bug reports
- Bug fixes
- Documentation improvements
- Performance improvements
- Tests
- Feature proposals

Before implementing large changes, please open an **issue** to discuss the
proposal with the maintainers.

---

## Reporting Issues

If you discover a bug or unexpected behavior, please open an issue and include:

- InitiumDb version (`idb-prepare --version`)
- Java version
- Database engine and version, and JDBC driver version
- Steps to reproduce the issue
- Expected vs actual behavior
- Relevant logs or error messages

Never include credentials, JDBC URLs with secrets, or real data in an issue.
Clear reports help us resolve issues faster.

---

## Pull Requests

Pull requests are welcome and target the `development` branch.

Please ensure that:

- The change is clearly described
- Code follows the project's style conventions (Spotless / google-java-format)
- Tests pass
- Documentation (`README.md`) and `CHANGELOG.md` are updated when needed

Small, focused pull requests are preferred. All project content (code,
documentation, comments, commits, pull requests) is written in English.

---

## Development Setup

### Prerequisites

- JDK 25
- [Apache Maven](https://maven.apache.org/) 3.9 or newer
- Docker, only for the opt-in container integration tests

### Clone and build

```bash
git clone https://github.com/EtimoChile/InitiumDb.git
cd InitiumDb
mvn verify
```

### Run the quality suite

```bash
mvn spotless:check
mvn verify
```

Before opening a pull request, all checks must pass. The CI pipeline runs
these checks on every push and pull request targeting `development` and `main`.

### Integration tests against containers

Integration tests are opt-in and need a running Docker. They start a disposable
PostgreSQL or Oracle container; never point them at a shared or production
database.

```bash
mvn test -Ppostgresql-integration
mvn test -Poracle-integration
```

See [`DEVELOPMENT.md`](DEVELOPMENT.md) for the daily workflow.

---

## Licensing of contributions

InitiumDb is licensed under the [Apache License 2.0](LICENSE). By submitting a
contribution you agree that it is licensed under the same terms, as stated in
section 5 of that license. See also [`LICENSE-DUAL.md`](LICENSE-DUAL.md) and
[`COMMERCIAL-EDITION-NOTICE.md`](COMMERCIAL-EDITION-NOTICE.md).
