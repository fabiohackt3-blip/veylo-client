using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;

namespace Veylo.Launcher;

internal sealed record InstallProgress(string Message, double? Percent = null);

internal sealed class GameInstaller(string root)
{
    private const string Loader = "0.19.3";
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(2) };
    private static readonly string[] Payload = ["veylo-client.jar", "fabric-api.jar"];

    public string GamePath(Profile profile)
    {
        var directory = Path.Combine(root, "profiles", profile.Id.ToString("N"));
        if (profile.UseVeylo && profile.VersionId == GameVersion.Default.Id)
            return directory; // Preserve existing worlds and settings for the original profile format.
        if (!GameVersion.IsSafeId(profile.VersionId))
            throw new InvalidDataException("Ungültige Minecraft-Version.");
        return Path.Combine(directory, "instances", profile.VersionId + "-" + profile.Mode);
    }

    public async Task<Process> Start(Profile profile, MSession session,
        IProgress<InstallProgress> progress, CancellationToken cancellation)
    {
        string gamePath = GamePath(profile);
        string version = profile.VersionId;
        if (profile.HasMods)
        {
            if (profile.UseVeylo && profile.VersionId != GameVersion.Default.Id)
                throw new InvalidDataException("Die Veylo-Mod unterstützt Minecraft 1.21.11.");
            var loader = profile.UseVeylo ? Loader : await StableFabricLoader(profile.VersionId, cancellation);
            version = $"fabric-loader-{loader}-{profile.VersionId}";
            await PrepareFabric(gamePath, profile.VersionId, loader, version, progress, cancellation);
            if (profile.UseVeylo) StageVeyloMods(gamePath, cancellation);
        }

        var launcher = new MinecraftLauncher(new MinecraftPath(gamePath));
        progress.Report(new("Minecraft-Dateien und Java werden geprüft …"));
        var timer = Stopwatch.StartNew();
        long previousBytes = 0;
        long previousMilliseconds = 0;
        int bytesReported = 0;
        launcher.FileProgressChanged += (_, e) =>
        {
            if (Volatile.Read(ref bytesReported) != 0 || e.TotalTasks <= 0) return;
            progress.Report(new($"Dateien: {e.ProgressedTasks:N0} / {e.TotalTasks:N0}",
                100d * e.ProgressedTasks / e.TotalTasks));
        };
        launcher.ByteProgressChanged += (_, e) =>
        {
            if (e.TotalBytes <= 0) return;
            var elapsed = timer.ElapsedMilliseconds;
            if (elapsed - previousMilliseconds < 350 && e.ProgressedBytes < e.TotalBytes) return;
            Interlocked.Exchange(ref bytesReported, 1);
            double mib = 1024d * 1024d;
            double rate = Math.Max(0, e.ProgressedBytes - previousBytes) * 1000d /
                          Math.Max(1, elapsed - previousMilliseconds) / mib;
            previousMilliseconds = elapsed;
            previousBytes = e.ProgressedBytes;
            progress.Report(new($"Download: {e.ProgressedBytes / mib:N1} / {e.TotalBytes / mib:N1} MB · {rate:N1} MB/s",
                100d * e.ProgressedBytes / e.TotalBytes));
        };
        await launcher.InstallAsync(version, cancellation);
        cancellation.ThrowIfCancellationRequested();
        progress.Report(new("Minecraft wird gestartet …"));
        var process = await launcher.BuildProcessAsync(version, new MLaunchOption
        {
            Session = session,
            MaximumRamMb = profile.RamMb
        });
        try
        {
            cancellation.ThrowIfCancellationRequested();
            if (process.Start()) return process;
            throw new InvalidOperationException("Minecraft konnte nicht gestartet werden.");
        }
        catch
        {
            process.Dispose();
            throw;
        }
    }

    internal static async Task<string> StableFabricLoader(string minecraft, CancellationToken cancellation)
    {
        if (!GameVersion.IsSafeId(minecraft)) throw new InvalidDataException("Ungültige Minecraft-Version.");
        using var response = await Http.GetAsync($"https://meta.fabricmc.net/v2/versions/loader/{minecraft}", cancellation);
        if (response.StatusCode is System.Net.HttpStatusCode.NotFound or System.Net.HttpStatusCode.BadRequest)
            throw new InvalidDataException($"Fabric ist für Minecraft {minecraft} nicht verfügbar.");
        response.EnsureSuccessStatusCode();
        await using var body = await response.Content.ReadAsStreamAsync(cancellation);
        using var manifest = await JsonDocument.ParseAsync(body, cancellationToken: cancellation);
        foreach (var entry in manifest.RootElement.EnumerateArray())
        {
            var candidate = entry.GetProperty("loader");
            var loader = candidate.GetProperty("version").GetString();
            if (candidate.GetProperty("stable").GetBoolean() && GameVersion.IsSafeId(loader)) return loader!;
        }
        throw new InvalidDataException($"Für Minecraft {minecraft} gibt es noch keinen stabilen Fabric Loader.");
    }

    private static async Task PrepareFabric(string gamePath, string minecraft, string loader, string version,
        IProgress<InstallProgress> progress, CancellationToken cancellation)
    {
        string versionPath = Path.Combine(gamePath, "versions", version, version + ".json");
        if (!File.Exists(versionPath))
        {
            progress.Report(new("Fabric wird eingerichtet …"));
            string json = await Http.GetStringAsync(
                $"https://meta.fabricmc.net/v2/versions/loader/{minecraft}/{loader}/profile/json", cancellation);
            using var manifest = JsonDocument.Parse(json);
            if (manifest.RootElement.GetProperty("id").GetString() != version ||
                manifest.RootElement.GetProperty("inheritsFrom").GetString() != minecraft)
                throw new InvalidDataException("Fabric hat ein unerwartetes Profil geliefert.");
            Directory.CreateDirectory(Path.GetDirectoryName(versionPath)!);
            await File.WriteAllTextAsync(versionPath + ".tmp", json, cancellation);
            File.Move(versionPath + ".tmp", versionPath, overwrite: true);
        }
    }

    private static void StageVeyloMods(string gamePath, CancellationToken cancellation)
    {
        string modsSource = Path.Combine(AppContext.BaseDirectory, "mods");
        if (Payload.Any(file => !File.Exists(Path.Combine(modsSource, file))))
            throw new InvalidDataException("Die Veylo-Mod fehlt. Entpacke das vollständige Windows-Paket.");
        string mods = Path.Combine(gamePath, "mods");
        Directory.CreateDirectory(mods);
        foreach (var file in Payload)
        {
            cancellation.ThrowIfCancellationRequested();
            string target = Path.Combine(mods, file);
            File.Copy(Path.Combine(modsSource, file), target + ".tmp", overwrite: true);
            File.Move(target + ".tmp", target, overwrite: true);
        }
    }
}
