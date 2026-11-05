using System.Diagnostics;
using System.IO;
using System.Net.Http;
using System.Text.Json;
using CmlLib.Core;
using CmlLib.Core.Auth;
using CmlLib.Core.ProcessBuilder;

namespace Veylo.Launcher;

internal sealed class GameInstaller(string root)
{
    private const string Minecraft = "1.21.11";
    private const string Loader = "0.19.3";
    private const string FabricVersion = "fabric-loader-" + Loader + "-" + Minecraft;
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(2) };
    private static readonly string[] Payload = ["veylo-client.jar", "fabric-api.jar"];

    public async Task<Process> Start(Profile profile, MSession session, IProgress<string> progress)
    {
        var modsSource = Path.Combine(AppContext.BaseDirectory, "mods");
        if (Payload.Any(file => !File.Exists(Path.Combine(modsSource, file))))
            throw new InvalidDataException("Die Veylo-Mod fehlt. Baue den Launcher mit Build-Veylo.cmd.");

        var gamePath = Path.Combine(root, "profiles", profile.Id.ToString("N"));
        var versionPath = Path.Combine(gamePath, "versions", FabricVersion, FabricVersion + ".json");
        if (!File.Exists(versionPath))
        {
            progress.Report("Fabric wird eingerichtet …");
            var json = await Http.GetStringAsync(
                $"https://meta.fabricmc.net/v2/versions/loader/{Minecraft}/{Loader}/profile/json");
            using var manifest = JsonDocument.Parse(json);
            if (manifest.RootElement.GetProperty("id").GetString() != FabricVersion ||
                manifest.RootElement.GetProperty("inheritsFrom").GetString() != Minecraft)
                throw new InvalidDataException("Fabric hat ein unerwartetes Profil geliefert.");
            Directory.CreateDirectory(Path.GetDirectoryName(versionPath)!);
            var pending = versionPath + ".tmp";
            await File.WriteAllTextAsync(pending, json);
            File.Move(pending, versionPath, overwrite: true);
        }

        var mods = Path.Combine(gamePath, "mods");
        Directory.CreateDirectory(mods);
        foreach (var file in Payload)
        {
            var target = Path.Combine(mods, file);
            File.Copy(Path.Combine(modsSource, file), target + ".tmp", overwrite: true);
            File.Move(target + ".tmp", target, overwrite: true);
        }

        var launcher = new MinecraftLauncher(new MinecraftPath(gamePath));
        progress.Report("Minecraft und Java werden geprüft und bei Bedarf heruntergeladen …");
        var process = await launcher.InstallAndBuildProcessAsync(FabricVersion, new MLaunchOption
        {
            Session = session,
            MaximumRamMb = profile.RamMb
        });
        if (process.Start())
            return process;
        process.Dispose();
        throw new InvalidOperationException("Minecraft konnte nicht gestartet werden.");
    }
}
