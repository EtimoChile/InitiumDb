package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Contract: README.md > "Captured dataset" (values of each type come back unchanged). The engine
 * containers cover every type per engine; this class keeps the time-zone rules in the default
 * build.
 */
class DatasetTypesTest {

  /** Clocks in America/Santiago jumped from 2024-09-08 00:00 to 01:00. */
  private static final String SANTIAGO = "America/Santiago";

  private static final String QUERIES =
      "tables:\n  - name: EVENT\n    sql: SELECT * FROM EVENT ORDER BY ID\n";

  @TempDir Path tmp;
  Fixture fx;
  TimeZone previous;

  @BeforeEach
  void setUp() {
    previous = TimeZone.getDefault();
    fx = new Fixture(tmp);
    fx.sql("CREATE TABLE EVENT (ID INT PRIMARY KEY, AT TIMESTAMP(6))");
    fx.write("CRM.queries.yml", QUERIES);
  }

  @AfterEach
  void restoreZone() {
    TimeZone.setDefault(previous);
  }

  @Test
  void aTimestampThatDoesNotExistInTheLocalTimeZoneIsNotShifted() {
    // Spec: README.md > Captured dataset (a timestamp without time zone is kept as written)
    // Given: the machine runs in a zone where 00:30 that day does not exist
    TimeZone.setDefault(TimeZone.getTimeZone(SANTIAGO));
    fx.sql("INSERT INTO EVENT VALUES (1, TIMESTAMP '2024-09-08 00:30:00')");
    assertThat(fx.capture().exitCode()).isZero();
    fx.sql("DELETE FROM EVENT");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.read("CRM.dataset.xml")).contains("AT=\"2024-09-08 00:30:00.0\"");
    assertThat(fx.value("SELECT CAST(AT AS VARCHAR) FROM EVENT WHERE ID = 1"))
        .isEqualTo("2024-09-08 00:30:00");
  }

  @Test
  void timestampsAreWrittenInTheFormatOfEarlierDatasets() {
    // Spec: README.md > Captured dataset (datasets written by earlier versions stay valid)
    // Given
    fx.sql(
        "INSERT INTO EVENT VALUES (1, TIMESTAMP '2024-03-15 13:45:07.123456')",
        "INSERT INTO EVENT VALUES (2, TIMESTAMP '2024-03-15 13:45:07.5')");
    // When
    CliRun run = fx.capture();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.read("CRM.dataset.xml"))
        .contains("AT=\"2024-03-15 13:45:07.123456\"")
        .contains("AT=\"2024-03-15 13:45:07.5\"");
  }

  @Test
  void handWrittenTimestampsInOtherFormsStillLoad() {
    // Spec: README.md > Captured dataset (datasets written by hand still load)
    // Given: no fraction, an ISO 'T', and a date alone
    fx.write(
        "CRM.dataset.xml",
        "<dataset><EVENT ID=\"1\" AT=\"2024-03-15 13:45:07\"/><EVENT ID=\"2\""
            + " AT=\"2024-03-15T13:45\"/><EVENT ID=\"3\" AT=\"2024-03-15\"/></dataset>");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).as(run.err()).isZero();
    assertThat(fx.value("SELECT CAST(AT AS VARCHAR) FROM EVENT WHERE ID = 1"))
        .isEqualTo("2024-03-15 13:45:07");
    assertThat(fx.value("SELECT CAST(AT AS VARCHAR) FROM EVENT WHERE ID = 2"))
        .isEqualTo("2024-03-15 13:45:00");
    assertThat(fx.value("SELECT CAST(AT AS VARCHAR) FROM EVENT WHERE ID = 3"))
        .isEqualTo("2024-03-15 00:00:00");
  }

  @Test
  void aTimestampThatIsNotADateFailsThePrepare() {
    // Spec: README.md > idb-prepare (exit successfully only if every alias completes)
    // Given
    fx.sql("INSERT INTO EVENT VALUES (1, TIMESTAMP '2024-01-01 00:00:00')");
    fx.write("CRM.dataset.xml", "<dataset><EVENT ID=\"1\" AT=\"yesterday\"/></dataset>");
    // When
    CliRun run = fx.prepare();
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(fx.value("SELECT CAST(AT AS VARCHAR) FROM EVENT WHERE ID = 1"))
        .isEqualTo("2024-01-01 00:00:00");
  }
}
