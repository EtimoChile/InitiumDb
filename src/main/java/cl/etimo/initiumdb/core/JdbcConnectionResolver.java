package cl.etimo.initiumdb.core;

import cl.etimo.initiumdb.core.EnvironmentConfig.DatabaseSettings;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** Generic JDBC resolver: settings from the environment file, password from a provider. */
public final class JdbcConnectionResolver implements ConnectionResolver {

  private final EnvironmentConfig environment;
  private final CredentialProvider credentials;

  public JdbcConnectionResolver(EnvironmentConfig environment, CredentialProvider credentials) {
    this.environment = environment;
    this.credentials = credentials;
  }

  @Override
  public Connection resolve(String environmentName, String databaseAlias) throws SQLException {
    DatabaseSettings settings = environment.database(databaseAlias);
    String password = credentials.password(settings.credential());
    return DriverManager.getConnection(settings.jdbcUrl(), settings.username(), password);
  }
}
