using System.IO;
using System.Text.Json.Serialization;
using System.Text.Json;

namespace Veylo.Launcher;

public sealed record Profile(Guid Id, string Name)
{
    public string VersionId { get; set; } = GameVersion.Default.Id;
    public bool UseVeylo { get; set; } = true;
    public string Loader { get; set; } = "vanilla";
    public string? CoverFile { get; set; }
    [JsonIgnore]
    public string Mode => UseVeylo ? "veylo" : Loader == "fabric" ? "fabric" : "vanilla";
    [JsonIgnore]
    public bool HasMods => Mode != "vanilla";
    [JsonIgnore]
    public string ModeLabel => Mode switch
    {
        "veylo" => "Veylo / Fabric",
        "fabric" => "Fabric",
        _ => "Vanilla"
    };
    [JsonIgnore]
    public string Details => VersionId + " · " + ModeLabel;
    public int RamMb { get; set; } = 4096;
    public DateTimeOffset? LastPlayedAt { get; set; }
}

internal sealed class ProfileStore(string directory)
{
    private readonly string path = Path.Combine(directory, "profiles.json");

    public List<Profile> Load()
    {
        if (!File.Exists(path))
            return [new Profile(Guid.NewGuid(), "Veylo Standard")];

        var profiles = JsonSerializer.Deserialize<List<Profile>>(File.ReadAllText(path));
        if (profiles is null || profiles.Count == 0 ||
            profiles.Any(profile => profile is null || profile.Id == Guid.Empty ||
                                    string.IsNullOrWhiteSpace(profile.Name)) ||
            profiles.Select(profile => profile.Id).Distinct().Count() != profiles.Count)
            throw new InvalidDataException("Die gespeicherten Profile sind ungültig.");

        foreach (var profile in profiles)
        {
            profile.RamMb = Math.Clamp(profile.RamMb, 2048, 12288);
            if (!GameVersion.IsSafeId(profile.VersionId)) profile.VersionId = GameVersion.Default.Id;
            profile.UseVeylo &= profile.VersionId == GameVersion.Default.Id;
            if (profile.Loader != "fabric") profile.Loader = "vanilla";
            if (profile.CoverFile != profile.Id.ToString("N") + ".png") profile.CoverFile = null;
        }
        return profiles;
    }

    public static Profile[] RecentlyPlayed(IEnumerable<Profile> profiles) =>
        profiles.Where(profile => profile.LastPlayedAt.HasValue)
            .OrderByDescending(profile => profile.LastPlayedAt)
            .Take(3)
            .ToArray();

    public void Save(IEnumerable<Profile> profiles)
    {
        Directory.CreateDirectory(directory);
        var temporary = path + ".tmp";
        try
        {
            File.WriteAllText(temporary, JsonSerializer.Serialize(profiles, new JsonSerializerOptions
            {
                WriteIndented = true
            }));
            File.Move(temporary, path, overwrite: true);
        }
        finally
        {
            File.Delete(temporary);
        }
    }
}
