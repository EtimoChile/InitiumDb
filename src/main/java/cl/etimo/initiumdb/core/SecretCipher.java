package cl.etimo.initiumdb.core;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM encryption of individual secret values.
 *
 * <p>Token format: {@code enc:v1:aes256gcm:<nonce>:<ciphertext>}, both parts URL-safe base64.
 */
public final class SecretCipher {

  public static final String PREFIX = "enc:v1:aes256gcm:";

  private static final int NONCE_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final SecureRandom RANDOM = new SecureRandom();

  private SecretCipher() {}

  /** Returns whether the value is already an encrypted token. */
  public static boolean isEncrypted(String value) {
    return value.startsWith(PREFIX);
  }

  /** Encrypts {@code plaintext} with a fresh random nonce. */
  public static String encrypt(String plaintext, byte[] key) {
    byte[] nonce = new byte[NONCE_BYTES];
    RANDOM.nextBytes(nonce);
    try {
      byte[] ciphertext =
          cipher(Cipher.ENCRYPT_MODE, key, nonce)
              .doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      Base64.Encoder b64 = Base64.getUrlEncoder();
      return PREFIX + b64.encodeToString(nonce) + ":" + b64.encodeToString(ciphertext);
    } catch (GeneralSecurityException e) {
      throw new InitiumDbException("Unable to encrypt secret.");
    }
  }

  /** Decrypts a token produced by {@link #encrypt}. Callers must never print the result. */
  public static String decrypt(String token, byte[] key) {
    if (!isEncrypted(token)) {
      throw new InitiumDbException("Value is not an encrypted token.");
    }
    String[] parts = token.substring(PREFIX.length()).split(":", -1);
    if (parts.length != 2) {
      throw new InitiumDbException("Malformed encrypted token.");
    }
    try {
      Base64.Decoder b64 = Base64.getUrlDecoder();
      byte[] plaintext =
          cipher(Cipher.DECRYPT_MODE, key, b64.decode(parts[0])).doFinal(b64.decode(parts[1]));
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new InitiumDbException("Malformed encrypted token.");
    } catch (GeneralSecurityException e) {
      throw new InitiumDbException(
          "Unable to decrypt secret: wrong master key or corrupted value.");
    }
  }

  private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws GeneralSecurityException {
    if (key == null || key.length != MasterKey.KEY_BYTES) {
      throw new InitiumDbException("Master key must be " + MasterKey.KEY_BYTES + " bytes.");
    }
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
    return cipher;
  }
}
