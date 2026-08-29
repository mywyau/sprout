@echo off
setlocal

if defined SPROUT_JAVA_HOME (
  set "JAVA_COMMAND=%SPROUT_JAVA_HOME%\bin\java.exe"
) else if defined JAVA_HOME (
  set "JAVA_COMMAND=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_COMMAND=java"
)

if defined SPROUT_JAR (
  set "JAR_PATH=%SPROUT_JAR%"
) else (
  set "JAR_PATH=%~dp0..\lib\sprout.jar"
)

if not exist "%JAR_PATH%" (
  echo error: Sprout's application jar was not found at: 1>&2
  echo   %JAR_PATH% 1>&2
  echo Reinstall Sprout or set SPROUT_JAR. 1>&2
  exit /b 1
)

"%JAVA_COMMAND%" -jar "%JAR_PATH%" %*
exit /b %ERRORLEVEL%
