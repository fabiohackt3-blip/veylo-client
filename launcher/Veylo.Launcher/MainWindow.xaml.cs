using System.Collections.ObjectModel;
using System.Diagnostics;
using System.IO;
using System.Windows;
using System.Windows.Controls;
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

    public MainWindow()
    {
        var root = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Veylo");
        store = new ProfileStore(root);
        installer = new GameInstaller(root);
        login = new JELoginHandlerBuilder()
            .WithAccountManager(new InMemoryXboxGameAccountManager(JEGameAccount.FromSessionStorage))
            .Build();
        InitializeComponent();
        ProfilePicker.ItemsSource = profiles;
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
        RefreshActions();
        try
        {
            Status.Text = "Anmeldung wird geprüft …";
            try
            {
                session = await login.AuthenticateSilently();
            }
            catch (Exception)
            {
                session = null;
                Account.Text = "Nicht angemeldet";
                Status.Text = "Bitte melde dich erneut an.";
                return;
            }
            var progress = new Progress<string>(message => Status.Text = message);
            var process = await installer.Start(profile, session, progress);
            running[profile.Id] = process;
            Status.Text = "Minecraft läuft. Rechte Shift öffnet Veylo.";
            _ = ObserveGame(profile.Id, process);
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
        Ram.Value = profile.RamMb;
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
        PlayButton.IsEnabled = ready && !busy && session is not null &&
                               ProfilePicker.SelectedItem is Profile profile && !IsRunning(profile);
    }
}
