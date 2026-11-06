using System.IO;
using System.Net.Http;
using System.Net.Http.Json;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Serialization;
using System.Text.RegularExpressions;

namespace Veylo.Launcher;

internal sealed record ModrinthProject([property: JsonPropertyName("project_id")] string Id,
    string Title, string Description, [property: JsonPropertyName("client_side")] string ClientSide);

internal sealed record ModrinthVersion(string Id, [property: JsonPropertyName("project_id")] string ProjectId,
    [property: JsonPropertyName("version_number")] string Number,
    [property: JsonPropertyName("date_published")] DateTimeOffset Published,
    [property: JsonPropertyName("version_type")] string Channel,
    [property: JsonPropertyName("game_versions")] string[] GameVersions, string[] Loaders,
    ModrinthFile[] Files, ModrinthDependency[] Dependencies)
{
    public bool Compatible(string game, string loader) => GameVersions.Contains(game) && Loaders.Contains(loader);
    public bool IsUpdateFor(ModrinthVersion current, string game, string loader) =>
        ProjectId == current.ProjectId && Id != current.Id && Published > current.Published &&
        Stability(Channel) >= Stability(current.Channel) && Compatible(game, loader);

    internal static int Stability(string channel) => channel switch { "release" => 2, "beta" => 1, _ => 0 };

    public ModrinthFile? Jar => Files.Where(file => file.Filename.EndsWith(".jar", StringComparison.OrdinalIgnoreCase))
        .OrderByDescending(file => file.Primary).FirstOrDefault();
}

internal sealed record ModrinthFile(string Url, string Filename, bool Primary, long Size, Dictionary<string, string> Hashes);
internal sealed record ModrinthDependency([property: JsonPropertyName("project_id")] string? ProjectId,
    [property: JsonPropertyName("version_id")] string? VersionId,
    [property: JsonPropertyName("dependency_type")] string Type);

internal sealed class Modrinth
{
    internal sealed record SearchResult(ModrinthProject Project, ModrinthVersion Version)
    {
        public string Summary => Version.Number + " · " + Project.Description;
    }

    internal sealed record Page(SearchResult[] Results, bool HasMore);
    internal sealed record Installed(ModManager.Entry Entry, ModrinthVersion Version);
    internal sealed record Update(Installed Current, ModrinthVersion Version)
    {
        public override string ToString() =>
            $"{Path.GetFileName(Current.Entry.Path)}  ·  {Current.Version.Number} → {Version.Number}";
    }

    public const int PageSize = 12;
    private const long MaxJarSize = 128 * 1024 * 1024;
    private const string Loader = "fabric";
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private static readonly HttpClient PublicApi = CreateHttpClient();
    private readonly HttpClient http;

    public Modrinth() : this(PublicApi) {}
    internal Modrinth(HttpClient http) => this.http = http;

    private static HttpClient CreateHttpClient()
    {
        var client = new HttpClient(new HttpClientHandler { AllowAutoRedirect = false })
        {
            BaseAddress = new Uri("https://api.modrinth.com/v2/"),
            Timeout = TimeSpan.FromSeconds(60)
        };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Veylo-Client/0.5.0 (+https://github.com/fabiohackt3-blip/veylo-client)");
        return client;
    }

    public async Task<Page> Search(string query, string game, int offset, CancellationToken token)
    {
        var facets = new string[][] { ["project_type:mod"], ["categories:fabric"], [$"versions:{game}"] };
        var request = $"search?query={Uri.EscapeDataString(query)}&index={(query.Length == 0 ? "downloads" : "relevance")}" +
            $"&facets={Uri.EscapeDataString(JsonSerializer.Serialize(facets))}&limit={PageSize}&offset={offset}";
        var page = await Get<SearchResponse>(request, token);
        var results = await Task.WhenAll(page.Hits
            .Where(project => project.ClientSide != "unsupported")
            .Select(async project => (Project: project, Version: Latest(await Versions(project.Id, game, token)))));
        return new Page(results.Where(result => result.Version is not null)
            .Select(result => new SearchResult(result.Project, result.Version!)).ToArray(),
            offset + PageSize < page.TotalHits);
    }

    private sealed record SearchResponse(ModrinthProject[] Hits, [property: JsonPropertyName("total_hits")] int TotalHits);

    public async Task<Update[]> Updates(string folder, string game, CancellationToken token)
    {
        var installed = await Scan(folder, token);
        using var slots = new SemaphoreSlim(6);
        var results = await Task.WhenAll(installed.Where(item => !item.Entry.Bundled &&
            item.Version.Compatible(game, Loader)).Select(async item =>
        {
            await slots.WaitAsync(token);
            try
            {
                var versions = await Versions(item.Version.ProjectId, game, token);
                var candidate = versions.Where(version => version.IsUpdateFor(item.Version, game, Loader))
                    .OrderByDescending(version => version.Published).FirstOrDefault();
                return candidate is null ? null : new Update(item, candidate);
            }
            finally { slots.Release(); }
        }));
        return results.OfType<Update>().ToArray();
    }

    private async Task<Installed[]> Scan(string folder, CancellationToken token)
    {
        var matches = new List<Installed>();
        var files = new List<(ModManager.Entry Entry, string Hash)>();
        foreach (var entry in ModManager.List(folder))
        {
            token.ThrowIfCancellationRequested();
            if (new FileInfo(entry.Path).Length > MaxJarSize) continue;
            await using var input = File.OpenRead(entry.Path);
            files.Add((entry, Convert.ToHexString(await SHA512.HashDataAsync(input, token)).ToLowerInvariant()));
        }
        foreach (var batch in files.Chunk(50))
        {
            using var response = await http.PostAsJsonAsync("version_files", new
            {
                hashes = batch.Select(file => file.Hash).Distinct().ToArray(),
                algorithm = "sha512"
            }, token);
            response.EnsureSuccessStatusCode();
            var versions = await response.Content.ReadFromJsonAsync<Dictionary<string, ModrinthVersion>>(Json, token)
                ?? throw new InvalidDataException("Modrinth lieferte keine Dateiinformationen.");
            matches.AddRange(batch.Where(file => versions.ContainsKey(file.Hash))
                .Select(file => new Installed(file.Entry, versions[file.Hash])));
        }
        return matches.ToArray();
    }

    public async Task<int> Install(string folder, string game, ModrinthVersion selected, Update? update,
        CancellationToken token, bool bundledFabricApi = false)
    {
        if (!selected.Compatible(game, Loader) || selected.Jar is null)
            throw new InvalidDataException("Diese Datei passt nicht zur Minecraft-Version und zum Modloader.");
        if (bundledFabricApi && selected.ProjectId == "P7dR8mSH")
            throw new InvalidDataException("Fabric API wird in dieser Veylo-Instanz mitgeliefert.");

        var parent = Directory.GetParent(folder)!.FullName;
        Directory.CreateDirectory(parent);
        using var profileLock = new FileStream(Path.Combine(parent, ".veylo-modrinth.lock"),
            FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        var existing = await Scan(folder, token);
        var current = FindInstalled(selected.ProjectId);
        if (update is null && current is not null)
            throw new InvalidDataException("Diese Mod ist bereits installiert. Verwende „Updates prüfen“.");
        if (update is not null && (current is null || current.Entry.Bundled ||
            current.Version.Id != update.Current.Version.Id || !selected.IsUpdateFor(current.Version, game, Loader)))
            throw new InvalidDataException("Die installierte Version hat sich geändert. Prüfe Updates erneut.");

        var plan = new Dictionary<string, ModrinthVersion>();
        await Add(selected);
        var staging = Path.Combine(parent, ".modrinth-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(staging);
        bool preserveBackups = false;
        try
        {
            var changes = new List<(string Temporary, string Target, string? Backup)>();
            foreach (var version in plan.Values)
            {
                var installed = FindInstalled(version.ProjectId);
                if (installed?.Version.Id == version.Id) continue;
                if (installed?.Entry.Bundled == true)
                    throw new InvalidDataException("Eine mitgelieferte System-Mod kann hier nicht ersetzt werden.");
                var id = SafeId(version.ProjectId);
                string temporary = Path.Combine(staging, id + ".download");
                await Download(version.Jar!, temporary, token);
                string modId = ModManager.ReadFabricId(temporary);
                if (modId == "veylo" || modId == "fabric-api" && bundledFabricApi)
                    throw new InvalidDataException("Diese System-Mod wird bereits mit Veylo geliefert.");
                string target = installed?.Entry.Path ?? Path.Combine(folder, $"modrinth-{id}.jar");
                if (installed is null && (File.Exists(target) || File.Exists(target + ".disabled") ||
                    ModManager.List(folder).Any(entry => ModManager.TryReadFabricId(entry.Path) == modId)))
                    throw new InvalidDataException($"Die Mod-ID „{modId}“ ist bereits in dieser Instanz vorhanden.");
                string? backup = installed is null ? null : Path.Combine(staging, id + ".backup");
                if (backup is not null) File.Copy(target, backup);
                changes.Add((temporary, target, backup));
            }
            token.ThrowIfCancellationRequested();
            Directory.CreateDirectory(folder);
            var applied = new List<(string Target, string? Backup)>();
            try
            {
                foreach (var change in changes)
                {
                    File.Move(change.Temporary, change.Target, change.Backup is not null);
                    applied.Add((change.Target, change.Backup));
                }
            }
            catch
            {
                var rollbackErrors = new List<Exception>();
                foreach (var change in applied.AsEnumerable().Reverse())
                {
                    try
                    {
                        if (change.Backup is null) File.Delete(change.Target);
                        else File.Move(change.Backup, change.Target, overwrite: true);
                    }
                    catch (Exception error) when (error is IOException or UnauthorizedAccessException)
                    { rollbackErrors.Add(error); }
                }
                if (rollbackErrors.Count > 0)
                {
                    preserveBackups = true;
                    throw new InvalidDataException("Mod-Änderung konnte nicht vollständig zurückgesetzt werden. Sicherungen liegen in " + staging,
                        new AggregateException(rollbackErrors));
                }
                throw;
            }
            return changes.Count;
        }
        finally { if (!preserveBackups) Directory.Delete(staging, recursive: true); }

        Installed? FindInstalled(string project)
        {
            var matches = existing.Where(item => item.Version.ProjectId == project).Take(2).ToArray();
            if (matches.Length > 1) throw new InvalidDataException("Für eine Mod sind mehrere JARs installiert.");
            return matches.FirstOrDefault();
        }

        async Task Add(ModrinthVersion version)
        {
            SafeId(version.ProjectId);
            if (!version.Compatible(game, Loader) || version.Jar is null)
                throw new InvalidDataException("Eine benötigte Mod passt nicht zur Instanz.");
            if (plan.TryGetValue(version.ProjectId, out var planned))
            {
                if (planned.Id != version.Id) throw new InvalidDataException("Zwei Mods benötigen unterschiedliche Versionen derselben Abhängigkeit.");
                return;
            }
            if (plan.Count >= 64) throw new InvalidDataException("Die Mod hat zu viele Abhängigkeiten.");
            plan.Add(version.ProjectId, version);
            foreach (var dependency in version.Dependencies.Where(item => item.Type == "required"))
            {
                if (dependency.ProjectId == "P7dR8mSH" && bundledFabricApi)
                {
                    if (dependency.VersionId is not null)
                    {
                        var bundled = FindInstalled(dependency.ProjectId);
                        var supplied = bundled?.Version.Id;
                        if (supplied is null)
                        {
                            var apiJar = Path.Combine(AppContext.BaseDirectory, "mods", "fabric-api.jar");
                            await using var stream = File.OpenRead(apiJar);
                            var hash = Convert.ToHexString(await SHA512.HashDataAsync(stream, token)).ToLowerInvariant();
                            using var response = await http.GetAsync($"version_file/{hash}?algorithm=sha512", token);
                            if (response.IsSuccessStatusCode)
                                supplied = (await response.Content.ReadFromJsonAsync<ModrinthVersion>(Json, token))?.Id;
                        }
                        if (supplied != dependency.VersionId)
                            throw new InvalidDataException("Diese Mod benötigt eine andere Fabric-API-Version. Verwende eine separate Fabric-Instanz.");
                    }
                    continue;
                }
                ModrinthVersion? required;
                if (dependency.VersionId is { } pinned)
                {
                    required = await Get<ModrinthVersion>($"version/{SafeId(pinned)}", token);
                    if (dependency.ProjectId is { } expected && required.ProjectId != expected)
                        throw new InvalidDataException("Modrinth lieferte widersprüchliche Abhängigkeiten.");
                }
                else if (dependency.ProjectId is { } project)
                {
                    required = plan.GetValueOrDefault(project) ?? FindInstalled(project)?.Version;
                    if (required?.Compatible(game, Loader) != true)
                        required = Latest(await Versions(project, game, token));
                }
                else required = null;
                if (required is null)
                    throw new InvalidDataException("Für eine notwendige Abhängigkeit wurde keine passende Version gefunden.");
                var installedDependency = FindInstalled(required.ProjectId);
                if (installedDependency?.Entry.Enabled == false)
                    throw new InvalidDataException($"Aktiviere zuerst die benötigte Mod {Path.GetFileName(installedDependency.Entry.Path)}.");
                if (installedDependency is not null && installedDependency.Version.Id != required.Id &&
                    (!required.IsUpdateFor(installedDependency.Version, game, Loader) ||
                    existing.Any(item => item.Entry.Enabled && !plan.ContainsKey(item.Version.ProjectId) &&
                        item.Version.Dependencies.Any(dep => dep.Type == "required" && dep.VersionId == installedDependency.Version.Id))))
                    throw new InvalidDataException($"Die installierte Abhängigkeit {Path.GetFileName(installedDependency.Entry.Path)} passt nicht zur angeforderten Version.");
                await Add(required);
            }
        }
    }

    private async Task Download(ModrinthFile file, string destination, CancellationToken token)
    {
        if (!Uri.TryCreate(file.Url, UriKind.Absolute, out var uri) || uri.Scheme != Uri.UriSchemeHttps ||
            uri.Host != "cdn.modrinth.com" || !uri.IsDefaultPort || uri.UserInfo.Length != 0 ||
            file.Size is < 1 or > MaxJarSize || !file.Hashes.TryGetValue("sha512", out var expected) ||
            !Regex.IsMatch(expected, @"\A[0-9a-fA-F]{128}\z"))
            throw new InvalidDataException("Modrinth lieferte ungültige Download-Daten.");
        using var response = await http.GetAsync(uri, HttpCompletionOption.ResponseHeadersRead, token);
        response.EnsureSuccessStatusCode();
        await using var input = await response.Content.ReadAsStreamAsync(token);
        await using var output = File.Create(destination);
        using var digest = IncrementalHash.CreateHash(HashAlgorithmName.SHA512);
        var buffer = new byte[81920];
        long length = 0;
        int read;
        while ((read = await input.ReadAsync(buffer, token)) != 0)
        {
            length += read;
            if (length > file.Size) throw new InvalidDataException("Die Mod-Datei überschreitet ihre erwartete Größe.");
            digest.AppendData(buffer, 0, read);
            await output.WriteAsync(buffer.AsMemory(0, read), token);
        }
        if (length != file.Size || !Convert.ToHexString(digest.GetHashAndReset()).Equals(expected, StringComparison.OrdinalIgnoreCase))
            throw new InvalidDataException("Die Mod-Datei ist unvollständig oder ihre Prüfsumme stimmt nicht.");
    }

    private static ModrinthVersion? Latest(IEnumerable<ModrinthVersion> versions) =>
        versions.OrderByDescending(version => ModrinthVersion.Stability(version.Channel))
            .ThenByDescending(version => version.Published).FirstOrDefault();

    private async Task<ModrinthVersion[]> Versions(string id, string game, CancellationToken token)
    {
        var versions = await Get<ModrinthVersion[]>($"project/{SafeId(id)}/version?loaders=%5B%22fabric%22%5D" +
            $"&game_versions={Uri.EscapeDataString(JsonSerializer.Serialize(new[] { game }))}&include_changelog=false", token);
        return versions.Where(version => version.ProjectId == id && version.Compatible(game, Loader) && version.Jar is not null).ToArray();
    }

    private async Task<T> Get<T>(string path, CancellationToken token) where T : class =>
        await http.GetFromJsonAsync<T>(path, Json, token)
        ?? throw new InvalidDataException("Modrinth lieferte keine Daten.");

    private static string SafeId(string id) => Regex.IsMatch(id, @"\A[a-zA-Z0-9]{1,32}\z")
        ? id : throw new InvalidDataException("Modrinth lieferte eine ungültige ID.");
}
