package cl.etimo.initiumdb.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Local secrets file: a JSON object mapping each {@code credential} name of the environment file to
 * its password.
 */
public final class SecretsFile {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private SecretsFile() {}

  /**
   * Encrypts every non-empty cleartext value in place. Returns {@code true} when the file changed.
   * Empty values are left untouched; already encrypted values are never re-encrypted.
   */
  public static boolean encryptInPlace(Path file, byte[] key) {
    if (!Files.isRegularFile(file)) {
      throw new InitiumDbException("Secrets file not found: " + file);
    }
    ObjectNode root = read(file);
    List<String> names = new ArrayList<>();
    root.fieldNames().forEachRemaining(names::add);
    boolean updated = false;
    for (String name : names) {
      JsonNode value = root.get(name);
      if (!value.isTextual()) {
        throw new InitiumDbException("Secret '" + name + "' in " + file + " must be a string.");
      }
      String text = value.asText();
      if (!text.isEmpty() && !SecretCipher.isEncrypted(text)) {
        root.put(name, SecretCipher.encrypt(text, key));
        updated = true;
      }
    }
    if (updated) {
      write(file, root);
    }
    return updated;
  }

  private static ObjectNode read(Path file) {
    try {
      JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
      if (node instanceof ObjectNode object) {
        return object;
      }
    } catch (JsonProcessingException e) {
      // The parser message may quote file content, so it is deliberately not propagated.
    } catch (IOException e) {
      throw new InitiumDbException("Unable to read secrets file " + file + ".");
    }
    throw new InitiumDbException("Secrets file " + file + " must be a valid JSON object.");
  }

  private static void write(Path file, ObjectNode root) {
    DefaultPrettyPrinter printer =
        new DefaultPrettyPrinter().withObjectIndenter(new DefaultIndenter("  ", "\n"));
    try {
      Files.writeString(
          file, MAPPER.writer(printer).writeValueAsString(root) + "\n", StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new InitiumDbException("Unable to write secrets file " + file + ".");
    }
  }
}
