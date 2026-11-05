# Veylo Client 0.2.0

Windows-Launcher und integrierter Fabric-Client für Minecraft Java.

## Windows-App

In GitHub unter **Actions → Build Veylo for Windows** den erfolgreichen Lauf für den gewünschten Branch öffnen und unter **Artifacts** die ZIP herunterladen. Die ganze ZIP entpacken und `Veylo.exe` starten.

## Funktionsumfang dieser Ausbaustufe

- Anthrazitfarbenes Launcher-Dashboard mit eisblauer Navigation, Profilen, Versionen, Mods, Accounts und Einstellungen.
- Offizieller Mojang-Versionskatalog mit optionalen Snapshots und lokal getrennten Vanilla-Instanzen. **Veylo mit Fabric und dem integrierten HUD unterstützt derzeit Minecraft 1.21.11.** Bestehende 1.21.11-Fabric-Profile behalten ihren bisherigen Ordner.
- Der Launcher verwendet die erfolgreiche interaktive Microsoft-Sitzung, zeigt den Installationsfortschritt und erlaubt Abbruch und erneutes Starten. Die Sitzung bleibt während der Laufzeit im Speicher.
- Lokaler JAR-Import für Fabric und Aktivieren/Deaktivieren je Profil. Der Import prüft das Fabric-Manifest. Die Kompatibilitäts- und Abhängigkeitsprüfung beim Spielstart übernimmt Fabric.
- Right Shift öffnet das Modulmenü mit Suche, Kategorien, HUD-Editor und Hotkeys. Richtung, Tempo, Uhr und Speicher ergänzen die bisherigen Anzeigen. Die neuen Anzeigen sind zunächst aus, damit bestehende Layouts übersichtlich bleiben.
- Discord-Aktivität wird mit gesetzter Application ID bereits beim Öffnen des Launchers initialisiert. Die Konfiguration steht unter **Einstellungen**. Der Discord-Desktopclient erhält den Launcher- bzw. Spielstatus und die Minecraft-Version. Veylo verwendet den lokalen Discord-RPC-Kanal über DiscordRichPresence.

## Noch offen

Weitere Veylo-Minecraft-Versionen, Forge/NeoForge, verschlüsselte dauerhafte Kontospeicherung und Token-Erneuerung, eigenständige Discord-Präsenz nach Schließen des Launchers, Veylo-App-ID und Rich-Presence-Bildassets für die Distribution, zusätzliche PvP- und Performance-Module, vollständiger Shader-/Cosmetics-Manager, Update-System und erweiterte HUD-Farb- und Transparenzoptionen.

## Aus dem Quellcode bauen

Auf Windows `Build-Veylo.cmd` mit .NET 8 SDK und JDK 21 ausführen. Für Microsoft-Login wird die Microsoft Edge WebView2 Runtime benötigt. Das Build-Ergebnis liegt in `dist\Veylo`.
