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
    CliRun run =
        CliRun.init(
            NO_ENV,
            home,
            "--case",
            caseFile().toString(),
            "--environment",
            "dev",
            "--database",
            "CRM");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(caseFile()).exists();
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    assertThat(Files.readString(config("dev.yml"))).contains("environment: dev");
    assertThat(Files.readString(config("dev.secrets.json"))).contains("\"CRM\": \"\"");
  }

  @Test
  void initCreatesTheMasterKeyAndKeepsItOnRerun() throws IOException {
    // Spec: README.md > Initialization
    // Given
    CliRun.init(
        NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev", "--database", "CRM");
    byte[] first = Files.readAllBytes(MasterKey.keyPath(home));
    // When
    CliRun.init(
        NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev", "--database", "CRM");
    // Then
    assertThat(Files.readAllBytes(MasterKey.keyPath(home))).isEqualTo(first);
  }

  private CliRun init(String... extra) {
    java.util.List<String> args =
        new java.util.ArrayList<>(
            java.util.List.of("--case", caseFile().toString(), "--environment", "dev"));
    args.addAll(java.util.List.of(extra));
    return CliRun.init(NO_ENV, home, args.toArray(String[]::new));
  }

  @Test
  void initAddsNewDatabasesToExistingFilesAndNeverModifiesExistingOnes() throws IOException {
    // Spec: README.md > Initialization (cumulative, never overwrites)
    // Given: files initialized for CRM and then customised by the user
    init("--database", "CRM");
    Files.writeString(
        config("dev.yml"),
        Files.readString(config("dev.yml")).replace("USERNAME", "custom_user")
            + "# trailing comment\n");
    Files.writeString(project.resolve("testdata/CRM.queries.yml"), "custom: queries\n");
    Files.writeString(config("dev.secrets.json"), "{\"CRM\": \"hunter2\"}");
    String caseBefore = Files.readString(caseFile());
    // When
    CliRun run = init("--database", "CRM", "--database", "postgresql:billing");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(run.out()).contains("Updated: ").contains("Unchanged: ");
    assertThat(Files.readString(caseFile()))
        .startsWith(caseBefore)
        .contains("  - alias: BILLING\n    queries: BILLING.queries.yml\n")
        .contains("dataset: BILLING.dataset.xml")
        .contains("preSql: BILLING.pre.sql")
        .contains("postSql: BILLING.post.sql");
    assertThat(project.resolve("testdata/BILLING.queries.yml")).exists();
    assertThat(project.resolve("testdata/BILLING.pre.sql")).exists().isEmptyFile();
    assertThat(project.resolve("testdata/BILLING.post.sql")).exists().isEmptyFile();
    assertThat(Files.readString(project.resolve("testdata/CRM.queries.yml")))
        .isEqualTo("custom: queries\n");
    String environment = Files.readString(config("dev.yml"));
    assertThat(environment)
        .contains("username: \"custom_user\"")
        .contains("# trailing comment")
        .contains("  BILLING:\n    engine: postgresql\n    jdbcUrl: \"jdbc:postgresql://");
    assertThat(environment.indexOf("  CRM:")).isLessThan(environment.indexOf("  BILLING:"));
    assertThat(Files.readString(config("dev.secrets.json")))
        .contains("\"CRM\" : \"hunter2\"")
        .contains("\"BILLING\"");
  }

  @Test
  void initRunTwiceChangesNothingTheSecondTime() throws IOException {
    // Spec: README.md > Initialization (cumulative, never overwrites)
    // Given
    init("--database", "CRM", "--database", "BILLING");
    String caseText = Files.readString(caseFile());
    String environment = Files.readString(config("dev.yml"));
    String secrets = Files.readString(config("dev.secrets.json"));
    // When
    CliRun run = init("--database", "CRM", "--database", "BILLING");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(run.out()).doesNotContain("Created: ").doesNotContain("Updated: ");
    assertThat(Files.readString(caseFile())).isEqualTo(caseText);
    assertThat(Files.readString(config("dev.yml"))).isEqualTo(environment);
    assertThat(Files.readString(config("dev.secrets.json"))).isEqualTo(secrets);
  }

  @Test
  void initNeverChangesTheEngineOfAnExistingDatabase() throws IOException {
    // Spec: README.md > Initialization (an existing database entry is never modified)
    // Given
    init("--database", "oracle:CRM");
    // When
    CliRun run = init("--database", "postgresql:CRM");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(config("dev.yml")))
        .contains("engine: oracle")
        .doesNotContain("postgresql");
  }

  @Test
  void initRefusesToAddToAnEnvironmentFileOfAnotherEnvironmentAndWritesNothing()
      throws IOException {
    // Spec: README.md > Initialization
    // Given
    Files.createDirectories(config("dev.yml").getParent());
    Files.writeString(
        config("dev.yml"),
        "version: 1\nenvironment: qa\ndatabases:\n  CRM:\n    engine: oracle\n"
            + "    jdbcUrl: \"x\"\n    username: \"u\"\n");
    // When
    CliRun run = init("--database", "BILLING");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("declares environment 'qa'");
    assertThat(caseFile()).doesNotExist();
  }

  @Test
  void initRefusesToExtendAFileWhereDatabasesIsNotTheLastEntryAndWritesNothing()
      throws IOException {
    // Spec: README.md > Initialization
    // Given
    Files.createDirectories(caseFile().getParent());
    Files.writeString(
        caseFile(),
        "version: 1\nname: x\ndatabases:\n  - alias: CRM\n    queries: CRM.queries.yml\n"
            + "    dataset: CRM.dataset.xml\nextra: true\n");
    String before = Files.readString(caseFile());
    // When
    CliRun run = init("--database", "BILLING");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("'databases' must be the last entry").contains("BILLING");
    assertThat(Files.readString(caseFile())).isEqualTo(before);
    assertThat(config("dev.yml")).doesNotExist();
  }

  @Test
  void initConvertsDatabaseNamesToUpperCaseAndAcceptsAnEnginePrefixPerDatabase()
      throws IOException {
    // Spec: README.md > Initialization (--database [engine:]NAME)
    // When
    CliRun run = init("--database", "oracle:crm", "--database", "PostgreSQL:billing");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(config("dev.yml")))
        .contains("  CRM:\n    engine: oracle\n")
        .contains("  BILLING:\n    engine: postgresql\n");
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    assertThat(Files.readString(config("dev.secrets.json")))
        .contains("\"CRM\": \"\"")
        .contains("\"BILLING\": \"\"");
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
            "--database",
            "CRM",
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
    CliRun run =
        CliRun.init(
            NO_ENV,
            home,
            "--case",
            caseFile().toString(),
            "--environment",
            "a/b",
            "--database",
            "CRM");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(caseFile()).doesNotExist();
    assertThat(MasterKey.keyPath(home)).doesNotExist();
  }

  @Test
  void initRequiresTheCaseOption() {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--environment", "dev", "--database", "CRM");
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
            NO_ENV,
            home,
            "--case",
            caseFile().toString(),
            "--environment",
            "dev",
            "--database",
            "CRM",
            "--bogus");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--bogus");
  }

  @Test
  void initCreatesOneSetOfFilesPerDatabaseNextToTheCase() throws IOException {
    // Spec: README.md > Initialization (--database, repeatable)
    // When
    CliRun run =
        CliRun.init(
            NO_ENV,
            home,
            "--case",
            caseFile().toString(),
            "--environment",
            "dev",
            "--database",
            "CRM",
            "--database",
            "BILLING");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(caseFile()))
        .contains("alias: CRM")
        .contains("queries: CRM.queries.yml")
        .contains("dataset: CRM.dataset.xml")
        .contains("alias: BILLING")
        .contains("dataset: BILLING.dataset.xml");
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    assertThat(project.resolve("testdata/BILLING.queries.yml")).exists();
    assertThat(Files.readString(config("dev.yml"))).contains("  CRM:").contains("  BILLING:");
    assertThat(Files.readString(config("dev.secrets.json")))
        .contains("\"CRM\": \"\"")
        .contains("\"BILLING\": \"\"");
  }

  @Test
  void initWritesThePostgresqlEnvironmentTemplateWhenAskedFor() throws IOException {
    // Spec: README.md > Initialization (--engine)
    // When
    CliRun run =
        CliRun.init(
            NO_ENV,
            home,
            "--case",
            caseFile().toString(),
            "--environment",
            "dev",
            "--database",
            "CRM",
            "--engine",
            "postgresql");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(config("dev.yml")))
        .contains("engine: postgresql")
        .contains("jdbc:postgresql://");
  }

  @Test
  void initRequiresAtLeastOneDatabaseAndWritesNothing() {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--case", caseFile().toString(), "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--database");
    assertThat(caseFile()).doesNotExist();
    assertThat(MasterKey.keyPath(home)).doesNotExist();
  }

  @Test
  void initRejectsInvalidOrDuplicateDatabaseNamesAndUnknownEngines() {
    // Spec: README.md > Initialization
    for (String[] bad :
        new String[][] {
          {"--database", "a/b"},
          {"--database", "CRM", "--database", "crm"},
          {"--database", "mysql:CRM"},
          {"--database", "oracle:a:b"},
          {"--database", "1CRM"},
          {"--database", "CRM", "--engine", "mysql"}
        }) {
      // When
      java.util.List<String> args =
          new java.util.ArrayList<>(
              java.util.List.of("--case", caseFile().toString(), "--environment", "dev"));
      args.addAll(java.util.List.of(bad));
      CliRun run = CliRun.init(NO_ENV, home, args.toArray(String[]::new));
      // Then
      assertThat(run.exitCode()).isEqualTo(1);
      assertThat(caseFile()).doesNotExist();
    }
  }
}
