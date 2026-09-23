@echo off
setlocal EnableExtensions
set "APP_DIR=%~dp0"

if exist "%APP_DIR%local-env.bat" call "%APP_DIR%local-env.bat"

set "JAVA_EXE=%STAR_CCM_JAVA%"
if not defined JAVA_EXE if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA_EXE for %%J in (javaw.exe) do set "JAVA_EXE=%%~$PATH:J"

if not defined JAVA_EXE (
    echo Java was not found. Set STAR_CCM_JAVA in local-env.bat or set JAVA_HOME.
    pause
    exit /b 1
)
if not exist "%JAVA_EXE%" (
    echo Java was not found at: %JAVA_EXE%
    pause
    exit /b 1
)
if not exist "%APP_DIR%StarCleanupGui.jar" (
    echo StarCleanupGui.jar is missing. Run build.ps1 and use the files in dist.
    pause
    exit /b 1
)
if not exist "%APP_DIR%StarCleanup.java" (
    echo StarCleanup.java is missing beside StarCleanupGui.jar.
    pause
    exit /b 1
)

set "STAR_EXE=%STAR_CCM_EXE%"
if not defined STAR_EXE for %%S in (starccm+.bat) do set "STAR_EXE=%%~$PATH:S"
start "" "%JAVA_EXE%" -jar "%APP_DIR%StarCleanupGui.jar" "%STAR_EXE%"
exit /b %errorlevel%
