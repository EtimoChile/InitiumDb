package cl.etimo.initiumdb.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Strict YAML reading helpers shared by the configuration loaders. */
final class YamlFiles {

  private static final ObjectMapper MAPPER =
      new ObjectMapper(
          new YAMLFactory()
              .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));

  private static final ObjectMapper WRITER =
      new ObjectMapper(
          YAMLFactory.builder()
              .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
              .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
              .enable(YAMLGenerator.Feature.LITERAL_BLOCK_STYLE)
              .build());

  private YamlFiles() {}

  /** Serializes a mapping as YAML (the only way configuration files are written). */
  static String write(ObjectNode node) {
    try {
      return WRITER.writeValueAsString(node);
    } catch (JsonProcessingException e) {
      throw new InitiumDbException("Unable to serialize YAML.");
    }
  }

  /** Reads a YAML mapping. Parser messages are not propagated (they may quote file content). */
  static ObjectNode readObject(Path file, String what) {
    if (!Files.isRegularFile(file)) {
      throw new InitiumDbException(what + " not found: " + file);
    }
    try {
      JsonNode node = MAPPER.readTree(Files.readString(file, StandardCharsets.UTF_8));
      if (node instanceof ObjectNode object) {
        return object;
      }
    } catch (JsonProcessingException e) {
      // Reported below.
    } catch (IOException e) {
      throw new InitiumDbException("Unable to read " + what + " " + file + ".");
    }
    throw new InitiumDbException(what + " " + file + " must be a valid YAML mapping.");
  }

  static void requireVersion(ObjectNode node, Path file) {
    JsonNode version = node.get("version");
    if (version == null || !version.isInt() || version.asInt() != 1) {
      throw new InitiumDbException(
          "Unsupported or missing 'version' in " + file + " (expected 1).");
    }
  }

  static String requiredText(JsonNode node, String field, Path file) {
    String value = optionalText(node, field, file);
    if (value == null) {
      throw new InitiumDbException("Missing '" + field + "' in " + file + ".");
    }
    return value;
  }

  static String optionalText(JsonNode node, String field, Path file) {
    JsonNode value = node.get(field);
    if (value == null || value.isNull()) {
      return null;
    }
    if (!value.isTextual() || value.asText().isBlank()) {
      throw new InitiumDbException("'" + field + "' in " + file + " must be a non-empty string.");
    }
    return value.asText();
  }

  static ObjectNode requiredObject(JsonNode node, String field, Path file) {
    JsonNode value = node.get(field);
    if (value instanceof ObjectNode object && !object.isEmpty()) {
      return object;
    }
    throw new InitiumDbException("'" + field + "' in " + file + " must be a non-empty mapping.");
  }
}
