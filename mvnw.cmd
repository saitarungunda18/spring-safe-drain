@echo off
setlocal
set "MVNW_DIR=%~dp0"
set "MAVEN_VERSION=3.9.11"
set "MAVEN_HOME=%MVNW_DIR%.mvn\apache-maven-%MAVEN_VERSION%"
for /d %%J in ("%MVNW_DIR%.mvn\jdk-21\jdk-*") do set "JAVA_HOME=%%~fJ"
if defined JAVA_HOME set "PATH=%JAVA_HOME%\bin;%PATH%"
if defined USERPROFILE set "MAVEN_OPTS=-Duser.home=%USERPROFILE% %MAVEN_OPTS%"
if not exist "%MAVEN_HOME%\bin\mvn.cmd" (
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$u='https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip'; $z='%MVNW_DIR%.mvn\maven.zip'; Invoke-WebRequest $u -OutFile $z; Expand-Archive -Force $z '%MVNW_DIR%.mvn'; Remove-Item $z"
  if errorlevel 1 exit /b 1
)
call "%MAVEN_HOME%\bin\mvn.cmd" %*
