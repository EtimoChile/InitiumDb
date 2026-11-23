package cl.etimo.initiumdb.core;

/** Source of database passwords, looked up by the {@code credential} name. */
public interface CredentialProvider {

  /** Returns the password for {@code credential}. Callers must never log it. */
  String password(String credential);
}
