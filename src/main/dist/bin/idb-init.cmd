@echo off
rem Launcher for idb-init. Uses JAVA_HOME when set, otherwise java from PATH (Java 25 or later).
rem Extra JVM options can be passed through IDB_JAVA_OPTS.
setlocal
set "APP_HOME=%~dp0.."
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" %IDB_JAVA_OPTS% -cp "%APP_HOME%\lib\*;%APP_HOME%\drivers\*" cl.etimo.initiumdb.cli.IdbInit %*
exit /b %ERRORLEVEL%
