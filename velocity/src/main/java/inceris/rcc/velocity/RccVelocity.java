package inceris.rcc.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import inceris.rcc.common.Packet;
import inceris.rcc.common.BuildInfo;
import org.slf4j.Logger;
import java.util.*;
import java.util.concurrent.*;

@Plugin(id = "rcc", name = "Remote Custom Commands Bridge", version = BuildInfo.VERSION, authors = {"Inceris"})
public final class RccVelocity {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(Packet.CHANNEL);
    private record Route(String origin, String target, long deadline) {}
    private final ProxyServer proxy;
    private final Logger logger;
    private final Map<UUID, Route> routes = new ConcurrentHashMap<>();
    @Inject public RccVelocity(ProxyServer proxy, Logger logger) { this.proxy = proxy; this.logger = logger; }
    @Subscribe public void init(ProxyInitializeEvent event) {
        proxy.getChannelRegistrar().register(CHANNEL);
        proxy.getScheduler().buildTask(this, this::expire).repeat(1, TimeUnit.SECONDS).schedule();
    }
    @Subscribe public void message(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) return;
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection connection)) return;
        String sender = connection.getServer().getServerInfo().getName();
        Packet packet;
        try { packet = Packet.decode(event.getData()); }
        catch (IllegalArgumentException e) { logger.warn("Rejected malformed RCC packet from {}: {}", sender, e.getMessage()); return; }
        if (packet instanceof Packet.Discover discover) {
            if (!discover.origin().equals(sender)) return;
            List<String> names = proxy.getAllServers().stream().map(s -> s.getServerInfo().getName()).sorted().toList();
            try { send(sender, Packet.encode(new Packet.Servers(discover.id(), sender, names))); }
            catch (IllegalArgumentException e) {
                logger.warn("RCC broadcast server list exceeds protocol limits: {}", e.getMessage());
                send(sender, Packet.encode(new Packet.Servers(discover.id(), sender, List.of())));
            }
        } else if (packet instanceof Packet.Request request) {
            route(request.id(), request.origin(), request.target(), connection, event.getData());
        } else if (packet instanceof Packet.Check check) {
            route(check.id(), check.origin(), check.target(), connection, event.getData());
        } else if (packet instanceof Packet.Response response) {
            Route route = routes.get(response.id());
            if (route == null || !route.target().equals(sender) || !route.origin().equals(response.origin()) || !route.target().equals(response.target())) return;
            routes.remove(response.id(), route);
            send(route.origin(), event.getData());
        }
    }
    private void route(UUID id, String origin, String target, ServerConnection connection, byte[] payload) {
        String sender = connection.getServer().getServerInfo().getName();
        if (!origin.equals(sender)) return;
        if (routes.putIfAbsent(id, new Route(sender, target, System.currentTimeMillis() + 10_000)) != null) return;
        if (proxy.getServer(target).isEmpty()) { fail(id, origin, target, connection, Packet.Code.ROUTING_FAILURE, "Unknown backend"); return; }
        if (!send(target, payload)) fail(id, origin, target, connection, Packet.Code.ROUTING_FAILURE, "No player on destination backend");
    }
    private boolean send(String server, byte[] payload) {
        for (Player player : proxy.getAllPlayers()) {
            ServerConnection connection = player.getCurrentServer().orElse(null);
            if (connection != null && connection.getServer().getServerInfo().getName().equals(server)
                && connection.sendPluginMessage(CHANNEL, payload)) return true;
        }
        return false;
    }
    private void fail(UUID id, String origin, String target, ServerConnection connection, Packet.Code code, String detail) {
        routes.remove(id);
        byte[] response = Packet.encode(new Packet.Response(id, origin, target, code, detail));
        // The incoming connection is a known carrier, including while global player state is changing.
        if (!connection.sendPluginMessage(CHANNEL, response)) send(origin, response);
    }
    private void expire() {
        long now = System.currentTimeMillis();
        routes.forEach((id, route) -> {
            if (now >= route.deadline() && routes.remove(id, route)) {
                send(route.origin(), Packet.encode(new Packet.Response(id, route.origin(), route.target(), Packet.Code.TIMEOUT, "Destination did not respond")));
            }
        });
    }
}
