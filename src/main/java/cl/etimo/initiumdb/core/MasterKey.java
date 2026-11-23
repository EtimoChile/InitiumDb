package cl.etimo.initiumdb.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Resolves the AES-256 master key used to encrypt local secrets.
 *
 * <p>Order: the {@value #ENV_VAR} environment variable (hex of 64 chars, base64 of 32 bytes, or a
 * raw 32-byte string); otherwise {@code ~/.initiumdb/idb.key}, created with owner-only permissions
 * when absent.
 */
public final class MasterKey {

  public static final String ENV_VAR = "IDB_MASTER_KEY";
  public static final int KEY_BYTES = 32;

  private static final Pattern HEX64 = Pattern.compile("[0-9a-fA-F]{64}");
  private static final SecureRandom RANDOM = new SecureRandom();

  private MasterKey() {}

  /** Location of the key file under the given home directory. */
  public static Path keyPath(Path homeDir) {
    return homeDir.resolve(".initiumdb").resolve("idb.key");
  }

  /** Returns the existing master key; fails instead of creating one when none exists. */
  public static byte[] load(Map<String, String> env, Path homeDir) {
    return resolve(env, homeDir, false);
  }

  /** Returns the master key, creating the key file when neither the variable nor the file exist. */
  public static byte[] loadOrCreate(Map<String, String> env, Path homeDir) {
    return resolve(env, homeDir, true);
  }

  private static byte[] resolve(Map<String, String> env, Path homeDir, boolean create) {
    String fromEnv = env.get(ENV_VAR);
    if (fromEnv != null && !fromEnv.isEmpty()) {
      return parseEnvValue(fromEnv);
    }
    Path path = keyPath(homeDir);
    try {
      if (Files.exists(path)) {
        return readKeyFile(path);
      }
      if (!create) {
        throw new InitiumDbException(
            "No master key: set " + ENV_VAR + " or create " + path + " (idb-init creates it).");
      }
      return createKeyFile(path);
    } catch (IOException e) {
      throw new InitiumDbException("Unable to access the master key file " + path + ".");
    }
  }

  private static byte[] parseEnvValue(String value) {
    if (HEX64.matcher(value).matches()) {
      return HexFormat.of().parseHex(value);
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(value);
      if (decoded.length == KEY_BYTES) {
        return decoded;
      }
    } catch (IllegalArgumentException ignored) {
      // Not base64: fall through to the raw form.
    }
    byte[] raw = value.getBytes(StandardCharsets.UTF_8);
    if (raw.length == KEY_BYTES) {
      return raw;
    }
    throw new InitiumDbException(
        "Invalid " + ENV_VAR + ": use hex (64 chars), base64 (32 bytes) or a raw 32-byte value.");
  }

  private static byte[] readKeyFile(Path path) throws IOException {
    String text = Files.readString(path, StandardCharsets.UTF_8).strip();
    try {
      byte[] key = Base64.getDecoder().decode(text);
      if (key.length == KEY_BYTES) {
        return key;
      }
    } catch (IllegalArgumentException ignored) {
      // Reported below without echoing file content.
    }
    throw new InitiumDbException(
        "Invalid master key file " + path + ": expected base64 of 32 bytes.");
  }

  private static byte[] createKeyFile(Path path) throws IOException {
    byte[] key = new byte[KEY_BYTES];
    RANDOM.nextBytes(key);
    byte[] data = Base64.getEncoder().encode(key);
    Files.createDirectories(path.getParent());
    try {
      if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
        Files.createFile(
            path,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.write(path, data, StandardOpenOption.TRUNCATE_EXISTING);
      } else {
        Files.write(path, data, StandardOpenOption.CREATE_NEW);
      }
    } catch (FileAlreadyExistsException e) {
      return readKeyFile(path);
    }
    return key;
  }
}
