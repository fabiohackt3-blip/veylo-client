using System.Text.Json;
using System.IO.Compression;
using System.Net;
using System.Net.Http.Json;
using System.Security.Cryptography;
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

await CheckInstallAndUpdate();
Console.WriteLine("Instance migration, Modrinth update rules, and checksum-safe file replacement passed.");

static async Task CheckInstallAndUpdate()
{
    string path = Path.Combine(Path.GetTempPath(), "veylo-download-" + Guid.NewGuid().ToString("N"));
    string folder = Path.Combine(path, "mods");
    var oldJar = Jar("1.0.0");
    var newJar = Jar("1.0.1");
    var oldRelease = WithFile(Release("old", "26.3", "fabric", "release", "2026-10-01"), oldJar);
    var newRelease = WithFile(Release("new", "26.3", "fabric", "release", "2026-10-02"), newJar);
    bool corrupt = false;
    using var http = new HttpClient(new FixtureHttp(request =>
    {
        string url = request.RequestUri!.AbsolutePath;
        var response = new HttpResponseMessage(HttpStatusCode.OK);
        response.Content = url switch
        {
            "/v2/version_files" => JsonContent.Create(new Dictionary<string, ModrinthVersion>
            {
                [Hash(oldJar)] = oldRelease
            }),
            "/data/old.jar" => new ByteArrayContent(oldJar),
            "/data/new.jar" => new ByteArrayContent(corrupt ? new byte[newJar.Length] : newJar),
            _ => throw new Exception("Unexpected fixture request " + url)
        };
        return response;
    })) { BaseAddress = new Uri("https://api.modrinth.com/v2/") };
    var catalog = new Modrinth(http);
    try
    {
        Assert(await catalog.Install(folder, "26.3", oldRelease, null, default) == 1, "verified mod installs");
        var entry = ModManager.List(folder).Single();
        ModManager.Toggle(entry);
        entry = ModManager.List(folder).Single();
        var update = new Modrinth.Update(new Modrinth.Installed(entry, oldRelease), newRelease);
        corrupt = true;
        try
        {
            await catalog.Install(folder, "26.3", newRelease, update, default);
            throw new Exception("corrupt download was accepted");
        }
        catch (InvalidDataException) { }
        Assert(File.ReadAllBytes(entry.Path).SequenceEqual(oldJar), "corrupt update preserves old JAR");
        corrupt = false;
        Assert(await catalog.Install(folder, "26.3", newRelease, update, default) == 1, "compatible update succeeds");
        Assert(File.ReadAllBytes(entry.Path).SequenceEqual(newJar), "update preserves disabled state and writes the new JAR");
    }
    finally { if (Directory.Exists(path)) Directory.Delete(path, recursive: true); }

    static string Hash(byte[] bytes) => Convert.ToHexString(SHA512.HashData(bytes)).ToLowerInvariant();
    static ModrinthVersion WithFile(ModrinthVersion version, byte[] bytes) => version with
    {
        Files = [new($"https://cdn.modrinth.com/data/{version.Id}.jar", version.Id + ".jar", true, bytes.Length,
            new() { ["sha512"] = Hash(bytes) })]
    };
    static byte[] Jar(string version)
    {
        using var bytes = new MemoryStream();
        using (var zip = new ZipArchive(bytes, ZipArchiveMode.Create, leaveOpen: true))
        using (var writer = new StreamWriter(zip.CreateEntry("fabric.mod.json").Open()))
            writer.Write(JsonSerializer.Serialize(new { id = "test_mod", version }));
        return bytes.ToArray();
    }
}

static void Assert(bool value, string message)
{
    if (!value) throw new Exception(message);
}

static ModrinthVersion Release(string id, string version, string loader, string channel, string date) =>
    new(id, "project", id, DateTimeOffset.Parse(date), channel, [version], [loader], [], []);


sealed class FixtureHttp(Func<HttpRequestMessage, HttpResponseMessage> respond) : HttpMessageHandler
{
    protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token)
    {
        token.ThrowIfCancellationRequested();
        return Task.FromResult(respond(request));
    }
}
