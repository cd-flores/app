@echo off
cd /d %~dp0
rem An invalid JAVA_HOME (for example a leftover template path) stops Gradle from starting.
rem If it doesn't point at a real JDK, ignore it and use the java on the PATH instead.
if defined JAVA_HOME if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JAVA_HOME "%JAVA_HOME%" is not a valid JDK - using java from PATH instead.
    set "JAVA_HOME="
)
call gradlew.bat lwjgl3:run
if errorlevel 1 pause
