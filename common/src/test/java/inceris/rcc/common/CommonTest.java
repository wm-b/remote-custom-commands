package inceris.rcc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommonTest {
    @TempDir Path temp;
    @Test void configurationDefaultsAndMultipleFiles() throws Exception {
        Files.writeString(temp.resolve("a.yml"), "hello:\n  server: lobby\n  runcmd: [say hi]\n");
        Files.writeString(temp.resolve("b.yml"), "bye:\n  command: /leave\n  server: survival\n  runcmd: [say bye]\n  register: true\n");
        Files.writeString(temp.resolve("ignored.txt"), "bad");
        var config = CommandConfig.load(temp);
        assertEquals(2, config.size());
        assertFalse(config.get("hello").register());
        assertTrue(config.get("hello").permissionRequired());
        assertEquals("leave", config.get("bye").binding());
    }
    @Test void duplicateAndInvalidConfigurationRejected() throws Exception {
        Files.writeString(temp.resolve("a.yml"), "hello:\n  server: lobby\n  runcmd: [say hi]\n");
        Files.writeString(temp.resolve("b.yml"), "hello:\n  server: lobby\n  runcmd: [say bye]\n");
        assertThrows(IllegalArgumentException.class, () -> CommandConfig.load(temp));
        Files.delete(temp.resolve("b.yml"));
        Files.writeString(temp.resolve("a.yml"), "hello:\n  server: lobby\n  runcmd: []\n");
        assertThrows(IllegalArgumentException.class, () -> CommandConfig.load(temp));
    }
    @Test void startupSkipsBadFileAndKeepsValidDefinitions() throws Exception {
        Files.writeString(temp.resolve("a.yml"), "good:\n  server: lobby\n  runcmd: [say hi]\n");
        Files.writeString(temp.resolve("b.yml"), "bad:\n  server: lobby\n  runcmd: []\n");
        List<String> errors = new ArrayList<>();
        assertEquals(Set.of("good"), CommandConfig.loadAvailable(temp, errors::add).keySet());
        assertEquals(1, errors.size());
    }
    @Test void quotesAndLiteralPlaceholders() {
        assertEquals(List.of("a b", "", "c"), Arguments.parse("\"a b\" \"\" c"));
        assertEquals("Alice $x one one two origin target ", Placeholders.resolve("$player $x $arg1 $multiargs $server $target-server $arg3", "Alice", "id", List.of("one", "two"), "origin", "target"));
        assertThrows(IllegalArgumentException.class, () -> Arguments.parse("\"unfinished"));
    }
    @Test void protocolRoundTripAndValidation() {
        var request = new Packet.Request(UUID.randomUUID(), "a", "b", List.of("say hello"));
        assertEquals(request, Packet.decode(Packet.encode(request)));
        var response = new Packet.Response(request.id(), "a", "b", Packet.Code.SUCCESS, "OK");
        assertEquals(response, Packet.decode(Packet.encode(response)));
        byte[] malformed = Packet.encode(request);
        malformed[0] = 2;
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(malformed));
    }
    @Test void permissionsCallbacksAndDuplicateDelivery() {
        Fake origin = new Fake(), target = new Fake();
        BackendEngine from = new BackendEngine("origin", origin), to = new BackendEngine("target", target);
        origin.peer = to; target.peer = from;
        from.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("target"), false, List.of("say $player"), false, true, "rcc.use", List.of("say success"), List.of("say error"))));
        from.invoke("test", List.of(), "Alice", "id", false, node -> false);
        assertTrue(target.commands.isEmpty());
        from.invoke("test", List.of(), "Alice", "id", false, node -> true);
        assertEquals(List.of("say Alice"), target.commands);
        assertEquals(List.of("say success"), origin.commands);
        target.lastReply = target.sent;
        from.receive(target.lastReply);
        assertEquals(1, origin.commands.size());
        to.receive(origin.sent);
        assertEquals(1, target.commands.size());
    }
    @Test void failedTransportRunsErrorCallbackAtOrigin() {
        Fake origin = new Fake();
        origin.available = false;
        BackendEngine from = new BackendEngine("origin", origin);
        from.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("target"), false, List.of("say remote"), false, false, null, List.of("say success"), List.of("say error"))));
        from.invoke("test", List.of(), null, null, true, node -> true);
        assertEquals(List.of("say error"), origin.commands);
    }
    @Test void listsAreLoadedAndBroadcastIgnoresServer() throws Exception {
        Files.writeString(temp.resolve("commands.yml"), """
            multiple:
              server: [paper, fabric, paper]
              runcmd: [say hi]
              on-success: [say first, say second]
              on-error: [say failed, say again]
            everyone:
              broadcast: true
              server: {ignored: invalid}
              runcmd: [say all]
            """);
        var config = CommandConfig.load(temp);
        assertEquals(List.of("paper", "fabric"), config.get("multiple").servers());
        assertEquals(List.of("say first", "say second"), config.get("multiple").onSuccess());
        assertEquals(List.of("say failed", "say again"), config.get("multiple").onError());
        assertTrue(config.get("everyone").broadcast());
        assertTrue(config.get("everyone").servers().isEmpty());
        Files.writeString(temp.resolve("invalid.yml"), "bad:\n  server: []\n  runcmd: [say hi]\n");
        assertThrows(IllegalArgumentException.class, () -> CommandConfig.load(temp));
    }
    @Test void eachDestinationGetsResolvedCommandsAndItsOwnCallbacks() {
        Controlled adapter = new Controlled();
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("paper", "fabric"), false,
            List.of("say $player $target-server"), false, false, null,
            List.of("say OK $target-server", "say done $target-server"), List.of("say FAILED $target-server"))));
        engine.invoke("test", List.of(), "Alice", "id", false, node -> false);
        assertEquals(2, adapter.messages.size());
        Packet.Request paper = (Packet.Request) adapter.messages.get(0), fabric = (Packet.Request) adapter.messages.get(1);
        assertEquals(List.of("say Alice paper"), paper.commands());
        assertEquals(List.of("say Alice fabric"), fabric.commands());
        assertNotEquals(paper.id(), fabric.id());
        byte[] success = Packet.encode(new Packet.Response(paper.id(), "origin", "paper", Packet.Code.SUCCESS, "OK"));
        engine.receive(success); engine.receive(success);
        engine.receive(Packet.encode(new Packet.Response(fabric.id(), "origin", "fabric", Packet.Code.ROUTING_FAILURE, "offline")));
        assertEquals(List.of("say OK paper", "say done paper", "say FAILED fabric"), adapter.commands);
    }
    @Test void broadcastDiscoversAllBackendsAndDoesNotRepeatOnDuplicateReply() {
        Controlled adapter = new Controlled();
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("ignored"), true,
            List.of("say $target-server"), false, false, null, List.of(), List.of("say failure"))));
        engine.invoke("test", List.of(), null, null, true, node -> true);
        Packet.Discover discovery = (Packet.Discover) adapter.messages.getFirst();
        assertEquals(discovery, Packet.decode(Packet.encode(discovery)));
        Packet.Servers servers = new Packet.Servers(discovery.id(), "origin", List.of("origin", "paper", "fabric", "paper"));
        assertEquals(servers, Packet.decode(Packet.encode(servers)));
        engine.receive(Packet.encode(servers)); engine.receive(Packet.encode(servers));
        assertEquals(4, adapter.messages.size());
        assertEquals(List.of("origin", "paper", "fabric"), adapter.messages.subList(1, 4).stream().map(p -> ((Packet.Request) p).target()).toList());
        assertEquals(List.of("say fabric"), ((Packet.Request) adapter.messages.getLast()).commands());
    }
    @Test void callbackFailureDoesNotPreventLaterEntries() {
        Fake adapter = new Fake();
        adapter.available = false;
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("target"), false,
            List.of("say hi"), false, false, null, List.of(), List.of("rcc:test", "say next"))));
        engine.invoke("test", List.of(), null, null, true, node -> true);
        assertEquals(List.of("say next"), adapter.commands);
    }
    @Test void targetOverridesBroadcastAndResolvesCallbacksWithoutChangingConfiguration() {
        Controlled adapter = new Controlled();
        BackendEngine engine = new BackendEngine("origin", adapter);
        CommandDefinition definition = new CommandDefinition("test", null, List.of("configured"), true,
            List.of("say $player $arg1 $target-server"), false, false, null,
            List.of("say success $target-server $arg1"), List.of("say error $target-server"));
        engine.setDefinitions(Map.of("test", definition));
        engine.invokeTarget("override", "test", List.of("one two"), "Alice", "id", false, node -> false);
        assertEquals(1, adapter.messages.size());
        Packet.Request request = (Packet.Request) adapter.messages.getFirst();
        assertEquals("override", request.target());
        assertEquals(List.of("say Alice one two override"), request.commands());
        engine.receive(Packet.encode(new Packet.Response(request.id(), "origin", "override", Packet.Code.SUCCESS, "OK")));
        assertEquals(List.of("say success override one two"), adapter.commands);
        assertSame(definition, engine.definitions().get("test"));
        engine.invoke("test", List.of(), null, null, true, node -> true);
        assertInstanceOf(Packet.Discover.class, adapter.messages.getLast());
    }
    @Test void targetOverridesServerListAndReportsFailuresWithOverrideContext() {
        Controlled adapter = new Controlled();
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("first", "second"), false,
            List.of("say hi"), false, false, null, List.of(), List.of("say failure $target-server"))));
        engine.invokeTarget("unknown", "test", List.of(), null, null, true, node -> true);
        assertEquals(1, adapter.messages.size());
        Packet.Request request = (Packet.Request) adapter.messages.getFirst();
        assertEquals("unknown", request.target());
        engine.receive(Packet.encode(new Packet.Response(request.id(), "origin", "unknown", Packet.Code.ROUTING_FAILURE, "Unknown backend")));
        assertEquals(List.of("say failure unknown"), adapter.commands);
        engine.invoke("test", List.of(), null, null, true, node -> true);
        assertEquals(List.of("unknown", "first", "second"), adapter.messages.stream().map(p -> ((Packet.Request) p).target()).toList());
    }
    @Test void targetPreservesPermissionChecksAndRejectsInvalidTargets() {
        Controlled adapter = new Controlled();
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of("test", new CommandDefinition("test", null, List.of("configured"), true,
            List.of("say hi"), false, true, "rcc.use", List.of(), List.of())));
        engine.invokeTarget("override", "test", List.of(), "Alice", "id", true, node -> false);
        assertTrue(adapter.messages.isEmpty());
        engine.invokeTarget("bad/server", "test", List.of(), "Alice", "id", true, node -> true);
        engine.invokeTarget("override", "missing", List.of(), null, null, true, node -> true);
        assertTrue(adapter.messages.isEmpty());
        engine.invokeTarget("override", "test", List.of(), "Alice", "id", false, node -> true);
        assertEquals(1, adapter.messages.size());
        assertEquals("override", ((Packet.Request) adapter.messages.getFirst()).target());
    }
    @Test void targetOverrideIsNotInheritedByInternallyInvokedCallbackCommands() {
        Controlled adapter = new Controlled();
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of(
            "first", new CommandDefinition("first", null, List.of("configured"), false, List.of("say hi"), false, false, null, List.of("rcc:next $arg1"), List.of()),
            "next", new CommandDefinition("next", null, List.of("next-server"), false, List.of("say $arg1 $target-server"), false, false, null, List.of(), List.of())));
        engine.invokeTarget("override", "first", List.of("Alice"), null, null, true, node -> true);
        Packet.Request request = (Packet.Request) adapter.messages.getFirst();
        engine.receive(Packet.encode(new Packet.Response(request.id(), "origin", "override", Packet.Code.SUCCESS, "OK")));
        assertEquals(2, adapter.messages.size());
        Packet.Request callback = (Packet.Request) adapter.messages.getLast();
        assertEquals("next-server", callback.target());
        assertEquals(List.of("say Alice next-server"), callback.commands());
    }
    @Test void successCallbackParsesExplicitArgumentsAndKeepsPlayerContext() {
        Controlled adapter = new Controlled();
        BackendEngine engine = callbackEngine(adapter, List.of("  rcc:next   $arg2 \"$arg1\" literal"), List.of(),
            "say <$arg1>|<$arg2>|<$arg3>|<$arg4>|$player|$uuid");
        engine.invoke("first", List.of("Alice Smith", "1", "unused"), "Inxc", "original-uuid", true, node -> true);
        reply(engine, adapter, Packet.Code.SUCCESS);
        Packet.Request callback = (Packet.Request) adapter.messages.getLast();
        assertEquals("destination", callback.target());
        assertEquals(List.of("say <1>|<Alice Smith>|<literal>|<>|Inxc|original-uuid"), callback.commands());
    }
    @Test void errorCallbackCanExplicitlyPassMultiargs() {
        Controlled adapter = new Controlled();
        BackendEngine engine = callbackEngine(adapter, List.of(), List.of("rcc:next $multiargs"), "say <$arg1>|<$arg2>|<$arg3>");
        engine.invoke("first", List.of("Inxc", "1"), null, null, true, node -> true);
        reply(engine, adapter, Packet.Code.COMMAND_FAILURE);
        assertEquals(List.of("say <Inxc>|<1>|<>"), ((Packet.Request) adapter.messages.getLast()).commands());
    }
    @Test void successAndErrorCallbacksWithoutArgumentsReceiveNone() {
        for (Packet.Code outcome : List.of(Packet.Code.SUCCESS, Packet.Code.COMMAND_FAILURE)) {
            Controlled adapter = new Controlled();
            BackendEngine engine = callbackEngine(adapter, List.of("rcc:next"), List.of("rcc:next"), "say <$arg1>|<$multiargs>");
            engine.invoke("first", List.of("Inxc", "1"), null, null, true, node -> true);
            reply(engine, adapter, outcome);
            assertEquals(List.of("say <>|<>"), ((Packet.Request) adapter.messages.getLast()).commands());
        }
    }
    @Test void normalCallbacksOnlyDispatchTheirExplicitText() {
        Controlled adapter = new Controlled();
        BackendEngine engine = callbackEngine(adapter, List.of("  /say  \"$arg2\" $arg1", "/say done"), List.of(), "say next");
        engine.invoke("first", List.of("Inxc", "hello world", "unused"), null, null, true, node -> true);
        reply(engine, adapter, Packet.Code.SUCCESS);
        assertEquals(List.of("/say  \"hello world\" Inxc", "/say done"), adapter.commands);
        assertEquals(1, adapter.messages.size());
    }
    @Test void malformedCallbackArgumentsDoNotPreventLaterCallbacks() {
        Controlled adapter = new Controlled();
        BackendEngine engine = callbackEngine(adapter, List.of("rcc:next \"unterminated", "rcc:", "rcc:first explicit", "say later"), List.of(), "say next");
        engine.invoke("first", List.of("Inxc"), null, null, true, node -> true);
        reply(engine, adapter, Packet.Code.SUCCESS);
        assertEquals(1, adapter.messages.size());
        assertEquals(List.of("say later"), adapter.commands);
        assertEquals(3, adapter.logs.size());
    }
    private static BackendEngine callbackEngine(Controlled adapter, List<String> success, List<String> error, String nextCommand) {
        BackendEngine engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(Map.of(
            "first", new CommandDefinition("first", null, List.of("source"), false, List.of("say first"), false, false, null, success, error),
            "next", new CommandDefinition("next", null, List.of("destination"), false, List.of(nextCommand), false, false, null, List.of(), List.of())));
        return engine;
    }
    private static void reply(BackendEngine engine, Controlled adapter, Packet.Code code) {
        Packet.Request request = (Packet.Request) adapter.messages.getFirst();
        engine.receive(Packet.encode(new Packet.Response(request.id(), request.origin(), request.target(), code, "result")));
    }
    private static final class Controlled implements BackendEngine.Adapter {
        final List<Packet> messages = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<String> logs = new ArrayList<>();
        public boolean send(byte[] message) { messages.add(Packet.decode(message)); return true; }
        public boolean dispatchConsole(String command) { commands.add(command); return true; }
        public void feedback(String uuid, String message) {}
        public void log(String message) { logs.add(message); }
    }
    private static final class Fake implements BackendEngine.Adapter {
        BackendEngine peer;
        boolean available = true;
        List<String> commands = new ArrayList<>();
        byte[] sent, lastReply;
        public boolean send(byte[] message) { sent = message; if (!available) return false; if (peer != null) peer.receive(message); return true; }
        public boolean dispatchConsole(String command) { commands.add(command); return true; }
        public void feedback(String playerUuid, String message) {}
        public void log(String message) {}
    }
}
