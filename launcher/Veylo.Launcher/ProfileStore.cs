using System.IO;
using System.Text.Json;

namespace Veylo.Launcher;

public sealed record Profile(Guid Id, string Name)
{
    public int RamMb { get; set; } = 4096;
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
            profile.RamMb = Math.Clamp(profile.RamMb, 2048, 12288);
        return profiles;
    }

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
