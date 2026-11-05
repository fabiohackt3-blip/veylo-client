@echo off
setlocal
pushd "%~dp0"
where dotnet >nul 2>nul
if errorlevel 1 (
  echo Installiere zuerst das .NET 8 SDK.
  goto :failed
)
where java >nul 2>nul
if errorlevel 1 if not defined JAVA_HOME (
  echo Installiere zuerst das JDK 21 und setze JAVA_HOME.
  goto :failed
)

echo [1/2] Veylo Fabric-Mod und Fabric API vorbereiten...
call mod\gradlew.bat -p mod --no-daemon stageLauncherMods
if errorlevel 1 goto :failed

echo [2/2] Windows-Launcher bauen...
dotnet publish launcher\Veylo.Launcher\Veylo.Launcher.csproj -c Release -r win-x64 --self-contained true -o dist\Veylo
if errorlevel 1 goto :failed
if not exist "dist\Veylo\Veylo.exe" goto :failed
if not exist "dist\Veylo\mods\veylo-client.jar" goto :failed
if not exist "dist\Veylo\mods\fabric-api.jar" goto :failed

echo Fertig: dist\Veylo\Veylo.exe
popd
pause
exit /b 0

:failed
echo Der Build konnte nicht abgeschlossen werden. Die Fehlermeldung steht oben.
popd
pause
exit /b 1
