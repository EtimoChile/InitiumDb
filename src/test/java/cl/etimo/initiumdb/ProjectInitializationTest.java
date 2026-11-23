package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.core.MasterKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "Initialization" (idb-init). */
class ProjectInitializationTest {

  private static final Map<String, String> NO_ENV = Map.of();

  @TempDir Path home;
  @TempDir Path project;

  private Path caseFile() {
    return project.resolve("testdata/case.yml");
  }

  private Path config(String name) {
    return home.resolve(".initiumdb").resolve(name);
  }

  @Test
  void initCreatesCaseQueriesEnvironmentAndEmptySecretsTemplates() throws IOException {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(caseFile()).exists();
    assertThat(project.resolve("testdata/db/CRM/queries.yml")).exists();
    assertThat(Files.readString(config("dev.yml"))).contains("environment: dev");
    assertThat(Files.readString(config("dev.secrets.json"))).contains("\"crm\": \"\"");
  }

  @Test
  void initCreatesTheMasterKeyAndKeepsItOnRerun() throws IOException {
    // Spec: README.md > Initialization
    // Given
    CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev");
    byte[] first = Files.readAllBytes(MasterKey.keyPath(home));
    // When
    CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev");
    // Then
    assertThat(Files.readAllBytes(MasterKey.keyPath(home))).isEqualTo(first);
  }

  @Test
  void initDoesNotOverwriteExistingFilesWithoutTheOverwriteOption() throws IOException {
    // Spec: README.md > Initialization
    // Given
    Files.createDirectories(caseFile().getParent());
    Files.writeString(caseFile(), "custom: true\n");
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(caseFile())).isEqualTo("custom: true\n");
    assertThat(run.out()).contains("Skipped (exists): " + caseFile().toAbsolutePath());
  }

  @Test
  void initWithOverwriteReplacesTemplatesButNeverTheSecretsFile() throws IOException {
    // Spec: README.md > Initialization
    // Given
    CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev");
    Files.writeString(caseFile(), "custom: true\n");
    Files.writeString(config("dev.secrets.json"), "{\"crm\": \"hunter2\"}");
    // When
    CliRun run =
        CliRun.init(
            NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev", "--overwrite");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(caseFile())).contains("version: 1");
    assertThat(Files.readString(config("dev.secrets.json"))).isEqualTo("{\"crm\": \"hunter2\"}");
  }

  @Test
  void initHonorsTheConfigDirOption() {
    // Spec: README.md > Initialization
    // Given
    Path custom = project.resolve("cfg");
    // When
    CliRun run =
        CliRun.init(
            NO_ENV,
            home,
            "--case",
            caseFile().toString(),
            "--environment",
            "qa",
            "--config-dir",
            custom.toString());
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(custom.resolve("qa.yml")).exists();
    assertThat(custom.resolve("qa.secrets.json")).exists();
  }

  @Test
  void initRejectsInvalidEnvironmentNamesAndWritesNothing() {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "a/b");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(caseFile()).doesNotExist();
    assertThat(MasterKey.keyPath(home)).doesNotExist();
  }

  @Test
  void initRequiresTheCaseOption() {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--case").contains("Usage:");
  }

  @Test
  void initRejectsUnknownArguments() {
    // Spec: README.md > Initialization
    // When
    CliRun run =
        CliRun.init(
            NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev", "--bogus");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--bogus");
  }
}
