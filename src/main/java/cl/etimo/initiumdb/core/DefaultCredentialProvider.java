package cl.etimo.initiumdb.core;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Looks a password up first in the {@code IDB_CREDENTIAL_<NAME>} environment variable (name
 * upper-cased, non-alphanumerics replaced by '_'), then in the local secrets file.
 */
public final class DefaultCredentialProvider implements CredentialProvider {

  private final Map<String, String> env;
  private final Path secretsFile;
  private final Supplier<byte[]> key;

  public DefaultCredentialProvider(
      Map<String, String> env, Path secretsFile, Supplier<byte[]> key) {
    this.env = env;
    this.secretsFile = secretsFile;
    this.key = key;
  }

  /** Name of the environment variable that overrides the secrets file for a credential. */
  public static String variableName(String credential) {
    return "IDB_CREDENTIAL_" + credential.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
  }

  @Override
  public String password(String credential) {
    String fromEnv = env.get(variableName(credential));
    if (fromEnv != null && !fromEnv.isEmpty()) {
      return fromEnv;
    }
    return SecretsFile.resolve(secretsFile, credential, key);
  }
}
