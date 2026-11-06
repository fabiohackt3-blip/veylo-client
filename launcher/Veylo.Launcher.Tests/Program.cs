using System.Text.Json;
using Veylo.Launcher;

var current = Release("current", "26.3", "fabric", "release", "2026-10-01");
var newer = Release("next", "26.3", "fabric", "release", "2026-10-02");
Assert(newer.IsUpdateFor(current, "26.3", "fabric"), "a newer compatible release updates");
Assert(!Release("other-game", "26.3.1", "fabric", "release", "2026-10-03")
    .IsUpdateFor(current, "26.3", "fabric"), "updates require exact Minecraft version");
Assert(!Release("other-loader", "26.3", "neoforge", "release", "2026-10-03")
    .IsUpdateFor(current, "26.3", "fabric"), "updates require exact loader");
Assert(!Release("beta", "26.3", "fabric", "beta", "2026-10-03")
    .IsUpdateFor(current, "26.3", "fabric"), "stable stays stable");
Assert(!current.IsUpdateFor(newer, "26.3", "fabric"), "updates never downgrade");

var path = Path.Combine(Path.GetTempPath(), "veylo-profile-" + Guid.NewGuid().ToString("N"));
Directory.CreateDirectory(path);
try
{
    var oldId = Guid.NewGuid();
    File.WriteAllText(Path.Combine(path, "profiles.json"), JsonSerializer.Serialize(new[]
    {
        new { Id = oldId, Name = "Veylo Standard", VersionId = "1.21.11", UseVeylo = true },
        new { Id = Guid.NewGuid(), Name = "Vanilla", VersionId = "26.3", UseVeylo = false }
    }));
    var store = new ProfileStore(path);
    var profiles = store.Load();
    Assert(profiles[0].Id == oldId && profiles[0].Mode == "veylo", "legacy Veylo profile preserved");
    Assert(profiles[1].Mode == "vanilla", "legacy vanilla profile preserved");
    profiles.Add(new Profile(Guid.NewGuid(), "26.3 Fabric") { VersionId = "26.3", UseVeylo = false, Loader = "fabric" });
    store.Save(profiles);
    Assert(store.Load()[2].Mode == "fabric", "Fabric selection persists outside 1.21.11");
}
finally { Directory.Delete(path, recursive: true); }

Console.WriteLine("Instance migration and Modrinth update rules passed.");

static void Assert(bool value, string message)
{
    if (!value) throw new Exception(message);
}

static ModrinthVersion Release(string id, string version, string loader, string channel, string date) =>
    new(id, "project", id, DateTimeOffset.Parse(date), channel, [version], [loader], [], []);
