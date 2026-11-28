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

## Credential providers

`idb-capture` and `idb-prepare` obtain each database password from a
`CredentialProvider` (`cl.etimo.initiumdb.core`), looked up by the `credential`
name of the environment configuration:

```java
public interface CredentialProvider {
  String password(String credential);
}
```

The built-in `DefaultCredentialProvider` reads the `IDB_CREDENTIAL_<NAME>`
environment variable and falls back to the encrypted local secrets file.
`JdbcConnectionResolver` combines the provider with the environment
configuration to open the connection, so a provider deals only with secrets,
never with JDBC URLs or connections.

A provider for another source (a corporate vault, the secret store of a CI/CD
platform) implements the same interface. It must:

- never log, print or include the password in an exception message;
- fail with an `InitiumDbException` that names the credential and the source,
  so the command reports it and exits with code `1`;
- not cache passwords beyond the run of a command.

A provider is plugged in where `DataCommand` (`cl.etimo.initiumdb.cli`) builds
the `JdbcConnectionResolver`, in place of or around `DefaultCredentialProvider`
(for example, trying the new source first and falling back to the built-in
one), and the distribution is rebuilt with `mvn package`.

## Branches and releases

- `development` is the integration branch; work branches start from it and
  pull requests target it.
- `main` holds releases only and is protected: changes arrive through a pull
  request from a `release/X.Y.Z` branch, merged with a merge commit.
- Releases are tagged `vX.Y.Z` on `main`; the tag triggers
  `.github/workflows/release.yml`.

`mvn package` builds the distribution folder
`target/initiumdb-<version>-dist/initiumdb/` and the archives
`target/initiumdb-<version>-dist.zip` and `.tar.gz`. On the `development`
branch the version is `X.Y.Z-SNAPSHOT` (a build in progress); a release has no
suffix.

To release from `main` (for example `0.3.0`):

1. Rename the `Unreleased` heading of `CHANGELOG.md` to the version being
   released and the release date, and commit it on `development`.
2. On a branch `release/0.3.0` cut from `development`, set the version and
   commit it: `mvn versions:set -DnewVersion=0.3.0 -DgenerateBackupPoms=false`,
   then `git commit -am "Release 0.3.0"`. `main` is protected: push the branch
   and merge it with a pull request, using a merge commit (not squash) so the
   tag stays on `main`.
3. After the merge, tag the merge commit on `main` and push the tag:
   `git tag v0.3.0 && git push origin v0.3.0`.
4. The `Release` workflow (`.github/workflows/release.yml`) checks that the tag
   equals the pom version, runs `mvn clean verify` and publishes the GitHub
   release with the archives and `SHA256SUMS`.
5. Back on `development`, merge `main` and move to the next snapshot:
   `mvn versions:set -DnewVersion=0.4.0-SNAPSHOT -DgenerateBackupPoms=false`.
