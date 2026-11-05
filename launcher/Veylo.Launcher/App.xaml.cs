using System.Threading;
using System.Windows;

namespace Veylo.Launcher;

public partial class App : Application
{
    private Mutex? instance;

    protected override void OnStartup(StartupEventArgs e)
    {
        instance = new Mutex(true, @"Local\VeyloClient.Launcher", out var ownsInstance);
        if (!ownsInstance)
        {
            instance.Dispose();
            instance = null;
            Shutdown();
            return;
        }
        base.OnStartup(e);
    }

    protected override void OnExit(ExitEventArgs e)
    {
        instance?.ReleaseMutex();
        instance?.Dispose();
        base.OnExit(e);
    }
}
