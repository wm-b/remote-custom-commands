package inceris.rcc.velocity;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import inceris.rcc.common.*;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RccVelocityTest {
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(Packet.CHANNEL);

    @Test void emptyDestinationRepliesOnIncomingConnectionAndRunsOnlyUnavailableCallback() {
        var network = new Network(true);
        network.engine.setDefinitions(Map.of("test", definition(true)));
        network.engine.invoke("test", List.of(), "Alice", "player-uuid", true, n -> true);
        assertEquals(List.of("say unavailable empty"), network.commands);
        assertEquals(1, network.replies.size());
        var response = network.replies.getFirst();
        assertEquals(Packet.Code.ROUTING_FAILURE, response.code());
        assertEquals("No player on destination backend", response.detail());
        assertEquals("empty", response.target());
        assertEquals(1, network.sent.size());
        assertInstanceOf(Packet.Check.class, network.sent.getFirst());
        // An unsolicited later success cannot reopen the failed gate.
        network.engine.receive(Packet.encode(new Packet.Response(response.id(), "origin", "empty", Packet.Code.SUCCESS, "OK")));
        assertEquals(List.of("say unavailable empty"), network.commands);
        assertEquals(1, network.sent.size());
    }

    @Test void unknownDestinationAlsoRepliesOnIncomingConnection() {
        var network = new Network(false);
        network.engine.setDefinitions(Map.of("test", definition(true)));
        network.engine.invoke("test", List.of(), null, null, true, n -> true);
        assertEquals(List.of("say unavailable empty"), network.commands);
        assertEquals("Unknown backend", network.replies.getFirst().detail());
    }

    @Test void executionWithoutAvailabilityChecksStillUsesNormalErrorCallback() {
        var network = new Network(true);
        network.engine.setDefinitions(Map.of("test", definition(false)));
        network.engine.invoke("test", List.of(), null, null, true, n -> true);
        assertEquals(List.of("say error"), network.commands);
        assertInstanceOf(Packet.Request.class, network.sent.getFirst());
        assertEquals(Packet.Code.ROUTING_FAILURE, network.replies.getFirst().code());
    }

    @Test void claimedOriginMustMatchIncomingBackendConnection() {
        var network = new Network(true);
        network.deliver(new Packet.Check(UUID.randomUUID(), "forged", "empty"));
        assertTrue(network.replies.isEmpty());
    }

    private static CommandDefinition definition(boolean check) {
        return new CommandDefinition("test", null, List.of("empty"), false, List.of("say executed"),
            false, false, null, List.of("say success"), List.of("say error"), null,
            check ? List.of("empty") : List.of(), List.of("say unavailable $attempted-server"));
    }

    private static final class Network {
        final List<Packet> sent = new ArrayList<>();
        final List<Packet.Response> replies = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final BackendEngine engine;
        final ServerConnection source;
        final RccVelocity bridge;

        Network(boolean destinationRegistered) {
            RegisteredServer origin = backend("origin"), destination = backend("empty");
            source = fake(ServerConnection.class, (proxy, method, args) -> switch (method.getName()) {
                case "getServer" -> origin;
                case "sendPluginMessage" -> {
                    var response = (Packet.Response) Packet.decode((byte[]) args[1]);
                    replies.add(response);
                    receive(response);
                    yield true;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            });
            ProxyServer proxy = fake(ProxyServer.class, (object, method, args) -> switch (method.getName()) {
                // The event's source is usable even if global player enumeration does not find it.
                case "getAllPlayers" -> List.of();
                case "getServer" -> switch ((String) args[0]) {
                    case "origin" -> Optional.of(origin);
                    case "empty" -> destinationRegistered ? Optional.of(destination) : Optional.empty();
                    default -> Optional.empty();
                };
                default -> throw new UnsupportedOperationException(method.getName());
            });
            Logger logger = fake(Logger.class, (object, method, args) -> null);
            bridge = new RccVelocity(proxy, logger);
            engine = new BackendEngine("origin", new BackendEngine.Adapter() {
                public boolean send(byte[] message) {
                    Packet packet = Packet.decode(message);
                    sent.add(packet);
                    deliver(packet);
                    return true;
                }
                public boolean dispatchConsole(String command) { commands.add(command); return true; }
                public void feedback(String uuid, String message) {}
                public void log(String message) {}
            });
        }
        void receive(Packet packet) { engine.receive(Packet.encode(packet)); }
        void deliver(Packet packet) {
            var event = new PluginMessageEvent(source, source, CHANNEL, Packet.encode(packet));
            bridge.message(event);
            assertFalse(event.getResult().isAllowed());
        }
    }

    private static RegisteredServer backend(String name) {
        ServerInfo info = new ServerInfo(name, new InetSocketAddress("127.0.0.1", 25565));
        return fake(RegisteredServer.class, (proxy, method, args) -> switch (method.getName()) {
            case "getServerInfo" -> info;
            case "getPlayersConnected" -> List.of();
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }
    private static <T> T fake(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler));
    }
}
