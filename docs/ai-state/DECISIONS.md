# Decisions

Format: `DEC-NNN | ACTIVE/REPLACED/DISCARDED | title` — decision, why,
artifacts, optional `changelog:`.

DEC-001 | REPLACED by DEC-002 | Python/poetry stack, engine-independent core
Bootstrapped as Python by mistake; `README.md` specifies Java.

DEC-002 | ACTIVE | Java 21 / Maven stack, engine-independent core
`core/` free of SQL, `db/<engine>/` adapters (same layout idea as LimitsDb).
Maven, Java 21 (LTS) baseline. Base package `cl.etimo.initiumdb` (set by the owner),
Maven `groupId` `cl.etimo`. README specifies Java, JDBC, DBUnit and YAML; the build
tool and Java baseline were not specified and are provisional until the owner confirms.
Artifacts: `pom.xml`, `AGENTS.md`, `src/main/java/cl/etimo/initiumdb/`.
