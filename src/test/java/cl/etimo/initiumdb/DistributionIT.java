package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract: README.md > "Installation". Runs the launchers of the generated distribution folder
 * (run by Failsafe after {@code package}; the folder is passed as {@code idb.dist}).
 */
class DistributionIT {

  private static final List<String> COMMANDS =
      List.of("idb-capture", "idb-prepare", "idb-init", "idb-crypt");

  private static final boolean WINDOWS =
      System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");

  @TempDir Path tmp;

  private static Path dist() {
    String dist = System.getProperty("idb.dist");
    assertThat(dist).as("system property idb.dist").isNotNull();
    return Path.of(dist);
  }

  private record Result(int exitCode, String output) {}

  private Result launch(String command, String... args) throws Exception {
    Path bin = dist().resolve("bin");
    List<String> line = new ArrayList<>();
    if (WINDOWS) {
      line.addAll(List.of("cmd", "/c", bin.resolve(command + ".cmd").toString()));
    } else {
      line.addAll(List.of("sh", bin.resolve(command).toString()));
    }
    line.addAll(List.of(args));
    ProcessBuilder builder = new ProcessBuilder(line).redirectErrorStream(true);
    builder.environment().put("JAVA_HOME", System.getProperty("java.home"));
    builder.environment().put("IDB_MASTER_KEY", "ab".repeat(32));
    builder.environment().put("USERPROFILE", tmp.toString());
    builder.environment().put("HOME", tmp.toString());
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("launcher finished").isTrue();
    return new Result(process.exitValue(), output);
  }

  @Test
  void theDistributionHasALauncherPerCommandAndNoBundledDrivers() throws IOException {
    // Spec: README.md > Installation
    Path bin = dist().resolve("bin");
    for (String command : COMMANDS) {
      assertThat(bin.resolve(command)).isRegularFile();
      assertThat(bin.resolve(command + ".cmd")).isRegularFile();
    }
    try (Stream<Path> lib = Files.list(dist().resolve("lib"))) {
      List<String> jars = lib.map(p -> p.getFileName().toString()).toList();
      assertThat(jars)
          .anyMatch(n -> n.startsWith("initiumdb-"))
          .anyMatch(n -> n.startsWith("dbunit-"))
          .noneMatch(n -> n.startsWith("postgresql-"))
          .noneMatch(n -> n.startsWith("ojdbc"));
    }
    assertThat(dist().resolve("drivers")).isDirectory();
  }

  @Test
  void versionPrintsThePackagedVersionThroughTheLauncher() throws Exception {
    // Spec: README.md > Installation (--version)
    // When
    Result result = launch("idb-prepare", "--version");
    // Then
    assertThat(result.exitCode()).isZero();
    assertThat(result.output().trim()).matches("idb-prepare \\d+\\.\\d+\\.\\d+(-SNAPSHOT)?");
  }

  @Test
  void idbInitCreatesTheProjectFilesThroughTheLauncher() throws Exception {
    // Spec: README.md > Installation; Initialization
    // When
    Result result =
        launch(
            "idb-init",
            "--case",
            tmp.resolve("project").toString(),
            "--environment",
            "dev",
            "--database",
            "CRM",
            "--config-dir",
            tmp.resolve("cfg").toString());
    // Then
    assertThat(result.exitCode()).as(result.output()).isZero();
    assertThat(tmp.resolve("project/case.yml")).isRegularFile();
    assertThat(tmp.resolve("project/CRM.queries.yml")).isRegularFile();
    assertThat(tmp.resolve("cfg/dev.yml")).isRegularFile();
  }

  @Test
  void everyLauncherFailsWithItsUsageWhenGivenNoArguments() throws Exception {
    // Spec: README.md > Installation (exit code 1 on failure)
    for (String command : COMMANDS) {
      // When
      Result result = launch(command);
      // Then
      assertThat(result.exitCode()).as(command).isEqualTo(1);
      assertThat(result.output()).as(command).contains("Usage: " + command);
    }
  }
}
