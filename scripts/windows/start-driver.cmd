@echo off
rem Starts the Chromium Developer Bridge driver (see start-driver.ps1 for options).
set ROOT=%~dp0..\..
if "%CDB_DRIVER_JAR%"=="" set CDB_DRIVER_JAR=%ROOT%\browser-driver\target\cdb-driver-exec.jar
java -jar "%CDB_DRIVER_JAR%" %*
