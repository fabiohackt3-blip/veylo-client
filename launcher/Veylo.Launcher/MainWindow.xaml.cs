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
    private IReadOnlyList<GameVersion> versions = [GameVersion.Default, new("1.20.1"), new("1.16.5"), new("1.12.2"), new("1.8.9")];
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
        ManageProfiles.ItemsSource = profiles;
        ShowPage("Home");
        ReadDiscordSettings();
        try
        {
            foreach (var profile in store.Load()) profiles.Add(profile);
            store.Save(profiles);
            ready = true;
            ProfilePicker.SelectedIndex = 0;
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
            Status.Text = profile.UseVeylo ? "Minecraft läuft. Rechte Shift öffnet Veylo." : "Minecraft läuft im Vanilla-Profil.";
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

    private void AddProfile(object sender, RoutedEventArgs e)
    {
        var name = ProfileName.Text.Trim();
        if (name.Length == 0 || profiles.Any(profile => profile.Name.Equals(name, StringComparison.OrdinalIgnoreCase)))
        {
            Status.Text = "Gib einen neuen Profilnamen ein.";
            return;
        }

        var profile = new Profile(Guid.NewGuid(), name);
        if (!Save(profiles.Append(profile))) return;
        profiles.Add(profile);
        ProfilePicker.SelectedItem = profile;
        ProfileName.Clear();
        Status.Text = "Profil erstellt.";
    }

    private void ProfileChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!ready || ProfilePicker.SelectedItem is not Profile profile) return;
        syncing = true;
        ManageProfiles.SelectedItem = profile;
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
        ProfilePicker.IsEnabled = ready && !busy;
        VersionControls.IsEnabled = ready && !busy && ProfilePicker.SelectedItem is Profile p && !IsRunning(p);
        ModsControls.IsEnabled = VersionControls.IsEnabled && ProfilePicker.SelectedItem is Profile { UseVeylo: true };
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
        if (page == "Mods") RefreshMods();
    }

    private void ManagedProfileChanged(object sender, SelectionChangedEventArgs e)
    {
        if (ready && !syncing && ManageProfiles.SelectedItem is Profile profile)
            ProfilePicker.SelectedItem = profile;
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
        VeyloMode.IsEnabled = profile.VersionId == GameVersion.Default.Id;
        VeyloMode.IsChecked = profile.UseVeylo;
        HomeVersion.Content = profile.VersionId + (profile.UseVeylo ? " · Veylo / Fabric" : " · Vanilla");
        syncing = wasSyncing;
    }

    private void VersionChanged(object sender, SelectionChangedEventArgs e)
    {
        if (!ready || syncing || busy || VersionPicker.SelectedItem is not GameVersion version ||
            ProfilePicker.SelectedItem is not Profile profile || IsRunning(profile)) return;
        SelectVersion(profile, version.Id, version.Id == GameVersion.Default.Id);
    }

    private void LoaderChanged(object sender, RoutedEventArgs e)
    {
        if (!ready || syncing || busy || ProfilePicker.SelectedItem is not Profile profile ||
            IsRunning(profile) || profile.VersionId != GameVersion.Default.Id) return;
        SelectVersion(profile, profile.VersionId, VeyloMode.IsChecked == true);
    }

    private void SelectVersion(Profile profile, string version, bool useVeylo)
    {
        string oldVersion = profile.VersionId;
        bool oldLoader = profile.UseVeylo;
        profile.VersionId = version;
        profile.UseVeylo = useVeylo;
        if (!Save(profiles))
        {
            profile.VersionId = oldVersion;
            profile.UseVeylo = oldLoader;
        }
        ApplyVersionFilter(profile);
        RefreshMods();
        RefreshActions();
    }

    private string? ModsFolder() => ProfilePicker.SelectedItem is Profile { UseVeylo: true } profile
        ? Path.Combine(installer.GamePath(profile), "mods") : null;

    private void RefreshMods()
    {
        var folder = ModsFolder();
        ModsInfo.Text = folder is null ? "Wähle ein Veylo/Fabric-Profil, um seine Mods zu verwalten." :
            "Mods für das aktive Fabric-Profil. Importiere JAR-Dateien für Minecraft 1.21.11.";
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

    private async void ImportMod(object sender, RoutedEventArgs e)
    {
        if (ModsFolder() is not string folder) return;
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
        try { ModManager.Toggle(entry); RefreshMods(); }
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

