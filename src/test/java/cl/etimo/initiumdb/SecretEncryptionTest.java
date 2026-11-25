package cl.etimo.initiumdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cl.etimo.initiumdb.core.InitiumDbException;
import cl.etimo.initiumdb.core.MasterKey;
import cl.etimo.initiumdb.core.SecretCipher;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Contract: README.md > "Local secrets" (idb-crypt). */
class SecretEncryptionTest {

  private static final Map<String, String> NO_ENV = Map.of();
  private static final String HEX_KEY =
      "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

  @TempDir Path home;

  private Path secrets() {
    return home.resolve(".initiumdb").resolve("dev.secrets.json");
  }

  private void writeSecrets(String json) throws IOException {
    Files.createDirectories(secrets().getParent());
    Files.writeString(secrets(), json, StandardCharsets.UTF_8);
  }

  @Test
  void cryptEncryptsCleartextSecretsAndLeavesNoCleartextInFileOrOutput() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"}");
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(secrets())).doesNotContain("hunter2").contains(SecretCipher.PREFIX);
    assertThat(run.out() + run.err()).doesNotContain("hunter2");
  }

  @Test
  void encryptedSecretsDecryptWithTheMasterKey() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"}");
    CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // When
    byte[] key = MasterKey.loadOrCreate(NO_ENV, home);
    String token = Files.readString(secrets()).split("\"CRM\"\\s*:\\s*\"")[1].split("\"")[0];
    // Then
    assertThat(SecretCipher.decrypt(token, key)).isEqualTo("hunter2");
  }

  @Test
  void cryptDoesNotReencryptAlreadyEncryptedSecrets() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"}");
    CliRun.crypt(NO_ENV, home, "--environment", "dev");
    String afterFirst = Files.readString(secrets());
    // When
    CliRun second = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(second.exitCode()).isZero();
    assertThat(Files.readString(secrets())).isEqualTo(afterFirst);
  }

  @Test
  void cryptLeavesEmptySecretsUntouched() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": \"\"}");
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(Files.readString(secrets())).isEqualTo("{\"CRM\": \"\"}");
  }

  @Test
  void cryptUsesMasterKeyFromEnvironmentVariableWithoutCreatingKeyFile() throws IOException {
    // Spec: README.md > Local secrets (key resolution)
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"}");
    // When
    CliRun run = CliRun.crypt(Map.of(MasterKey.ENV_VAR, HEX_KEY), home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isZero();
    assertThat(MasterKey.keyPath(home)).doesNotExist();
    String token = Files.readString(secrets()).split("\"CRM\"\\s*:\\s*\"")[1].split("\"")[0];
    byte[] key = java.util.HexFormat.of().parseHex(HEX_KEY);
    assertThat(SecretCipher.decrypt(token, key)).isEqualTo("hunter2");
  }

  @Test
  void cryptFailsOnInvalidMasterKeyWithoutEchoingIt() throws IOException {
    // Spec: README.md > Local secrets (key resolution)
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"}");
    // When
    CliRun run =
        CliRun.crypt(Map.of(MasterKey.ENV_VAR, "too-short-key"), home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).doesNotContain("too-short-key");
    assertThat(Files.readString(secrets())).contains("hunter2");
  }

  @Test
  void cryptFailsWhenSecretsFileIsMissing() {
    // Spec: README.md > Local secrets
    // Given: no secrets file
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Secrets file not found");
  }

  @Test
  void cryptFailsOnMalformedSecretsFileWithoutLeakingItsContent() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"");
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.out() + run.err()).doesNotContain("hunter2");
  }

  @Test
  void cryptFailsWhenASecretIsNotAString() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": 12345}");
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("'CRM'");
  }

  @Test
  void cryptRejectsCredentialNamesThatAreNotUpperCaseAndLeavesTheFileUntouched()
      throws IOException {
    // Spec: README.md > Local secrets (names: upper case, digits, '_')
    // Given
    writeSecrets("{\"crm\": \"hunter2\"}");
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Invalid credential name 'crm'");
    assertThat(Files.readString(secrets())).isEqualTo("{\"crm\": \"hunter2\"}");
  }

  @Test
  void cryptRejectsEnvironmentNamesThatEscapeTheConfigurationDirectory() {
    // Spec: README.md > Local secrets
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--environment", "../dev");
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("Invalid environment name");
  }

  @Test
  void cryptRequiresTheEnvironmentOption() {
    // Spec: README.md > Local secrets
    // When
    CliRun run = CliRun.crypt(NO_ENV, home);
    // Then
    assertThat(run.exitCode()).isEqualTo(1);
    assertThat(run.err()).contains("--environment").contains("Usage:");
  }

  @Test
  void cryptAcceptsTheCaseOptionSoPrepareCommandLinesCanBeReused() throws IOException {
    // Spec: README.md > Local secrets
    // Given
    writeSecrets("{\"CRM\": \"hunter2\"}");
    // When
    CliRun run = CliRun.crypt(NO_ENV, home, "--case", "any/case.yml", "--environment", "dev");
    // Then
    assertThat(run.exitCode()).isZero();
  }

  @Test
  void decryptingWithAnotherKeyFails() {
    // Spec: README.md > Local secrets
    // Given
    byte[] key = new byte[32];
    byte[] other = new byte[32];
    other[0] = 1;
    String token = SecretCipher.encrypt("hunter2", key);
    // Then
    assertThatThrownBy(() -> SecretCipher.decrypt(token, other))
        .isInstanceOf(InitiumDbException.class)
        .hasMessageNotContaining("hunter2");
  }
}
