using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Text.Json;
using Microsoft.Win32;
using CmlLib.Core.Auth;
using CmlLib.Core.Auth.Microsoft;
using CmlLib.Core.Auth.Microsoft.Sessions;
using XboxAuthNet.Game.Accounts;

namespace Veylo.Launcher;

public partial class MainWindow : Window
{
    private readonly ObservableCollection<Profile> profiles = [];
    private readonly Dictionary<Guid, Process> running = [];
    private readonly Modrinth modrinth = new();
    private sealed record InstanceTile(Profile Profile, ImageSource Cover, bool Active);
    private int modOffset;
    private string modSearch = "";
    private readonly ProfileStore store;
    private readonly GameInstaller installer;
    private readonly JELoginHandler login;
    private bool ready;
    private bool busy;
    private bool syncing;
    private MSession? session;
    private readonly string root;
    private readonly DiscordPresence presence = new();
    private CancellationTokenSource? installation;
    private IReadOnlyList<GameVersion> versions = [new("26.3"), GameVersion.Default, new("1.20.1"), new("1.16.5"), new("1.12.2"), new("1.8.9")];
    private bool loadingVersions;
    private string? startingVersion;
    private readonly Dictionary<Guid, (string Version, DateTime Started)> runningPresence = [];
    private sealed record DiscordSettings(bool Enabled, string ApplicationId);

    public MainWindow()
    {
        root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Veylo");
        store = new ProfileStore(root);
        installer = new GameInstaller(root);
        login = new JELoginHandlerBuilder()
            .WithAccountManager(new InMemoryXboxGameAccountManager(JEGameAccount.FromSessionStorage))
            .Build();
        InitializeComponent();
        ProfilePicker.ItemsSource = profiles;

        ShowPage("Home");
        ReadDiscordSettings();
        try
        {
            foreach (var profile in store.Load()) profiles.Add(profile);
            store.Save(profiles);
            ready = true;
            ProfilePicker.SelectedIndex = 0;
            RefreshRecentInstances();
            FilterNewVersions(this, new RoutedEventArgs());
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or InvalidDataException or System.Text.Json.JsonException)
        {
            Status.Text = "Profile konnten nicht geladen werden. Prüfe profiles.json im Veylo-Ordner.";
        }
        RefreshActions();
        _ = LoadVersions();
    }

    private async void SignIn(object sender, RoutedEventArgs e)
    {
        busy = true;
        RefreshActions();
        Status.Text = "Microsoft-Anmeldung wird geöffnet …";
        try
        {
            session = await login.AuthenticateInteractively();
            Account.Text = session.Username;
            Status.Text = "Angemeldet.";
        }
        catch (OperationCanceledException)
        {
            Status.Text = "Anmeldung abgebrochen.";
        }
        catch (Exception)
        {
            Status.Text = "Anmeldung fehlgeschlagen. Prüfe dein Microsoft-Konto, Minecraft Java und WebView2.";
        }
        finally
        {
            busy = false;
            RefreshActions();
        }
    }

    private async void Play(object sender, RoutedEventArgs e)
    {
        if (busy || session is null || ProfilePicker.SelectedItem is not Profile profile || IsRunning(profile)) return;
        busy = true;
        using var operation = new CancellationTokenSource(TimeSpan.FromMinutes(15));
        installation = operation;
        RefreshActions();
        string version = profile.VersionId;
        startingVersion = version;
        UpdatePresence();
        try
        {
            ShowProgress(new("Installation wird vorbereitet …"));
            var progress = new Progress<InstallProgress>(update =>
            {
                if (installation == operation && !operation.IsCancellationRequested)
                    ShowProgress(update);
            });
            var process = await installer.Start(profile, session, progress, operation.Token);
            running[profile.Id] = process;
            runningPresence[profile.Id] = (version, DateTime.UtcNow);
            startingVersion = null;
            UpdatePresence();
            profile.LastPlayedAt = DateTimeOffset.UtcNow;
            bool historySaved = Save(profiles);
            RefreshRecentInstances();
            string gameStatus = profile.UseVeylo ? "Minecraft läuft. Rechte Shift öffnet Veylo." : $"Minecraft läuft in {profile.Name}.";
            Status.Text = historySaved ? gameStatus : gameStatus + " Spielverlauf konnte nicht gespeichert werden.";
            _ = ObserveGame(profile.Id, process);
        }
        catch (OperationCanceledException)
        {
            Status.Text = "Installation abgebrochen oder Zeitlimit erreicht. Mit Spielen erneut versuchen.";
        }
        catch (InvalidDataException error)
        {
            Status.Text = error.Message;
        }
        catch (Exception error)
        {
            Status.Text = $"Start fehlgeschlagen ({error.GetType().Name}). Prüfe Internet und verfügbaren Speicher.";
        }
        finally
        {
            installation = null;
            DownloadProgress.Visibility = Visibility.Collapsed;
            startingVersion = null;
            UpdatePresence();
            busy = false;
            RefreshActions();
        }
    }

    private async Task ObserveGame(Guid profileId, Process process)
    {
        try
        {
            await process.WaitForExitAsync();
            Status.Text = process.ExitCode == 0 ? "Minecraft wurde geschlossen." :
                "Minecraft wurde mit einem Fehler beendet. Prüfe logs/latest.log im Profilordner.";
        }
        catch (InvalidOperationException)
        {
            Status.Text = "Der Minecraft-Prozess ist nicht mehr verfügbar.";
        }
        finally
        {
            running.Remove(profileId);
            runningPresence.Remove(profileId);
            UpdatePresence();
            process.Dispose();
            RefreshActions();
        }
    }

    private async void AddProfile(object sender, RoutedEventArgs e)
    {
        if (busy || NewVersion.SelectedItem is not GameVersion version || NewLoader.SelectedIndex < 0) return;
        var name = ProfileName.Text.Trim();
        if (name.Length == 0 || profiles.Any(profile => profile.Name.Equals(name, StringComparison.OrdinalIgnoreCase)))
        {
            Status.Text = "Gib einen neuen Instanznamen ein.";
            return;
        }
        bool veylo = NewLoader.SelectedIndex == 2 && version.Id == GameVersion.Default.Id;
        var profile = new Profile(Guid.NewGuid(), name)
        {
            VersionId = version.Id,
            UseVeylo = veylo,
            Loader = NewLoader.SelectedIndex == 1 ? "fabric" : "vanilla"
        };
        if (profile.Mode == "fabric")
        {
            busy = true;
            RefreshActions();
            try
            {
                Status.Text = $"Fabric für Minecraft {version.Id} wird geprüft …";
                using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(20));
                await GameInstaller.StableFabricLoader(version.Id, timeout.Token);
            }
            catch (Exception error)
            {
                Status.Text = error is InvalidDataException ? error.Message : "Fabric konnte nicht geprüft werden. Prüfe deine Internetverbindung.";
                return;
            }
            finally
            {
                busy = false;
                RefreshActions();
            }
        }
        if (!Save(profiles.Append(profile))) return;
        profiles.Add(profile);
        ProfilePicker.SelectedItem = profile;
        ProfileName.Clear();
        Status.Text = "Instanz erstellt.";
    }

    private void RefreshInstanceCards(Profile selected)
    {
        ManageProfiles.ItemsSource = profiles.Select(profile =>
            new InstanceTile(profile, InstanceCover.Load(root, profile), profile.Id == selected.Id)).ToArray();
        SelectedInstance.Text = selected.Name + "  ·  " + selected.Details;
    }

    private void ChangeCover(object sender, RoutedEventArgs e)
    {
        if (ProfilePicker.SelectedItem is not Profile profile) return;
        var dialog = new OpenFileDialog { Filter = "Bilder (*.png;*.jpg;*.jpeg;*.bmp)|*.png;*.jpg;*.jpeg;*.bmp" };
        if (dialog.ShowDialog(this) != true) return;
        string? previous = profile.CoverFile;
        try
        {
            profile.CoverFile = InstanceCover.Save(root, profile, dialog.FileName);
            if (!Save(profiles)) { profile.CoverFile = previous; return; }
            RefreshInstanceCards(profile);
            Status.Text = "Instanzbild gespeichert.";
        }
        catch (Exception error) when (error is IOException or InvalidDataException or UnauthorizedAccessException
            or NotSupportedException or FormatException or ArgumentException)
        {
            Status.Text = error is InvalidDataException ? error.Message : "Das Bild konnte nicht geöffnet werden.";
        }
    }

    private void ResetCover(object sender, RoutedEventArgs e)
    {
        if (ProfilePicker.SelectedItem is not Profile profile) return;
        string? previous = profile.CoverFile;
        profile.CoverFile = null;
        if (!Save(profiles)) { profile.CoverFile = previous; return; }
        RefreshInstanceCards(profile);
        Status.Text = "Standardbild wiederhergestellt.";
    }

    private void FilterNewVersions(object sender, RoutedEventArgs e)
    {
        if (!ready) return;
        string id = (NewVersion.SelectedItem as GameVersion)?.Id ?? GameVersion.Default.Id;
        var visible = versions.Where(item => !item.Snapshot || NewSnapshots.IsChecked == true).ToArray();
        NewVersion.ItemsSource = visible;
        NewVersion.SelectedItem = visible.FirstOrDefault(item => item.Id == id) ?? visible.FirstOrDefault();
    }

    private void NewVersionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (NewVeyloChoice is null || NewLoader is null) return;
        NewVeyloChoice.IsEnabled = (NewVersion.SelectedItem as GameVersion)?.Id == GameVersion.Default.Id;
        if (!NewVeyloChoice.IsEnabled && NewLoader.SelectedIndex == 2) NewLoader.SelectedIndex = 1;
    }

    private void ProfileChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!ready || ProfilePicker.SelectedItem is not Profile profile) return;
        syncing = true;
        RefreshInstanceCards(profile);
        ResetModView();
        Ram.Value = profile.RamMb;
        ApplyVersionFilter(profile);
        RefreshMods();
        RamLabel.Text = $"Arbeitsspeicher  ·  {profile.RamMb / 1024d:0.#} GB";
        syncing = false;
        RefreshActions();
    }

    private void RamChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (!ready || syncing || ProfilePicker.SelectedItem is not Profile profile) return;
        var previous = profile.RamMb;
        profile.RamMb = (int)e.NewValue;
        if (!Save(profiles))
        {
            profile.RamMb = previous;
            syncing = true;
            Ram.Value = previous;
            syncing = false;
        }
        RamLabel.Text = $"Arbeitsspeicher  ·  {profile.RamMb / 1024d:0.#} GB";
    }

    private bool Save(IEnumerable<Profile> items)
    {
        try
        {
            store.Save(items);
            return true;
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Status.Text = "Profile konnten nicht gespeichert werden.";
            return false;
        }
    }

    private bool IsRunning(Profile profile) =>
        running.TryGetValue(profile.Id, out var process) && !process.HasExited;

    private void RefreshActions()
    {
        ProfilePanel.IsEnabled = ready && !busy;
        SignInButton.IsEnabled = ready && !busy;
        SignOutButton.IsEnabled = !busy && session is not null;
        ProfilePicker.IsEnabled = ManageProfiles.IsEnabled = RecentInstances.IsEnabled = ready && !busy;
        VersionControls.IsEnabled = ready && !busy && ProfilePicker.SelectedItem is Profile p && !IsRunning(p);
        ModsControls.IsEnabled = VersionControls.IsEnabled && ProfilePicker.SelectedItem is Profile { HasMods: true };
        CancelButton.Visibility = installation is null ? Visibility.Collapsed : Visibility.Visible;
        PlayButton.IsEnabled = ready && !busy && session is not null &&
                               ProfilePicker.SelectedItem is Profile profile && !IsRunning(profile);
    }

    private void Navigate(object sender, RoutedEventArgs e)
    {
        if (sender is Button { Tag: string tag }) ShowPage(tag);
    }

    private void ShowPage(string page)
    {
        foreach (var (element, name) in new[]
        {
            (HomePage, "Home"), (VersionsPage, "Versions"), (ModsPage, "Mods"),
            (ProfilesPage, "Profiles"), (AccountsPage, "Accounts"), (SettingsPage, "Settings")
        })
            element.Visibility = name == page ? Visibility.Visible : Visibility.Collapsed;
        foreach (var button in Navigation.Children.OfType<Button>())
            button.Background = (Brush)new BrushConverter().ConvertFromString(
                Equals(button.Tag, page) ? "#21445D" : "#111C26")!;
        PageScroll.ScrollToTop();
        if (page == "Mods") RefreshMods();
    }

    private void OpenInstanceCard(object sender, RoutedEventArgs e)
    {
        if (!ready || busy || sender is not Button { CommandParameter: Profile profile }) return;
        ProfilePicker.SelectedItem = profile;
        SelectedInstance.BringIntoView();
    }

    private void OpenRecentInstance(object sender, RoutedEventArgs e)
    {
        if (!ready || busy || sender is not Button { CommandParameter: Profile profile }) return;
        ProfilePicker.SelectedItem = profile;
        ShowPage("Home");
    }

    private void RefreshRecentInstances()
    {
        var recent = ProfileStore.RecentlyPlayed(profiles);
        RecentInstances.ItemsSource = recent;
        RecentSection.Visibility = recent.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
    }

    private async void ReloadVersions(object sender, RoutedEventArgs e) => await LoadVersions();

    private async Task LoadVersions()
    {
        if (loadingVersions) return;
        loadingVersions = true;
        try
        {
            versions = await VersionCatalog.Fetch();
            VersionInfo.Text = "Offizielle Versionsliste geladen.";
        }
        catch (Exception)
        {
            VersionInfo.Text = "Versionsliste nicht erreichbar. Die verfügbare Liste bleibt auswählbar.";
        }
        finally
        {
            loadingVersions = false;
            if (ProfilePicker.SelectedItem is Profile profile) ApplyVersionFilter(profile);
            FilterNewVersions(this, new RoutedEventArgs());
        }
    }

    private void FilterVersions(object sender, RoutedEventArgs e)
    {
        if (ready && !syncing && ProfilePicker.SelectedItem is Profile profile) ApplyVersionFilter(profile);
    }

    private void ApplyVersionFilter(Profile profile)
    {
        bool wasSyncing = syncing;
        syncing = true;
        var visible = versions.Where(item => !item.Snapshot || Snapshots.IsChecked == true ||
            item.Id == profile.VersionId).ToList();
        if (visible.All(item => item.Id != profile.VersionId)) visible.Insert(0, new(profile.VersionId));
        VersionPicker.ItemsSource = visible;
        VersionPicker.SelectedItem = visible.First(item => item.Id == profile.VersionId);
        VeyloChoice.IsEnabled = profile.VersionId == GameVersion.Default.Id;
        LoaderPicker.SelectedIndex = profile.Mode switch { "veylo" => 2, "fabric" => 1, _ => 0 };
        HomeVersion.Content = profile.Details;
        syncing = wasSyncing;
    }

    private void VersionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!ready || syncing || busy || VersionPicker.SelectedItem is not GameVersion version ||
            ProfilePicker.SelectedItem is not Profile profile || IsRunning(profile)) return;
        SelectVersion(profile, version.Id, profile.Mode == "veylo" && version.Id != GameVersion.Default.Id ? "fabric" : profile.Mode);
    }

    private void LoaderChanged(object sender, RoutedEventArgs e)
    {
        if (!ready || syncing || busy || ProfilePicker.SelectedItem is not Profile profile ||
            IsRunning(profile) || LoaderPicker.SelectedIndex < 0) return;
        string mode = LoaderPicker.SelectedIndex switch { 2 when profile.VersionId == GameVersion.Default.Id => "veylo",
            1 => "fabric", _ => "vanilla" };
        SelectVersion(profile, profile.VersionId, mode);
    }

    private void SelectVersion(Profile profile, string version, string mode)
    {
        string oldVersion = profile.VersionId;
        bool oldVeylo = profile.UseVeylo;
        string oldLoader = profile.Loader;
        profile.VersionId = version;
        profile.UseVeylo = mode == "veylo";
        profile.Loader = mode == "fabric" ? "fabric" : "vanilla";
        if (!Save(profiles))
        {
            profile.VersionId = oldVersion;
            profile.UseVeylo = oldVeylo;
            profile.Loader = oldLoader;
        }
        ApplyVersionFilter(profile);
        RefreshInstanceCards(profile);
        ResetModView();
        RefreshMods();
        RefreshActions();
    }

    private string? ModsFolder() => ProfilePicker.SelectedItem is Profile { HasMods: true } profile
        ? Path.Combine(installer.GamePath(profile), "mods") : null;

    private void RefreshMods()
    {
        var folder = ModsFolder();
        var profile = ProfilePicker.SelectedItem as Profile;
        ModsTitle.Text = profile is null ? "Instanz-Mods" : profile.Name + " · Mods";
        ModsInfo.Text = folder is null ? "Öffne eine Fabric-Instanz oder wähle Fabric unter Versionen." :
            $"Minecraft {profile!.VersionId} · Fabric. Die Modrinth-Suche und Updates sind auf diese Instanz gefiltert.";
        try
        {
            ModList.ItemsSource = folder is null ? null : ModManager.List(folder).ToArray();
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            ModList.ItemsSource = null;
            Status.Text = "Der Mod-Ordner konnte nicht gelesen werden.";
        }
    }

    private void ResetModView()
    {
        modOffset = 0;
        modSearch = "";
        ModQuery.Clear();
        SearchResults.ItemsSource = null;
        PreviousMods.IsEnabled = false;
        NextMods.IsEnabled = false;
        ModPageInfo.Text = "Leere Suche zeigt beliebte Mods.";
        ShowUpdates([]);
    }

    private async void SearchMods(object sender, RoutedEventArgs e) => await SearchModPage(ModQuery.Text.Trim(), 0);

    private async void ModQueryKeyDown(object sender, System.Windows.Input.KeyEventArgs e)
    {
        if (e.Key != System.Windows.Input.Key.Enter) return;
        e.Handled = true;
        await SearchModPage(ModQuery.Text.Trim(), 0);
    }

    private async void PreviousModPage(object sender, RoutedEventArgs e) =>
        await SearchModPage(modSearch, Math.Max(0, modOffset - Modrinth.PageSize));

    private async void NextModPage(object sender, RoutedEventArgs e) =>
        await SearchModPage(modSearch, modOffset + Modrinth.PageSize);

    private Task SearchModPage(string query, int offset) => RunModOperation("Modrinth wird durchsucht …",
        async (profile, _, token) =>
        {
            var page = await modrinth.Search(query, profile.VersionId, offset, token);
            modOffset = offset;
            modSearch = query;
            SearchResults.ItemsSource = page.Results;
            if (page.Results.Length > 0) SearchResults.SelectedIndex = 0;
            PreviousMods.IsEnabled = offset > 0;
            NextMods.IsEnabled = page.HasMore;
            ModPageInfo.Text = page.Results.Length == 0 ? "Keine passenden Mods auf dieser Seite" :
                $"Seite {offset / Modrinth.PageSize + 1}";
            return page.Results.Length == 0 ? "Auf dieser Seite passen keine Mods zu deiner Instanz." :
                $"{page.Results.Length} kompatible Mods auf dieser Seite.";
        });

    private async void InstallModrinth(object sender, RoutedEventArgs e)
    {
        if (SearchResults.SelectedItem is not Modrinth.SearchResult result)
        {
            Status.Text = "Wähle eine Mod aus den Suchergebnissen.";
            return;
        }
        await RunModOperation($"{result.Project.Title} wird heruntergeladen und geprüft …", async (profile, folder, token) =>
        {
            int count = await modrinth.Install(folder, profile.VersionId, result.Version, null, token, profile.UseVeylo);
            ShowUpdates([]);
            return $"{result.Project.Title} {result.Version.Number} installiert · {count} Datei(en).";
        });
    }

    private async void CheckModUpdates(object sender, RoutedEventArgs e)
    {
        await RunModOperation("Installierte Dateien werden mit Modrinth abgeglichen …", async (profile, folder, token) =>
        {
            var updates = await modrinth.Updates(folder, profile.VersionId, token);
            ShowUpdates(updates, checkedNow: true);
            return updates.Length == 0 ? "Keine neueren kompatiblen Modrinth-Versionen gefunden." :
                $"{updates.Length} kompatible Updates verfügbar.";
        });
    }

    private async void UpdateModrinth(object sender, RoutedEventArgs e)
    {
        if (ModUpdates.SelectedItem is not Modrinth.Update update) return;
        await RunModOperation("Die neue Mod-Version wird heruntergeladen und geprüft …", async (profile, folder, token) =>
        {
            await modrinth.Install(folder, profile.VersionId, update.Version, update, token, profile.UseVeylo);
            var remaining = ((IEnumerable<Modrinth.Update>)ModUpdates.ItemsSource).Where(item => item != update).ToArray();
            ShowUpdates(remaining, checkedNow: true);
            return $"Mod auf {update.Version.Number} aktualisiert.";
        });
    }

    private void ShowUpdates(Modrinth.Update[] updates, bool checkedNow = false)
    {
        ModUpdates.ItemsSource = updates;
        ModUpdates.Visibility = ApplyModUpdate.Visibility = updates.Length == 0 ? Visibility.Collapsed : Visibility.Visible;
        if (updates.Length > 0) ModUpdates.SelectedIndex = 0;
        UpdateInfo.Text = updates.Length > 0 ? $"{updates.Length} Updates für diese Version und Fabric" :
            checkedNow ? "Keine weiteren kompatiblen Updates in dieser Liste." : "";
    }

    private async Task RunModOperation(string message, Func<Profile, string, CancellationToken, Task<string>> action)
    {
        if (busy || ProfilePicker.SelectedItem is not Profile profile || IsRunning(profile) || ModsFolder() is not string folder) return;
        using var operation = new CancellationTokenSource(TimeSpan.FromMinutes(5));
        installation = operation;
        busy = true;
        RefreshActions();
        ShowProgress(new(message));
        try { Status.Text = await action(profile, folder, operation.Token); }
        catch (OperationCanceledException) { Status.Text = "Modrinth-Vorgang abgebrochen oder Zeitlimit erreicht."; }
        catch (InvalidDataException error) { Status.Text = error.Message; }
        catch (System.Net.Http.HttpRequestException error)
        {
            Status.Text = error.StatusCode == System.Net.HttpStatusCode.TooManyRequests ?
                "Modrinth begrenzt die Anfragen gerade. Versuche es in einer Minute erneut." :
                "Modrinth ist gerade nicht erreichbar oder konnte die Datei nicht liefern.";
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        { Status.Text = "Die Mod-Dateien konnten nicht gelesen, geprüft oder gespeichert werden."; }
        finally
        {
            installation = null;
            busy = false;
            DownloadProgress.Visibility = Visibility.Collapsed;
            RefreshMods();
            RefreshActions();
        }
    }

    private async void ImportMod(object sender, RoutedEventArgs e)
    {
        if (busy || ProfilePicker.SelectedItem is not Profile profile || IsRunning(profile) || ModsFolder() is not string folder) return;
        var dialog = new OpenFileDialog { Filter = "Fabric-Mod (*.jar)|*.jar" };
        if (dialog.ShowDialog(this) != true) return;
        busy = true;
        RefreshActions();
        try
        {
            await ModManager.Import(dialog.FileName, folder);
            Status.Text = "Mod importiert. Sie wird beim nächsten Spielstart geladen.";
        }
        catch (InvalidDataException error) { Status.Text = error.Message; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException or JsonException)
        { Status.Text = "Die Mod konnte nicht importiert werden."; }
        finally
        {
            busy = false;
            RefreshMods();
            RefreshActions();
        }
    }

    private void ToggleMod(object sender, RoutedEventArgs e)
    {
        if (ModList.SelectedItem is not ModManager.Entry entry)
        {
            Status.Text = "Wähle zuerst eine Mod.";
            return;
        }
        try { ModManager.Toggle(entry); RefreshMods(); ShowUpdates([]); }
        catch (InvalidDataException error) { Status.Text = error.Message; }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        { Status.Text = "Die Mod konnte nicht umgeschaltet werden."; }
    }

    private void OpenMods(object sender, RoutedEventArgs e)
    {
        if (ModsFolder() is not string folder) return;
        try
        {
            Directory.CreateDirectory(folder);
            Process.Start(new ProcessStartInfo(folder) { UseShellExecute = true });
        }
        catch (Exception) { Status.Text = "Der Mod-Ordner konnte nicht geöffnet werden."; }
    }

    private void SignOut(object sender, RoutedEventArgs e)
    {
        session = null;
        login.AccountManager.ClearAccounts();
        Account.Text = "Nicht angemeldet";
        Status.Text = "Aus Veylo abgemeldet.";
        RefreshActions();
    }

    private void ShowProgress(InstallProgress update)
    {
        Status.Text = update.Message;
        DownloadProgress.Visibility = Visibility.Visible;
        DownloadProgress.IsIndeterminate = update.Percent is null;
        if (update.Percent is double value) DownloadProgress.Value = Math.Clamp(value, 0, 100);
    }

    private void CancelInstall(object sender, RoutedEventArgs e)
    {
        Status.Text = "Installation wird abgebrochen …";
        installation?.Cancel();
    }

    private void WindowClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (installation is not null)
        {
            installation.Cancel();
            Status.Text = "Installation wird abgebrochen. Schließe Veylo danach erneut.";
            e.Cancel = true;
        }
    }

    private void WindowClosed(object? sender, EventArgs e) => presence.Dispose();

    private void ReadDiscordSettings()
    {
        string path = Path.Combine(root, "discord.json");
        try
        {
            var settings = File.Exists(path) ? JsonSerializer.Deserialize<DiscordSettings>(File.ReadAllText(path)) : null;
            DiscordId.Text = settings?.ApplicationId ?? Environment.GetEnvironmentVariable("VEYLO_DISCORD_APP_ID") ?? "";
            DiscordEnabled.IsChecked = settings?.Enabled ?? !string.IsNullOrWhiteSpace(DiscordId.Text);
            ConfigurePresence();
        }
        catch (Exception error) when (error is IOException or JsonException or UnauthorizedAccessException)
        {
            Status.Text = "Discord-Einstellungen konnten nicht gelesen werden.";
        }
    }

    private void SaveDiscord(object sender, RoutedEventArgs e)
    {
        string id = DiscordId.Text.Trim();
        bool enabled = DiscordEnabled.IsChecked == true;
        if (enabled && (!ulong.TryParse(id, out ulong parsed) || parsed == 0))
        {
            Status.Text = "Gib eine gültige Discord Application ID ein.";
            return;
        }
        try
        {
            Directory.CreateDirectory(root);
            string path = Path.Combine(root, "discord.json");
            File.WriteAllText(path + ".tmp", JsonSerializer.Serialize(new DiscordSettings(enabled, id)));
            File.Move(path + ".tmp", path, overwrite: true);
            Status.Text = ConfigurePresence() ? "Discord-Einstellungen gespeichert." : "Discord konnte nicht initialisiert werden.";
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException)
        {
            Status.Text = "Discord-Einstellungen konnten nicht gespeichert werden.";
        }
    }

    private bool ConfigurePresence()
    {
        if (DiscordEnabled.IsChecked != true)
        {
            presence.Dispose();
            return true;
        }
        try
        {
            if (!presence.Configure(DiscordId.Text.Trim())) return false;
            UpdatePresence();
            return true;
        }
        catch (Exception)
        {
            presence.Dispose();
            return false;
        }
    }

    private void UpdatePresence()
    {
        try
        {
            if (startingVersion is not null) presence.Publish(startingVersion);
            else if (runningPresence.Count > 0)
            {
                var game = runningPresence.Values.MaxBy(value => value.Started);
                presence.Publish(game.Version, running: true, started: game.Started);
            }
            else presence.Publish();
        }
        catch (Exception) { presence.Dispose(); }
    }

}

