# Third-Party Licenses

InitiumDb depends on third-party libraries, each distributed under its own
license. Users must comply with the terms of those licenses when using or
redistributing the software.

## Libraries in the distribution (`lib/`)

| Library | License |
| ------- | ------- |
| DBUnit (`org.dbunit:dbunit`) | GNU LGPL 2.1 — distributed unmodified as a separate jar, so it can be replaced by the user |
| Jackson (`jackson-databind`, `jackson-dataformat-yaml`) | Apache License 2.0 |
| SLF4J NOP binding (`slf4j-nop`) | MIT |

The exact set and versions are those in `pom.xml` and in the `lib/` folder of
each release archive; transitive dependencies keep their own licenses.

## JDBC drivers

JDBC drivers (PostgreSQL, Oracle, ...) are **not** redistributed. Users download
them and place them in the `drivers/` folder, and are bound by the license of
each driver.

## Test-only dependencies

JUnit, AssertJ, Mockito, H2 and Testcontainers are used only to build and test
the project and are not part of the distribution.
