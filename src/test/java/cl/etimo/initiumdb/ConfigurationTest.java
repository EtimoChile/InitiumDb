package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "Case configuration" and "Environment configuration". */
class ConfigurationTest {

  @TempDir Path tmp;
  Fixture fx;

  @BeforeEach
  void setUp() {
    fx = new Fixture(tmp);
    fx.write("CRM.dataset.xml", "<dataset/>");
  }

  @Test
  void aCaseWithoutVersionIsRejected() {
    // Spec: README.md > Case configuration
    // Given
    fx.writeCase("name: t\ndatabases:\n  - alias: CRM\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("version");
  }

  @Test
  void aCaseWithoutDatabaseStepsIsRejected() {
    // Spec: README.md > Case configuration
    // Given
    fx.writeCase("version: 1\ndatabases: []\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("databases");
  }

  @Test
  void aMissingCaseFileIsRejected() throws Exception {
    // Spec: README.md > Case configuration
    // Given
    java.nio.file.Files.delete(fx.caseFile());
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Case file not found");
  }

  @Test
  void aMissingEnvironmentFileIsRejected() throws Exception {
    // Spec: README.md > Environment configuration
    // Given
    java.nio.file.Files.delete(fx.configDir.resolve("dev.yml"));
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Environment file not found");
  }

  @Test
  void anEnvironmentFileDeclaringAnotherEnvironmentIsRejected() {
    // Spec: README.md > Environment configuration
    // Given
    fx.writeEnvironment(
        "version: 1\nenvironment: qa\ndatabases:\n  CRM:\n    engine: postgresql\n"
            + "    jdbcUrl: \"x\"\n    username: u\n    credential: C\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("declares environment 'qa'");
  }

  @Test
  void anUnsupportedEngineIsRejected() {
    // Spec: README.md > Runtime and database support
    // Given
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: mysql\n"
            + "    jdbcUrl: \"x\"\n    username: u\n    credential: C\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err())
        .contains("Unsupported engine 'mysql'")
        .contains("oracle")
        .contains("postgresql");
  }

  @Test
  void malformedEnvironmentYamlIsRejectedWithoutEchoingItsContent() {
    // Spec: README.md > Connections and secrets (no JDBC secrets in logs)
    // Given
    fx.writeEnvironment("jdbcUrl: \"jdbc:secret-host\nenvironment: [dev\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.out() + run.err()).doesNotContain("secret-host");
  }

  @Test
  void credentialDefaultsToTheAlias() {
    // Spec: README.md > Environment configuration (credential optional, default the alias)
    // Given: no credential field; the password comes from IDB_CREDENTIAL_CRM
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n"
            + "    jdbcUrl: \""
            + fx.url
            + "\"\n    username: sa\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
  }

  @Test
  void aliasesAndCredentialNamesMustBeUpperCaseInEveryFileThatDeclaresThem() throws Exception {
    // Spec: README.md > Environment configuration (names: upper case, digits, '_')
    // Given/When/Then: environment file
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  crm:\n    engine: postgresql\n"
            + "    jdbcUrl: x\n    username: u\n");
    assertThat(fx.prepare().err()).contains("Invalid database alias 'crm'");
    // case file
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n"
            + "    jdbcUrl: x\n    username: u\n");
    fx.writeCase("version: 1\ndatabases:\n  - alias: crm\n");
    assertThat(fx.capture().err()).contains("Invalid database alias 'crm'");
    // credential field
    fx.writeCase("version: 1\ndatabases:\n  - alias: CRM\n");
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n"
            + "    jdbcUrl: x\n    username: u\n    credential: crm\n");
    assertThat(fx.prepare().err()).contains("Invalid credential name 'crm'");
  }

  @Test
  void duplicateAliasesInTheEnvironmentFileAreRejected() {
    // Spec: README.md > Environment configuration
    // Given
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n"
            + "  CRM:\n    engine: postgresql\n    jdbcUrl: x\n    username: u\n"
            + "  CRM:\n    engine: postgresql\n    jdbcUrl: x\n    username: u\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
  }

  @Test
  void aDatabaseCanBeDefinedOnlyByEnvironmentVariablesWithoutAnEnvironmentFile() throws Exception {
    // Spec: README.md > Environment configuration (IDB_<ALIAS>_ENGINE/URL/USER)
    // Given: no environment file at all
    java.nio.file.Files.delete(fx.configDir.resolve("dev.yml"));
    java.util.Map<String, String> env =
        java.util.Map.of(
            "IDB_CREDENTIAL_CRM",
            Fixture.PASSWORD,
            "IDB_CRM_ENGINE",
            "postgresql",
            "IDB_CRM_URL",
            fx.url,
            "IDB_CRM_USER",
            "sa");
    // When
    CliRun run = CliRun.prepare(env, fx.home, fx.prepareArgs());
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
  }

  @Test
  void environmentVariablesOverrideTheEnvironmentFile() {
    // Spec: README.md > Environment configuration (variables win over the file)
    // Given: the file has a wrong user, the variable the right one
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n"
            + "    jdbcUrl: \""
            + fx.url
            + "\"\n    username: wrong\n");
    java.util.Map<String, String> env =
        java.util.Map.of("IDB_CREDENTIAL_CRM", Fixture.PASSWORD, "IDB_CRM_USER", "sa");
    // When
    CliRun run = CliRun.prepare(env, fx.home, fx.prepareArgs());
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
  }

  @Test
  void aMissingFileWithIncompleteVariablesNamesTheMissingVariable() throws Exception {
    // Spec: README.md > Environment configuration
    // Given
    java.nio.file.Files.delete(fx.configDir.resolve("dev.yml"));
    java.util.Map<String, String> env =
        java.util.Map.of("IDB_CRM_ENGINE", "postgresql", "IDB_CRM_USER", "sa");
    // When
    CliRun run = CliRun.prepare(env, fx.home, fx.prepareArgs());
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("IDB_CRM_URL");
  }
}
