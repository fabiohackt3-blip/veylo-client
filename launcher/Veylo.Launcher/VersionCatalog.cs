using System.Net.Http;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace Veylo.Launcher;

internal sealed record GameVersion(string Id, bool Snapshot = false)
{
    public static readonly GameVersion Default = new("1.21.11");
    public override string ToString() => Snapshot ? Id + " · Snapshot" : Id;

    public static bool IsSafeId(string? value) =>
        value is not null && Regex.IsMatch(value, @"\A[0-9][a-zA-Z0-9._-]{0,47}\z") &&
        !value.EndsWith('.');
}

internal static class VersionCatalog
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    public static async Task<IReadOnlyList<GameVersion>> Fetch()
    {
        using var response = await Http.GetAsync("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync();
        using var manifest = await JsonDocument.ParseAsync(stream);
        return manifest.RootElement.GetProperty("versions").EnumerateArray()
            .Select(item => (Id: item.GetProperty("id").GetString(), Type: item.GetProperty("type").GetString()))
            .Where(item => GameVersion.IsSafeId(item.Id) && item.Type is "release" or "snapshot")
            .Select(item => new GameVersion(item.Id!, item.Type == "snapshot"))
            .DistinctBy(version => version.Id)
            .ToArray();
    }
}
