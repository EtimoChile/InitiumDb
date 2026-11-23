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
    fx.write("db/CRM/dataset.xml", "<dataset/>");
  }

  @Test
  void aCaseWithoutVersionIsRejected() {
    // Spec: README.md > Case configuration
    // Given
    fx.writeCase("name: t\ndatabases:\n  - alias: CRM\n    dataset: db/CRM/dataset.xml\n");
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
    fx.writeCase("version: 1\nname: t\ndatabases: []\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("databases");
  }

  @Test
  void aStepWithoutDatasetIsRejected() {
    // Spec: README.md > Case configuration
    // Given
    fx.writeCase("version: 1\nname: t\ndatabases:\n  - alias: CRM\n");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("dataset");
  }

  @Test
  void aMissingCaseFileIsRejected() throws Exception {
    // Spec: README.md > Case configuration
    // Given
    java.nio.file.Files.delete(fx.casePath());
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
            + "    jdbcUrl: \"x\"\n    username: u\n    credential: c\n");
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
            + "    jdbcUrl: \"x\"\n    username: u\n    credential: c\n");
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
}
