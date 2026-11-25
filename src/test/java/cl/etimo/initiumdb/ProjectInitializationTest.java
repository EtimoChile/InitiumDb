package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import cl.etimo.initiumdb.core.MasterKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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

  private Path caseDir() {
    return project.resolve("testdata");
  }

  private Path caseFile() {
    return caseDir().resolve("case.yml");
  }

  private static JsonNode yaml(Path file) throws IOException {
    return new ObjectMapper(new YAMLFactory()).readTree(Files.readString(file));
  }

  private static JsonNode json(Path file) throws IOException {
    return new ObjectMapper().readTree(Files.readString(file));
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
            NO_ENV, home, "--case", caseDir().toString(), "--environment", "dev", "--alias", "CRM");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(caseFile()).exists();
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    assertThat(yaml(config("dev.yml")).get("environment").asText()).isEqualTo("dev");
    assertThat(json(config("dev.secrets.json")).get("CRM").asText()).isEmpty();
  }

  @Test
  void initCreatesTheMasterKeyAndKeepsItOnRerun() throws IOException {
    // Spec: README.md > Initialization
    // Given
    CliRun.init(
        NO_ENV, home, "--case", caseDir().toString(), "--environment", "dev", "--alias", "CRM");
    byte[] first = Files.readAllBytes(MasterKey.keyPath(home));
    // When
    CliRun.init(
        NO_ENV, home, "--case", caseDir().toString(), "--environment", "dev", "--alias", "CRM");
    // Then
    assertThat(Files.readAllBytes(MasterKey.keyPath(home))).isEqualTo(first);
  }

  private CliRun init(String... extra) {
    java.util.List<String> args =
        new java.util.ArrayList<>(
            java.util.List.of("--case", caseDir().toString(), "--environment", "dev"));
    args.addAll(java.util.List.of(extra));
    return CliRun.init(NO_ENV, home, args.toArray(String[]::new));
  }

  @Test
  void initAddsNewDatabasesToExistingFilesAndNeverModifiesExistingOnes() throws IOException {
    // Spec: README.md > Initialization (cumulative, never overwrites)
    // Given: files initialized for CRM and then customised by the user
    init("--alias", "CRM");
    Files.writeString(
        config("dev.yml"), Files.readString(config("dev.yml")).replace("USERNAME", "custom_user"));
    Files.writeString(project.resolve("testdata/CRM.queries.yml"), "custom: queries\n");
    Files.writeString(config("dev.secrets.json"), "{\"CRM\": \"hunter2\"}");
    // When
    CliRun run = init("--alias", "CRM", "--alias", "postgresql:billing");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(run.out()).contains("Updated: ").contains("Unchanged: ");
    JsonNode steps = yaml(caseFile()).get("databases");
    assertThat(steps).hasSize(2);
    assertThat(steps.get(0).get("alias").asText()).isEqualTo("CRM");
    JsonNode added = steps.get(1);
    assertThat(added.get("alias").asText()).isEqualTo("BILLING");
    assertThat(added.size()).isEqualTo(1);
    assertThat(project.resolve("testdata/BILLING.queries.yml")).exists();
    assertThat(project.resolve("testdata/BILLING.pre.sql")).exists().isEmptyFile();
    assertThat(project.resolve("testdata/BILLING.post.sql")).exists().isEmptyFile();
    assertThat(Files.readString(project.resolve("testdata/CRM.queries.yml")))
        .isEqualTo("custom: queries\n");
    JsonNode databases = yaml(config("dev.yml")).get("databases");
    assertThat(databases.get("CRM").get("username").asText()).isEqualTo("custom_user");
    assertThat(databases.get("BILLING").get("engine").asText()).isEqualTo("postgresql");
    assertThat(databases.get("BILLING").get("jdbcUrl").asText()).startsWith("jdbc:postgresql://");
    JsonNode secrets = json(config("dev.secrets.json"));
    assertThat(secrets.get("CRM").asText()).isEqualTo("hunter2");
    assertThat(secrets.get("BILLING").asText()).isEmpty();
  }

  @Test
  void initRunTwiceChangesNothingTheSecondTime() throws IOException {
    // Spec: README.md > Initialization (cumulative, never overwrites)
    // Given
    init("--alias", "CRM", "--alias", "BILLING");
    String caseText = Files.readString(caseFile());
    String environment = Files.readString(config("dev.yml"));
    String secrets = Files.readString(config("dev.secrets.json"));
    // When
    CliRun run = init("--alias", "CRM", "--alias", "BILLING");
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
    init("--alias", "oracle:CRM");
    // When
    CliRun run = init("--alias", "postgresql:CRM");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(yaml(config("dev.yml")).get("databases").get("CRM").get("engine").asText())
        .isEqualTo("oracle");
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
    CliRun run = init("--alias", "BILLING");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("declares environment 'qa'");
    assertThat(caseFile()).doesNotExist();
  }

  @Test
  void initCompletesTheMissingFilesOfADatabaseAlreadyInTheCaseWithoutTouchingTheOthers()
      throws IOException {
    // Spec: README.md > Initialization (file names are fixed by convention)
    // Given: CRM is in the case with a customised post hook; its other files were deleted
    init("--alias", "CRM");
    Files.writeString(project.resolve("testdata/CRM.post.sql"), "DELETE FROM T;\n");
    Files.delete(project.resolve("testdata/CRM.queries.yml"));
    Files.delete(project.resolve("testdata/CRM.pre.sql"));
    String caseText = Files.readString(caseFile());
    // When
    CliRun run = init("--alias", "CRM");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    assertThat(project.resolve("testdata/CRM.pre.sql")).exists().isEmptyFile();
    assertThat(Files.readString(project.resolve("testdata/CRM.post.sql")))
        .isEqualTo("DELETE FROM T;\n");
    assertThat(Files.readString(caseFile())).isEqualTo(caseText);
  }

  @Test
  void initExtendsAnExistingCaseWhateverTheOrderOfItsEntries() throws IOException {
    // Spec: README.md > Initialization (YAML is processed with the YAML library, not as text)
    // Given: a flow-style case with another key after databases
    Files.createDirectories(caseFile().getParent());
    Files.writeString(caseFile(), "version: 1\ndatabases: [{alias: CRM}]\n" + "name: x\n");
    // When
    CliRun run = init("--alias", "BILLING");
    // Then
    assertThat(run.exitCode()).isZero();
    JsonNode root = yaml(caseFile());
    assertThat(root.get("name").asText()).isEqualTo("x");
    assertThat(root.get("databases")).hasSize(2);
    assertThat(root.get("databases").get(1).get("alias").asText()).isEqualTo("BILLING");
  }

  @Test
  void initRefusesAnExistingCaseWhoseDatabasesAreNotAListAndWritesNothing() throws IOException {
    // Spec: README.md > Initialization
    // Given
    Files.createDirectories(caseFile().getParent());
    Files.writeString(caseFile(), "version: 1\nname: x\ndatabases: none\n");
    String before = Files.readString(caseFile());
    // When
    CliRun run = init("--alias", "BILLING");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("'databases'").contains("must be a list");
    assertThat(Files.readString(caseFile())).isEqualTo(before);
    assertThat(config("dev.yml")).doesNotExist();
  }

  @Test
  void initConvertsDatabaseNamesToUpperCaseAndAcceptsAnEnginePrefixPerDatabase()
      throws IOException {
    // Spec: README.md > Initialization (--alias [engine:]NAME)
    // When
    CliRun run = init("--alias", "oracle:crm", "--alias", "PostgreSQL:billing");
    // Then
    assertThat(run.exitCode()).isZero();
    JsonNode databases = yaml(config("dev.yml")).get("databases");
    assertThat(databases.get("CRM").get("engine").asText()).isEqualTo("oracle");
    assertThat(databases.get("BILLING").get("engine").asText()).isEqualTo("postgresql");
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    JsonNode secrets = json(config("dev.secrets.json"));
    assertThat(secrets.has("CRM")).isTrue();
    assertThat(secrets.has("BILLING")).isTrue();
  }

  @Test
  void helpPrintsTheUsageToStandardOutputAndWritesNothing() {
    // Spec: README.md > Initialization (--help)
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--help");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(run.err()).isEmpty();
    assertThat(run.out())
        .contains("Usage: idb-init")
        .contains("--alias [ENGINE:]NAME")
        .contains("oracle:CRM")
        .contains("never overwritten");
    assertThat(MasterKey.keyPath(home)).doesNotExist();
  }

  @Test
  void everyCommandAcceptsHelp() {
    // Spec: README.md > Commands (--help)
    for (CliRun run :
        new CliRun[] {
          CliRun.crypt(NO_ENV, home, "-h"),
          CliRun.capture(NO_ENV, home, "--help"),
          CliRun.prepare(NO_ENV, home, "--help")
        }) {
      assertThat(run.exitCode()).isZero();
      assertThat(run.out()).startsWith("Usage: idb-");
      assertThat(run.err()).isEmpty();
    }
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
            caseDir().toString(),
            "--environment",
            "qa",
            "--alias",
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
            NO_ENV, home, "--case", caseDir().toString(), "--environment", "a/b", "--alias", "CRM");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(caseFile()).doesNotExist();
    assertThat(MasterKey.keyPath(home)).doesNotExist();
  }

  @Test
  void initRequiresTheCaseOption() {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--environment", "dev", "--alias", "CRM");
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
            caseDir().toString(),
            "--environment",
            "dev",
            "--alias",
            "CRM",
            "--bogus");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--bogus");
  }

  @Test
  void initCreatesOneSetOfFilesPerDatabaseNextToTheCase() throws IOException {
    // Spec: README.md > Initialization (--alias, repeatable)
    // When
    CliRun run =
        CliRun.init(
            NO_ENV,
            home,
            "--case",
            caseDir().toString(),
            "--environment",
            "dev",
            "--alias",
            "CRM",
            "--alias",
            "BILLING");
    // Then
    assertThat(run.exitCode()).isZero();
    JsonNode steps = yaml(caseFile()).get("databases");
    assertThat(steps.get(0).get("alias").asText()).isEqualTo("CRM");
    assertThat(steps.get(1).get("alias").asText()).isEqualTo("BILLING");
    assertThat(project.resolve("testdata/CRM.queries.yml")).exists();
    assertThat(project.resolve("testdata/BILLING.queries.yml")).exists();
    assertThat(yaml(config("dev.yml")).get("databases").has("CRM")).isTrue();
    assertThat(yaml(config("dev.yml")).get("databases").has("BILLING")).isTrue();
    assertThat(json(config("dev.secrets.json")).get("CRM").asText()).isEmpty();
    assertThat(json(config("dev.secrets.json")).get("BILLING").asText()).isEmpty();
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
            caseDir().toString(),
            "--environment",
            "dev",
            "--alias",
            "CRM",
            "--engine",
            "postgresql");
    // Then
    assertThat(run.exitCode()).isZero();
    JsonNode crm = yaml(config("dev.yml")).get("databases").get("CRM");
    assertThat(crm.get("engine").asText()).isEqualTo("postgresql");
    assertThat(crm.get("jdbcUrl").asText()).startsWith("jdbc:postgresql://");
  }

  @Test
  void initRequiresAtLeastOneDatabaseAndWritesNothing() {
    // Spec: README.md > Initialization
    // When
    CliRun run = CliRun.init(NO_ENV, home, "--case", caseDir().toString(), "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--alias");
    assertThat(caseFile()).doesNotExist();
    assertThat(MasterKey.keyPath(home)).doesNotExist();
  }

  @Test
  void initRejectsInvalidOrDuplicateDatabaseNamesAndUnknownEngines() {
    // Spec: README.md > Initialization
    for (String[] bad :
        new String[][] {
          {"--alias", "a/b"},
          {"--alias", "CRM", "--alias", "crm"},
          {"--alias", "mysql:CRM"},
          {"--alias", "oracle:a:b"},
          {"--alias", "1CRM"},
          {"--alias", "CRM", "--engine", "mysql"}
        }) {
      // When
      java.util.List<String> args =
          new java.util.ArrayList<>(
              java.util.List.of("--case", caseDir().toString(), "--environment", "dev"));
      args.addAll(java.util.List.of(bad));
      CliRun run = CliRun.init(NO_ENV, home, args.toArray(String[]::new));
      // Then
      assertThat(run.exitCode()).isEqualTo(1);
      assertThat(caseFile()).doesNotExist();
    }
  }
}
