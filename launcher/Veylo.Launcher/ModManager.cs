using System.IO;
using System.IO.Compression;
using System.Text.Json;

namespace Veylo.Launcher;

internal static class ModManager
{
    internal sealed record Entry(string Path, bool Enabled, bool Bundled)
    {
        public override string ToString() => System.IO.Path.GetFileName(Path) +
            (Bundled ? "  ·  Veylo-Systemdatei" : Enabled ? "  ·  Aktiv" : "  ·  Aus");
    }

    private static bool IsBundled(string name) =>
        name.Equals("veylo-client.jar", StringComparison.OrdinalIgnoreCase) ||
        name.Equals("fabric-api.jar", StringComparison.OrdinalIgnoreCase);

    public static IEnumerable<Entry> List(string folder) => Directory.Exists(folder)
        ? Directory.EnumerateFiles(folder).Where(path =>
                path.EndsWith(".jar", StringComparison.OrdinalIgnoreCase) ||
                path.EndsWith(".jar.disabled", StringComparison.OrdinalIgnoreCase))
            .OrderBy(Path.GetFileName, StringComparer.OrdinalIgnoreCase).Select(path =>
                new Entry(path, !path.EndsWith(".disabled", StringComparison.OrdinalIgnoreCase),
                    IsBundled(Path.GetFileName(path).Replace(".jar.disabled", ".jar", StringComparison.OrdinalIgnoreCase))))
        : [];

    public static void Toggle(Entry entry)
    {
        if (entry.Bundled) throw new InvalidDataException("Veylo und Fabric API werden vom Launcher verwaltet.");
        File.Move(entry.Path, entry.Enabled ? entry.Path + ".disabled" : entry.Path[..^9]);
    }

    public static async Task Import(string source, string folder)
    {
        var file = new FileInfo(source);
        if (file.Length is 0 or > 128 * 1024 * 1024 || IsBundled(file.Name))
            throw new InvalidDataException("Diese Mod-Datei kann nicht importiert werden.");
        Directory.CreateDirectory(folder);
        var target = Path.Combine(folder, file.Name);
        if (File.Exists(target) || File.Exists(target + ".disabled"))
            throw new InvalidDataException("Eine Mod mit diesem Dateinamen ist bereits installiert.");
        string pending = target + "." + Guid.NewGuid().ToString("N") + ".tmp";
        try
        {
            await using (var input = File.OpenRead(source))
            await using (var output = File.Create(pending))
                await input.CopyToAsync(output);
            var id = ReadFabricId(pending);
            if (id is "veylo" or "fabric-api")
                throw new InvalidDataException("Die Mod-ID ist für Veylo reserviert.");
            File.Move(pending, target);
        }
        finally
        {
            File.Delete(pending);
        }
    }

    internal static string? TryReadFabricId(string path)
    {
        try { return ReadFabricId(path); }
        catch (Exception error) when (error is InvalidDataException or IOException or JsonException) { return null; }
    }

    internal static string ReadFabricId(string path)
    {
        using var archive = ZipFile.OpenRead(path);
        var manifest = archive.GetEntry("fabric.mod.json");
        if (manifest is null || manifest.Length > 256 * 1024)
            throw new InvalidDataException("Die JAR enthält kein gültiges Fabric-Mod-Manifest.");
        using var content = manifest.Open();
        using var document = JsonDocument.Parse(content);
        if (document.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String &&
            !string.IsNullOrWhiteSpace(id.GetString()))
            return id.GetString()!;
        throw new InvalidDataException("In der JAR fehlt die Fabric-Mod-ID.");
    }

}
