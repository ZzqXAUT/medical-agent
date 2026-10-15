@echo off
setlocal
set "MAVEN_VERSION=3.9.11"
set "SCRIPT_DIR=%~dp0"
set "MAVEN_HOME=%SCRIPT_DIR%.mvn\apache-maven-%MAVEN_VERSION%"
set "MAVEN_ZIP=%SCRIPT_DIR%.mvn\apache-maven-%MAVEN_VERSION%-bin.zip"

if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  if not exist "%MAVEN_ZIP%" (
    echo Downloading Apache Maven %MAVEN_VERSION%...
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing 'https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip' -OutFile '%MAVEN_ZIP%'"
    if errorlevel 1 exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -LiteralPath '%MAVEN_ZIP%' -DestinationPath '%SCRIPT_DIR%.mvn' -Force"
  if errorlevel 1 exit /b 1
)

call "%MAVEN_HOME%\bin\mvn.cmd" -Dmaven.repo.local="%SCRIPT_DIR%.mvn\repository" %*
