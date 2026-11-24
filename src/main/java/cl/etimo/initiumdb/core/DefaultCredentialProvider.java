package cl.etimo.initiumdb.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Looks a password up first in the {@code IDB_CREDENTIAL_<NAME>} environment variable (name as
 * written), then in the local secrets file. Cleartext values found there are encrypted in place.
 */
public final class DefaultCredentialProvider implements CredentialProvider {

  private final Map<String, String> env;
  private final Path secretsFile;
  private final Supplier<byte[]> key;
  private final Supplier<byte[]> newKey;
  private final Consumer<String> notice;

  public DefaultCredentialProvider(
      Map<String, String> env,
      Path secretsFile,
      Supplier<byte[]> key,
      Supplier<byte[]> newKey,
      Consumer<String> notice) {
    this.env = env;
    this.secretsFile = secretsFile;
    this.key = key;
    this.newKey = newKey;
    this.notice = notice;
  }

  /** Name of the environment variable that overrides the secrets file for a credential. */
  public static String variableName(String credential) {
    return "IDB_CREDENTIAL_" + credential;
  }

  @Override
  public String password(String credential) {
    String fromEnv = env.get(variableName(credential));
    if (fromEnv != null && !fromEnv.isEmpty()) {
      return fromEnv;
    }
    return SecretsFile.resolve(secretsFile, credential, key, newKey, notice);
  }
}
