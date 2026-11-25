package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "idb-prepare" (the dataset is read as a stream, never held in memory). */
class PrepareMemoryTest {

  private static final int ROWS = 60_000;
  private static final int PAYLOAD = 400;

  @TempDir Path tmp;

  @Test
  void aDatasetFarLargerThanTheHeapIsAppliedWithoutLoadingItInMemory() throws Exception {
    // Spec: README.md > idb-prepare (streaming)
    // Given: a table of ~25 MB in Flat XML (> 100 MB as objects) and a JVM limited to 40 MB
    Path database = tmp.resolve("db");
    String url = "jdbc:h2:file:" + database.toAbsolutePath().toString().replace('\\', '/');
    try (Connection c = DriverManager.getConnection(url, "sa", Fixture.PASSWORD);
        Statement s = c.createStatement()) {
      s.execute("CREATE TABLE BIG (ID INT PRIMARY KEY, PAYLOAD VARCHAR(500))");
    }
    Fixture fx = new Fixture(tmp);
    fx.writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n    jdbcUrl: \""
            + url
            + "\"\n    username: \"sa\"\n");
    String payload = "x".repeat(PAYLOAD);
    try (Writer w = Files.newBufferedWriter(fx.path("CRM.dataset.xml"), StandardCharsets.UTF_8)) {
      w.write("<dataset>\n");
      for (int i = 1; i <= ROWS; i++) {
        w.write("<BIG ID=\"" + i + "\" PAYLOAD=\"" + payload + "\"/>\n");
      }
      w.write("</dataset>\n");
    }
    // When
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    command.add("-Xmx40m");
    command.addAll(List.of("-cp", System.getProperty("java.class.path")));
    command.add("cl.etimo.initiumdb.cli.IdbPrepare");
    command.addAll(List.of(fx.prepareArgs()));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().putAll(fx.env);
    builder.environment().put("USERPROFILE", tmp.toString());
    builder.environment().put("HOME", tmp.toString());
    Process process = builder.start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("finished").isTrue();
    // Then
    assertThat(process.exitValue()).as(output).isZero();
    assertThat(count(url)).isEqualTo(ROWS);
  }

  private static long count(String url) throws Exception {
    try (Connection c = DriverManager.getConnection(url, "sa", Fixture.PASSWORD);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM BIG")) {
      rs.next();
      return rs.getLong(1);
    }
  }
}
