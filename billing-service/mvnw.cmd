@ECHO OFF
SETLOCAL

SET BASE_DIR=%~dp0
SET WRAPPER_JAR=%BASE_DIR%\.mvn\wrapper\maven-wrapper.jar
SET WRAPPER_PROPS=%BASE_DIR%\.mvn\wrapper\maven-wrapper.properties

IF NOT EXIST "%WRAPPER_JAR%" (
  IF NOT EXIST "%WRAPPER_PROPS%" (
    ECHO Missing %WRAPPER_PROPS%
    EXIT /B 1
  )
)

java -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*

