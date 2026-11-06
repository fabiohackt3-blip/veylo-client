# Veylo Client 0.5.0

Windows-Launcher und integrierter Fabric-Client für Minecraft Java.

## Windows-App

In GitHub unter **Actions → Build Veylo for Windows** den erfolgreichen Lauf für den gewünschten Branch öffnen und unter **Artifacts** die ZIP herunterladen. Die ganze ZIP entpacken und `Veylo.exe` starten.

## Funktionsumfang dieser Ausbaustufe

- Anthrazitfarbenes Launcher-Dashboard mit eisblauer Navigation, Profilen, Versionen, Mods, Accounts und Einstellungen.
- Unter **Instanzen** lassen sich Name, Minecraft-Version aus dem Mojang-Katalog und Vanilla oder Fabric auswählen. Jede Instanz hat ein eigenes Standardbild, das durch PNG oder JPEG ersetzt werden kann, sowie getrennte Spielordner. **Veylo / Fabric** mit integriertem HUD ist eine eigene Option für Minecraft 1.21.11. Bestehende Instanzen behalten ihre Spielordner.
- Unter **Mods** steht der öffentliche Modrinth-Katalog für die gewählte Fabric-Instanz zur Verfügung. Suche, Downloads, erforderliche Abhängigkeiten und Updates prüfen die exakte Minecraft-Version und den Modloader. Updates ersetzen nur eine bekannte ältere Version. Mitgelieferte Veylo-Systemmods werden vom Launcher verwaltet.
- Der Launcher verwendet die erfolgreiche interaktive Microsoft-Sitzung, zeigt den Installationsfortschritt und erlaubt Abbruch und erneutes Starten. Die Sitzung bleibt während der Laufzeit im Speicher.
- Lokaler JAR-Import für Fabric und Aktivieren/Deaktivieren je Profil. Der Import prüft das Fabric-Manifest. Die Kompatibilitäts- und Abhängigkeitsprüfung beim Spielstart übernimmt Fabric.
- Rechte Shift öffnet Veylos eigenes Menü mit Suche, Schaltern, Kategorien sowie separaten HUD- und Cosmetics-Seiten. Alle Module haben eigene Einstellungen. Der HUD-Editor bietet Drag & Drop, Einrasten am Bildschirmrand, Skalierung, Hintergrund-Deckkraft, Akzentfarben und bis zu zwölf benannte Layouts.
- HUD-Module umfassen FPS, Ping, Position, Rüstung, Tasten, Richtung, Tempo, Uhr, Speicher, Links-/Rechts-CPS, Effekte, Sprintstatus, Spielzeit, Angriffscooldown und Biom. Ping nutzt die Spielerübersicht oder als gekennzeichneten Ersatz eine Messung über die Serverliste.
- Lokale Partikelspuren: Frostfunken, Seelenfeuer und Waldglanz. Sie sind nur im eigenen Client sichtbar und lassen sich ausschalten.
- Die Windows-EXE und das Launcher-Fenster verwenden das Veylo-Appsymbol.
- Discord-Aktivität wird mit gesetzter Application ID bereits beim Öffnen des Launchers initialisiert. Die Konfiguration steht unter **Einstellungen**. Der Discord-Desktopclient erhält den Launcher- bzw. Spielstatus und die Minecraft-Version. Veylo verwendet den lokalen Discord-RPC-Kanal über DiscordRichPresence.

## Noch offen

Weitere Veylo-Minecraft-Versionen, Forge/NeoForge, verschlüsselte dauerhafte Kontospeicherung und Token-Erneuerung, eigenständige Discord-Präsenz nach Schließen des Launchers, Veylo-App-ID und Rich-Presence-Bildassets für die Distribution, zusätzliche PvP- und Performance-Module, vollständiger Shader-/Cosmetics-Manager und automatische Launcher-Updates.

## Aus dem Quellcode bauen

Auf Windows `Build-Veylo.cmd` mit .NET 8 SDK und JDK 21 ausführen. Für Microsoft-Login wird die Microsoft Edge WebView2 Runtime benötigt. Das Build-Ergebnis liegt in `dist\Veylo`.

## Windows-Herausgeber signieren

Der Windows-Workflow signiert und prüft `Veylo.exe`, sobald im GitHub-Repository das Secret `VEYLO_SIGNING_PFX` (Base64 eines gültigen, öffentlich vertrauenswürdigen Code-Signing-Zertifikats mit privatem Schlüssel) und bei Bedarf `VEYLO_SIGNING_PASSWORD` gesetzt sind. Für Releases kann die Repository-Variable `VEYLO_REQUIRE_SIGNING=true` einen unsignierten Build verhindern. Ohne Zertifikat bleibt der Herausgeber unbekannt. SmartScreen kann bei einer neuen Datei zusätzlich eine Reputationswarnung anzeigen.

Für die Windows-Pakete übernimmt `stageLauncherMods` die unveränderte 1.21.11-Mod aus Veylo 0.4.1 mit verschiebbarem Server-Scoreboard und geglätteter Schrift. Diese Modfassung wurde als JAR ausgeliefert. Ihr Quellcode ist in diesem Repository noch auf 0.4.0.
