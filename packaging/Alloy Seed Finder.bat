@echo off
rem Alloy Seed Finder for Windows: finds Java 21 (the Minecraft launcher's own Java works) and opens the app.
setlocal
cd /d "%~dp0"
set "JAVA="
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\javaw.exe" set "JAVA=%JAVA_HOME%\bin\javaw.exe"

rem The Java that the Minecraft launcher installs for 1.21 (Microsoft Store, classic and Prism installs).
if not defined JAVA if exist "%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\javaw.exe" set "JAVA=%LOCALAPPDATA%\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\javaw.exe"
if not defined JAVA if exist "%ProgramFiles(x86)%\Minecraft Launcher\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\javaw.exe" set "JAVA=%ProgramFiles(x86)%\Minecraft Launcher\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\javaw.exe"
if not defined JAVA if exist "%APPDATA%\.minecraft\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\javaw.exe" set "JAVA=%APPDATA%\.minecraft\runtime\java-runtime-delta\windows-x64\java-runtime-delta\bin\javaw.exe"
if not defined JAVA if exist "%APPDATA%\PrismLauncher\java\java-runtime-delta\bin\javaw.exe" set "JAVA=%APPDATA%\PrismLauncher\java\java-runtime-delta\bin\javaw.exe"

rem An installed Java 21 or newer (Adoptium, Microsoft, Oracle).
if not defined JAVA for /d %%D in ("%ProgramFiles%\Eclipse Adoptium\jdk-2*" "%ProgramFiles%\Eclipse Adoptium\jre-2*" "%ProgramFiles%\Microsoft\jdk-2*" "%ProgramFiles%\Java\jdk-2*") do if exist "%%~D\bin\javaw.exe" set "JAVA=%%~D\bin\javaw.exe"

rem Last try: whatever javaw is on the PATH (the app says so if it's too old).
if not defined JAVA for %%J in (javaw.exe) do if not "%%~$PATH:J"=="" set "JAVA=%%~$PATH:J"

if not defined JAVA (
  echo Alloy Seed Finder needs Java 21.
  echo.
  echo Easiest: open the Minecraft launcher and play 1.21 once ^(it installs Java 21^), then try again.
  echo Or install Java 21 from adoptium.net ^(opening it now^).
  start "" "https://adoptium.net/temurin/releases/?version=21"
  pause
  exit /b 1
)
rem The graphics card code talks to the OpenCL driver through Java's foreign function API (a preview in Java 21).
set "JDK_JAVA_OPTIONS=--enable-preview --enable-native-access=ALL-UNNAMED %JDK_JAVA_OPTIONS%"
start "" "%JAVA%" -XX:MaxRAMPercentage=40 -jar "%~dp0AlloySeedFinder.jar" %*
