package cl.etimo.initiumdb.cli;

/** The InitiumDb version, read from the jar manifest ({@code Implementation-Version}). */
final class Version {

  private Version() {}

  /** Returns the packaged version, or {@code unknown} when not running from the jar. */
  static String current() {
    String version = Version.class.getPackage().getImplementationVersion();
    return version != null ? version : "unknown";
  }

  /** The line printed by {@code --version}. */
  static String line(String command) {
    return command + " " + current();
  }
}
