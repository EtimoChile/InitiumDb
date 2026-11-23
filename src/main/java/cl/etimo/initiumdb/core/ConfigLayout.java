package cl.etimo.initiumdb.core;

import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Locations of the per-environment files: {@code <configDir>/<environment>.yml} and {@code
 * <configDir>/<environment>.secrets.json}. The configuration directory defaults to {@code
 * ~/.initiumdb}.
 */
public record ConfigLayout(Path configDir, String environment) {

  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*");

  public ConfigLayout {
    if (environment == null || !NAME.matcher(environment).matches()) {
      throw new InitiumDbException(
          "Invalid environment name: use letters, digits, '_' or '-' (starting with a letter or digit).");
    }
  }

  /** Builds the layout; {@code configDir} may be {@code null} to use {@code <home>/.initiumdb}. */
  public static ConfigLayout of(String environment, Path configDir, Path homeDir) {
    return new ConfigLayout(
        configDir != null ? configDir : homeDir.resolve(".initiumdb"), environment);
  }

  public Path environmentFile() {
    return configDir.resolve(environment + ".yml");
  }

  public Path secretsFile() {
    return configDir.resolve(environment + ".secrets.json");
  }
}
