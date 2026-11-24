package cl.etimo.initiumdb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

/**
 * Test helper: a project directory, an environment file and a disposable in-memory H2 database
 * standing in for an engine (declared as {@code postgresql}). Each instance owns a uniquely named
 * database that only the test creates.
 */
final class Fixture {

  static final String PASSWORD = "secret";

  final Path home;
  final Path project;
  final Path configDir;
  final String url;
  final Map<String, String> env = Map.of("IDB_CREDENTIAL_CRM", PASSWORD);

  Fixture(Path root) {
    home = root.resolve("home");
    project = root.resolve("project");
    configDir = root.resolve("cfg");
    url = "jdbc:h2:mem:idb" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
    sql(
        "CREATE TABLE CUSTOMER (CUSTOMER_ID INT PRIMARY KEY, NAME VARCHAR(50), STATUS VARCHAR(10))",
        "CREATE TABLE ACCOUNT (ACCOUNT_ID INT PRIMARY KEY, CUSTOMER_ID INT NOT NULL,"
            + " BALANCE DECIMAL(14,2), FOREIGN KEY (CUSTOMER_ID) REFERENCES CUSTOMER (CUSTOMER_ID))");
    writeEnvironment(
        "version: 1\nenvironment: dev\ndatabases:\n  CRM:\n    engine: postgresql\n    jdbcUrl: \""
            + url
            + "\"\n    username: \"sa\"\n");
    writeCase("version: 1\ndatabases:\n  - alias: CRM\n");
    write(
        "CRM.queries.yml",
        "tables:\n  - name: CUSTOMER\n    sql: SELECT * FROM CUSTOMER WHERE CUSTOMER_ID IN (1001, 1002)"
            + " ORDER BY CUSTOMER_ID\n  - name: ACCOUNT\n    sql: SELECT * FROM ACCOUNT ORDER BY ACCOUNT_ID\n");
  }

  Connection db() throws SQLException {
    return DriverManager.getConnection(url, "sa", PASSWORD);
  }

  void sql(String... statements) {
    try (Connection c = db();
        Statement s = c.createStatement()) {
      for (String statement : statements) {
        s.execute(statement);
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Returns the first column of the first row, or {@code null} when there is no row. */
  String value(String query) {
    try (Connection c = db();
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(query)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The case is a directory; its file is always {@code case.yml}. */
  Path casePath() {
    return project;
  }

  Path caseFile() {
    return project.resolve("case.yml");
  }

  Path path(String relative) {
    return project.resolve(relative);
  }

  void write(String relative, String content) {
    writeFile(project.resolve(relative), content);
  }

  void writeCase(String yaml) {
    writeFile(caseFile(), yaml);
  }

  void writeEnvironment(String yaml) {
    writeFile(configDir.resolve("dev.yml"), yaml);
  }

  String read(String relative) {
    try {
      return Files.readString(project.resolve(relative), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Arguments common to {@code idb-capture} and {@code idb-prepare}. */
  String[] args() {
    return new String[] {
      "--case", casePath().toString(), "--environment", "dev", "--config-dir", configDir.toString()
    };
  }

  CliRun capture() {
    return CliRun.capture(env, home, args());
  }

  CliRun prepare() {
    return CliRun.prepare(env, home, args());
  }

  private static void writeFile(Path file, String content) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, content, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
