# InitiumDb — Development Guide

## Setup

Install JDK 25 and Maven 3.9 or newer, and make sure `JAVA_HOME` points to the
JDK (the compiler targets Java 25).

Install the Git hook so Spotless and the unit tests run on commits:

```bash
pre-commit install
```

## Daily workflow

Format and check formatting:

```bash
mvn spotless:apply
mvn spotless:check
```

Run the unit tests, or the whole build including the distribution tests:

```bash
mvn test
mvn verify
```

`mvn verify` also builds the folder distribution and the `.zip` / `.tar.gz`
archives under `target/`, and runs `DistributionIT` against the generated
launchers. The compiler runs with `-Xlint:all -Werror`.

Run all hooks without committing:

```bash
pre-commit run --all-files
```

Pull requests and pushes to `development` or `main` run `spotless:check` and
`mvn verify` in GitHub Actions; pull requests also run the PostgreSQL and Oracle
container jobs. `quality`, `postgresql-integration` and `oracle-integration` are
required checks on both branches.

## Coverage

`mvn verify` writes the JaCoCo report to `target/site/jacoco/`. In CI, the
container jobs add their execution data to the unit-test data and upload the
combined report as a workflow artifact (14 days).

## Managing dependencies

Versions are properties in `pom.xml`. JDBC drivers are `provided`: they are on
the build and test classpath but never in the distribution (DEC-008). When a
dependency changes, update [`THIRD_PARTY_LICENSES.md`](THIRD_PARTY_LICENSES.md).

## Container integration tests

They are excluded from the default build and need Docker. Each starts a
disposable engine; never use a shared or production database.

```bash
mvn test -Ppostgresql-integration
mvn test -Poracle-integration
```

The Oracle image is large and slow to start; allow several minutes.

## Branches and releases

- `development` is the integration branch; work branches start from it and
  pull requests target it.
- `main` holds releases only and is protected: changes arrive through a pull
  request from a `release/X.Y.Z` branch, merged with a merge commit.
- Releases are tagged `vX.Y.Z` on `main`; the tag triggers
  `.github/workflows/release.yml`. The step-by-step procedure is in the
  "Building and releasing" section of `README.md`.
- Before tagging, rename the `Unreleased` heading of `CHANGELOG.md` to the
  version and date it is released.
