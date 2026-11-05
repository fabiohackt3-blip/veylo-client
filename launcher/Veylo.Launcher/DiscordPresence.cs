using DiscordRPC;

namespace Veylo.Launcher;

internal sealed class DiscordPresence : IDisposable
{
    private DiscordRpcClient? client;
    private string applicationId = "";
    private readonly DateTime opened = DateTime.UtcNow;

    public bool Configure(string value)
    {
        if (value == applicationId && client is not null) return true;
        Dispose();
        if (!ulong.TryParse(value, out ulong id) || id == 0) return false;

        var connection = new DiscordRpcClient(value);
        if (!connection.Initialize())
        {
            connection.Dispose();
            return false;
        }
        client = connection;
        applicationId = value;
        return true;
    }

    public void Publish(string? version = null, bool running = false, DateTime? started = null)
    {
        client?.SetPresence(new RichPresence
        {
            Details = version is null ? "Im Veylo Launcher" : running ? "Spielt Minecraft" : "Minecraft wird gestartet",
            State = version is null ? "Bereit zum Spielen" : $"Minecraft {version} | Veylo Client",
            Timestamps = new Timestamps(started ?? opened)
        });
    }

    public void Dispose()
    {
        client?.Dispose();
        client = null;
        applicationId = "";
    }
}
