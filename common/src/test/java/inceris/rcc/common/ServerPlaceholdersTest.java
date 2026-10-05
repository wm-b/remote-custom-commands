package inceris.rcc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerPlaceholdersTest {
    @TempDir Path temp;
    @Test void scalarAndListTemplatesLoadButInvalidTemplatesStillFail() throws Exception {
        Files.writeString(temp.resolve("commands.yml"), """
            scalar:
              server: "$arg1"
              runcmd: [say scalar]
            list:
              server: ["$arg1", "fabric-$player", "$uuid", "$multiargs", "$server"]
              runcmd: [say list]
            """);
        var definitions = CommandConfig.load(temp);
        assertEquals(List.of("$arg1"), definitions.get("scalar").servers());
        assertEquals(5, definitions.get("list").servers().size());
        for (String invalid : List.of("bad/server", "two servers", "$unknown", "$arg0", "$target-server")) {
            Files.writeString(temp.resolve("invalid.yml"), "bad:\n  server: '" + invalid + "'\n  runcmd: [say invalid]\n");
            assertThrows(IllegalArgumentException.class, () -> CommandConfig.load(temp), invalid);
        }
    }
    @Test void destinationsResolveBeforeCommandsAndCurrencyAndDeduplicateAfterResolution() throws Exception {
        Files.writeString(temp.resolve("commands.yml"), """
            grant:
              server: ["$arg1", lobby, "fabric-$player", "$server"]
              runcmd: ["say $arg2 $target-server"]
              currency:
                type: scoreboard
                action: add
                currency: points
                player: "$arg2"
                amount: "$arg3"
              on-success: ["say success $target-server"]
            """);
        var adapter = new Adapter(); var engine = new BackendEngine("origin", adapter);
        var definitions = CommandConfig.load(temp); engine.setDefinitions(definitions);
        engine.invoke("grant", List.of("lobby", "Bob", "10"), "Alice", "id", true, n -> true);
        assertEquals(List.of("lobby", "fabric-Alice", "origin"), adapter.requests().stream().map(Packet.Request::target).toList());
        for (var request : adapter.requests()) {
            assertEquals(List.of("say Bob " + request.target()), request.commands());
            assertEquals("Bob", request.currency().player());
            engine.receive(Packet.encode(new Packet.Response(request.id(), "origin", request.target(), Packet.Code.SUCCESS, "OK")));
        }
        assertEquals(List.of("say success lobby", "say success fabric-Alice", "say success origin"), adapter.commands);
        assertEquals("$arg1", engine.definitions().get("grant").servers().getFirst());
        assertEquals(definitions, engine.definitions());
    }
    @Test void missingAndInvalidArgumentsFailLocallyWhileValidDestinationsStillRun() throws Exception {
        Files.writeString(temp.resolve("commands.yml"), """
            dynamic:
              server: ["$arg1", lobby]
              runcmd: [say remote]
              on-error: ["say error $target-server"]
            """);
        for (List<String> args : List.of(List.<String>of(), List.of("bad/server"), List.of("two servers"), List.of("$arg2"))) {
            var adapter = new Adapter(); var engine = new BackendEngine("origin", adapter);
            engine.setDefinitions(CommandConfig.load(temp));
            engine.invoke("dynamic", args, null, null, true, n -> true);
            assertEquals(List.of("lobby"), adapter.requests().stream().map(Packet.Request::target).toList());
            assertEquals(1, adapter.commands.size());
            assertTrue(adapter.feedback.stream().anyMatch(s -> s.contains("INVALID_REQUEST")));
        }
    }
    @Test void targetOverrideAndBroadcastBypassMissingDestinationArguments() throws Exception {
        Files.writeString(temp.resolve("commands.yml"), """
            dynamic:
              server: "$arg1"
              runcmd: ["say $target-server"]
            broadcast:
              broadcast: true
              server: "$arg1"
              runcmd: ["say $target-server"]
            """);
        var adapter = new Adapter(); var engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(CommandConfig.load(temp));
        engine.invokeTarget("override", "dynamic", List.of(), null, null, true, n -> true);
        assertEquals("override", adapter.requests().getFirst().target());
        engine.invoke("broadcast", List.of(), null, null, true, n -> true);
        var discovery = (Packet.Discover) adapter.messages.getLast();
        engine.receive(Packet.encode(new Packet.Servers(discovery.id(), "origin", List.of("paper", "fabric"))));
        assertEquals(List.of("override", "paper", "fabric"), adapter.requests().stream().map(Packet.Request::target).toList());
        assertTrue(adapter.commands.isEmpty());
    }
    @Test void internalCallbackResolvesServerFromItsExplicitArguments() throws Exception {
        Files.writeString(temp.resolve("commands.yml"), """
            first:
              server: lobby
              runcmd: [say first]
              on-success: ["rcc:next $arg2 $arg1"]
            next:
              server: "$arg1"
              runcmd: ["say $arg2 $target-server"]
            """);
        var adapter = new Adapter(); var engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(CommandConfig.load(temp));
        engine.invoke("first", List.of("Bob", "fabric"), null, null, true, n -> true);
        var request = adapter.requests().getFirst();
        engine.receive(Packet.encode(new Packet.Response(request.id(), "origin", "lobby", Packet.Code.SUCCESS, "OK")));
        var callback = adapter.requests().getLast();
        assertEquals("fabric", callback.target());
        assertEquals(List.of("say Bob fabric"), callback.commands());
    }
    private static final class Adapter implements BackendEngine.Adapter {
        final List<Packet> messages = new ArrayList<>();
        final List<String> commands = new ArrayList<>(), feedback = new ArrayList<>();
        public boolean send(byte[] message) { messages.add(Packet.decode(message)); return true; }
        public boolean dispatchConsole(String command) { commands.add(command); return true; }
        public void feedback(String uuid, String message) { feedback.add(message); }
        public void log(String message) {}
        List<Packet.Request> requests() { return messages.stream().filter(Packet.Request.class::isInstance).map(Packet.Request.class::cast).toList(); }
    }
}
