# Security Policy

InitiumDb reads and writes database data and resolves database credentials, so
security reports are taken seriously.

## Supported versions

Only the latest released version receives security fixes.

## Reporting a vulnerability

Please **do not open a public issue** for a vulnerability. Report it privately
through GitHub: **Security** tab → **Report a vulnerability** on
<https://github.com/EtimoChile/InitiumDb>. If that is not available to you,
write to **contacto@etimo.cl** with the subject "InitiumDb security".

Include the InitiumDb version, a description of the problem, steps to reproduce
and the potential impact. Do not include real credentials, JDBC URLs with
secrets or real personal data.

We aim to acknowledge a report within 5 business days and to agree on a fix and
disclosure timeline with you. Reporters are credited in the release notes unless
they prefer otherwise.

## Scope

In scope: leaking or mishandling secrets (local secrets file, master key,
logs), SQL or command injection through configuration values, unsafe handling
of captured datasets, and weaknesses in the distribution or release process.

Out of scope: running InitiumDb against production databases (it is designed
for development and QA only), data it was configured to capture, and
vulnerabilities in JDBC drivers or other third-party libraries (report those
upstream).
