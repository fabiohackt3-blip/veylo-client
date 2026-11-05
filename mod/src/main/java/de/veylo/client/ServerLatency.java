package de.veylo.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.MultiplayerServerListPinger;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.network.NetworkingBackend;

import java.net.UnknownHostException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

final class ServerLatency {
    private static final long PROBE_INTERVAL = TimeUnit.SECONDS.toNanos(20);
    private static final long PROBE_TIMEOUT = TimeUnit.SECONDS.toNanos(7);
    private static ClientPlayNetworkHandler connection;
    private static long nextProbe;
    private static long started;
    private static long measured = -1;
    private static int reported = -1;
    private static CompletableFuture<Void> pending;
    private static MultiplayerServerListPinger pinger;
    private static ServerInfo probe;

    static void tick(MinecraftClient client, boolean enabled) {
        var network = enabled && client.player != null ? client.getNetworkHandler() : null;
        if (network != connection) {
            resetProbe();
            connection = network;
            nextProbe = System.nanoTime();
            measured = -1;
            reported = -1;
        }
        if (network == null || client.player == null || client.isInSingleplayer()) return;
        var entry = network.getPlayerListEntry(client.player.getUuid());
        if (entry == null) entry = network.getPlayerListEntry(client.getSession().getUsername());
        reported = entry == null ? -1 : entry.getLatency();
        long now = System.nanoTime();
        if (probe != null) {
            pinger.tick();
            if (probe.ping >= 0) {
                measured = probe.ping;
                resetProbe();
            } else if (now - started >= PROBE_TIMEOUT) {
                measured = -1;
                resetProbe();
            }
        }
        var server = client.getCurrentServerEntry();
        if (reported > 0 || pending != null && !pending.isDone() ||
                probe != null || server == null || now - nextProbe < 0) return;
        nextProbe = now + PROBE_INTERVAL;
        started = now;
        var attempt = new MultiplayerServerListPinger();
        var info = new ServerInfo(server.name, server.address, server.getServerType());
        info.ping = -1;
        pinger = attempt;
        probe = info;
        pending = CompletableFuture.runAsync(() -> {
            try {
                attempt.add(info, () -> {}, () -> {}, NetworkingBackend.remote(false));
            } catch (UnknownHostException | RuntimeException ignored) {
                // An unavailable server-list endpoint leaves the measurement unknown.
            }
        }).whenComplete((result, error) -> client.execute(() -> {
            if (pinger != attempt) attempt.cancel();
        }));
    }

    private static void resetProbe() {
        if (pinger != null) pinger.cancel();
        pinger = null;
        probe = null;
    }

    static String display(MinecraftClient client) {
        if (client.isInSingleplayer()) return "Lokal";
        if (reported > 0) return reported + " ms";
        if (measured >= 0) return measured + " ms (Liste)";
        return probe == null ? "Keine Messung" : "Wird gemessen";
    }
}
