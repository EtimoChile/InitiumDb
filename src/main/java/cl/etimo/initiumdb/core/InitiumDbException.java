package cl.etimo.initiumdb.core;

/** Failure reported by InitiumDb. Messages must never contain credentials or decrypted secrets. */
public class InitiumDbException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public InitiumDbException(String message) {
    super(message);
  }

  public InitiumDbException(String message, Throwable cause) {
    super(message, cause);
  }
}
