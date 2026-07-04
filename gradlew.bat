@echo off
setlocal

rem Lightweight Gradle bootstrap script for Windows environments where the binary
rem gradle-wrapper.jar cannot be committed by the current authoring tool.

set APP_HOME=%~dp0
set GRADLE_VERSION=9.1.0
set BOOTSTRAP_ROOT=%USERPROFILE%\.gradle\bootstrap-dists
set DIST_DIR=%BOOTSTRAP_ROOT%\gradle-%GRADLE_VERSION%
set ZIP_PATH=%BOOTSTRAP_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
set GRADLE_BIN=%DIST_DIR%\bin\gradle.bat

if not exist "%GRADLE_BIN%" (
  if not exist "%BOOTSTRAP_ROOT%" mkdir "%BOOTSTRAP_ROOT%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-9.1.0-bin.zip' -OutFile '%ZIP_PATH%'; Expand-Archive -Force '%ZIP_PATH%' '%BOOTSTRAP_ROOT%'"
)

call "%GRADLE_BIN%" %*
