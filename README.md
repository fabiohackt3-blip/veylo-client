# Veylo Client 0.1.0

Ein neues Projekt mit Windows-Launcher und Ingame-Mod für Minecraft Java 1.21.11.

## Unter Windows bauen

1. Das **.NET 8 SDK**, ein **JDK 21** und die **Microsoft Edge WebView2 Runtime** installieren. Für Gradle sollte `JAVA_HOME` auf das JDK 21 zeigen.
2. `Build-Veylo.cmd` starten. Gradle und die Abhängigkeiten werden beim ersten Build heruntergeladen.
3. `dist\Veylo\Veylo.exe` öffnen. Beim Weitergeben den gesamten Ordner `dist\Veylo` zusammenhalten.

## In Minecraft ausprobieren

1. Im Launcher **Anmelden** drücken und ein Microsoft-Konto mit Minecraft Java verwenden. Die App hält die Spielsitzung für die Laufzeit im Arbeitsspeicher.
2. Profil und RAM auswählen. **Spielen** installiert bei Bedarf Minecraft 1.21.11, Java und Fabric und kopiert Veylo sowie Fabric API in die gewählte Instanz.
3. In einer Welt **rechte Shift-Taste** drücken. Im Menü sind FPS, Koordinaten, Ping, Bewegungstasten und Rüstung einzeln schaltbar. Mit **+ / −** skalieren. Mit **HUD anordnen** die aktiven Anzeigen verschieben.
4. **F8** blendet das gesamte Veylo-HUD aus oder ein. Unter **Hotkeys** lassen sich beide Tasten ändern.

Die Profile liegen getrennt unter `%LOCALAPPDATA%\Veylo\profiles`. Die HUD-Einstellungen liegen im jeweiligen Profil unter `config\veylo-hud.properties`.

## Aufbau

- `launcher/Veylo.Launcher` enthält die WPF-App mit CmlLib für Minecraft-Installation, Start und Microsoft-Authentifizierung.
- `mod` enthält die Java-Mod und den Gradle-Build. `gradlew.bat build` kann dort auch separat ausgeführt werden.

Der vollständige Start mit Microsoft-Login und das HUD in einer Minecraft-Welt müssen auf Windows getestet werden.
