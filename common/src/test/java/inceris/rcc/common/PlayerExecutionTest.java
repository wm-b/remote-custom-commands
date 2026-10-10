package inceris.rcc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PlayerExecutionTest {
    @TempDir Path temp;
    private static final UUID PLAYER = UUID.fromString("66d531e3-0dd4-4eea-92d7-8665b295117a");

    @Test void runAsPlayerDefaultsToFalseAndOnlyAcceptsBooleans() throws Exception {
        var definitions = config("""
            default:
              runcmd: [say hi]
            player:
              run-as-player: true
              runcmd: [say hi]
            console:
              run-as-player: false
              runcmd: [say hi]
            """);
        assertFalse(definitions.get("default").runAsPlayer());
        assertTrue(definitions.get("player").runAsPlayer());
        assertFalse(definitions.get("console").runAsPlayer());
        for (String invalid : List.of("'true'", "1", "[]", "{}")) {
            assertThrows(IllegalArgumentException.class, () -> config("test:\n  runcmd: [say hi]\n  run-as-player: " + invalid + "\n"));
        }
    }

    @Test void localPlayerCommandsUseInvokerUuidWhileCallbacksStayOnConsole() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              run-as-player: true
              runcmd: ["/spawn $arg1", "say $player $uuid $server $target-server"]
              on-success: ["say done $attempted-server"]
              on-error: [say error]
            """);
        invoke(engine);
        assertEquals(List.of("/spawn Bob", "say Alice " + PLAYER + " origin origin"), adapter.playerCommands);
        assertEquals(List.of(PLAYER, PLAYER), adapter.executingPlayers);
        assertEquals(List.of("say done origin"), adapter.consoleCommands);
        assertTrue(adapter.messages.isEmpty());
    }

    @Test void defaultConsoleExecutionDoesNotNeedAnOnlineInvoker() throws Exception {
        var adapter = new Adapter(); adapter.online = false;
        var engine = load(adapter, """
            test:
              runcmd: [say console]
              on-success: [say done]
            """);
        invoke(engine);
        assertEquals(List.of("say console", "say done"), adapter.consoleCommands);
        assertTrue(adapter.playerCommands.isEmpty());
        assertEquals(0, adapter.presenceChecks);
    }

    @Test void remoteExecutionTransportsInvokerIdentityAndDuplicateDeliveryDoesNotRepeatCommands() throws Exception {
        var origin = new Adapter(); var target = new Adapter();
        var engine = load(origin, """
            test:
              server: remote
              run-as-player: true
              runcmd: ["spawn $arg1"]
              on-success: ["say done $attempted-server"]
            """);
        var destination = new BackendEngine("remote", target);
        invoke(engine);
        var request = (Packet.Request) origin.messages.getFirst();
        assertEquals(PLAYER, request.playerUuid());
        destination.receive(Packet.encode(request));
        destination.receive(Packet.encode(request));
        assertEquals(List.of("spawn Bob"), target.playerCommands);
        assertEquals(List.of(PLAYER), target.executingPlayers);
        assertTrue(target.consoleCommands.isEmpty());
        engine.receive(Packet.encode(target.messages.getFirst()));
        engine.receive(Packet.encode(target.messages.getLast()));
        assertEquals(List.of("say done remote"), origin.consoleCommands);
    }

    @Test void consoleOrInvalidPlayerIdentityFailsWithoutExecutingAsConsole() throws Exception {
        for (String uuid : Arrays.asList(null, "invalid")) {
            var adapter = new Adapter();
            var engine = load(adapter, """
                test:
                  server: remote
                  run-as-player: true
                  runcmd: [say never]
                  on-error: [say error]
                """);
            engine.invoke("test", List.of(), uuid == null ? null : "Alice", uuid, true, n -> true);
            assertEquals(List.of("say error"), adapter.consoleCommands);
            assertTrue(adapter.playerCommands.isEmpty());
            assertTrue(adapter.messages.isEmpty());
            assertTrue(adapter.operations.isEmpty());
            assertTrue(adapter.diagnostics.getFirst().contains("INVALID_REQUEST"));
        }
    }

    @Test void missingPlayerOnDestinationFailsBeforeCurrencyAndDoesNotSubstituteAnotherPlayer() throws Exception {
        var origin = new Adapter(); var target = new Adapter(); target.online = false;
        var engine = load(origin, """
            test:
              server: remote
              run-as-player: true
              currency: {type: scoreboard, action: remove, currency: points, amount: 1, player: Bob}
              runcmd: [say never]
              on-error: ["say absent $attempted-server"]
              on-server-unavailable: [say unavailable]
            """);
        invoke(engine);
        var destination = new BackendEngine("remote", target);
        destination.receive(Packet.encode(origin.messages.getFirst()));
        assertTrue(target.operations.isEmpty());
        assertTrue(target.consoleCommands.isEmpty());
        assertTrue(target.playerCommands.isEmpty());
        var response = (Packet.Response) target.messages.getFirst();
        assertEquals(Packet.Code.COMMAND_FAILURE, response.code());
        assertTrue(response.detail().contains("not online"));
        engine.receive(Packet.encode(response));
        assertEquals(List.of("say absent remote"), origin.consoleCommands);
    }

    @Test void playerCommandFailureStillAttemptsRemainingCommandsAndAggregatesResult() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              run-as-player: true
              runcmd: [denied, throws, later]
              on-success: [say success]
              on-error: [say error]
            """);
        invoke(engine);
        assertEquals(List.of("denied", "throws", "later"), adapter.playerCommands);
        assertEquals(List.of("say error"), adapter.consoleCommands);
        assertTrue(adapter.diagnostics.getFirst().contains("COMMAND_FAILURE"));
        assertEquals(1, adapter.logs.size());
    }

    @Test void asyncCurrencyFinishesBeforePlayerDispatchAndDisconnectDoesNotFallBackToConsole() throws Exception {
        for (boolean stillOnline : List.of(true, false)) {
            var adapter = new Adapter();
            adapter.currencyResult = new CompletableFuture<>();
            var engine = load(adapter, """
                test:
                  run-as-player: true
                  currency: {type: scoreboard, action: add, currency: points, amount: 1, player: Bob}
                  runcmd: [spawn]
                  on-success: [say success]
                  on-error: [say error]
                """);
            invoke(engine);
            assertEquals(1, adapter.operations.size());
            assertTrue(adapter.playerCommands.isEmpty());
            assertTrue(adapter.consoleCommands.isEmpty());
            adapter.online = stillOnline;
            adapter.currencyResult.complete(CurrencyAction.Result.ok());
            assertEquals(stillOnline ? List.of("spawn") : List.of(), adapter.playerCommands);
            assertEquals(List.of(stillOnline ? "say success" : "say error"), adapter.consoleCommands);
        }
    }

    @Test void currencyOnlyDefinitionIgnoresRunAsPlayerAndWorksForConsole() throws Exception {
        var adapter = new Adapter(); adapter.online = false;
        var engine = load(adapter, """
            test:
              run-as-player: true
              currency: {type: scoreboard, action: add, currency: points, amount: 1, player: Bob}
              on-success: [say done]
            """);
        engine.invoke("test", List.of(), null, null, true, n -> true);
        assertEquals(1, adapter.operations.size());
        assertEquals(List.of("say done"), adapter.consoleCommands);
        assertEquals(0, adapter.presenceChecks);
    }

    @Test void internalCallbacksKeepPlayerIdentityAndUseEachDefinitionsSenderSetting() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              run-as-player: true
              runcmd: [spawn]
              on-success: ["rcc:next $arg1"]
            next:
              run-as-player: true
              runcmd: ["home $arg1"]
              on-success: [rcc:console]
            console:
              runcmd: [say audit]
            """);
        invoke(engine);
        assertEquals(List.of("spawn", "home Bob"), adapter.playerCommands);
        assertEquals(List.of(PLAYER, PLAYER), adapter.executingPlayers);
        assertEquals(List.of("say audit"), adapter.consoleCommands);
    }

    @Test void availabilityFailureSkipsPlayerExecutionAndUsesOnlyUnavailableCallbacks() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              check-server-available: remote
              run-as-player: true
              runcmd: [spawn]
              on-error: [say error]
              on-server-unavailable: ["say unavailable $attempted-server"]
            """);
        invoke(engine);
        var check = (Packet.Check) adapter.messages.getFirst();
        engine.receive(Packet.encode(new Packet.Response(check.id(), "origin", "remote", Packet.Code.ROUTING_FAILURE, "No player on destination backend")));
        assertEquals(List.of("say unavailable remote"), adapter.consoleCommands);
        assertTrue(adapter.playerCommands.isEmpty());
        assertEquals(0, adapter.presenceChecks);
    }

    @Test void targetOverrideAndBroadcastRequestsRetainInvokerUuid() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              broadcast: true
              run-as-player: true
              runcmd: [spawn]
            """);
        engine.invokeTarget("chosen", "test", List.of(), "Alice", PLAYER.toString(), true, n -> true);
        var override = (Packet.Request) adapter.messages.getFirst();
        assertEquals("chosen", override.target());
        assertEquals(PLAYER, override.playerUuid());
        invoke(engine);
        var discovery = (Packet.Discover) adapter.messages.getLast();
        engine.receive(Packet.encode(new Packet.Servers(discovery.id(), "origin", List.of("first", "second"))));
        for (Packet packet : adapter.messages.subList(2, 4)) {
            assertEquals(PLAYER, ((Packet.Request) packet).playerUuid());
        }
    }

    @Test void playerRequestRoundTripAndOldProtocolRejection() {
        var operation = new CurrencyAction.Operation(CurrencyAction.Type.SCOREBOARD, CurrencyAction.Action.ADD,
            "points", java.math.BigDecimal.ONE, "Bob", "");
        for (CurrencyAction.Operation currency : Arrays.asList(null, operation)) {
            var request = new Packet.Request(UUID.randomUUID(), "origin", "target", List.of("spawn"), currency, PLAYER);
            byte[] encoded = Packet.encode(request);
            assertEquals(request, Packet.decode(encoded));
            assertEquals(4, encoded[0]);
            encoded[0] = 3;
            assertThrows(IllegalArgumentException.class, () -> Packet.decode(encoded));
        }
    }

    private Map<String, CommandDefinition> config(String yaml) throws Exception {
        Files.writeString(temp.resolve("commands.yml"), yaml);
        return CommandConfig.load(temp);
    }
    private BackendEngine load(Adapter adapter, String yaml) throws Exception {
        var engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(config(yaml));
        return engine;
    }
    private static void invoke(BackendEngine engine) {
        engine.invoke("test", List.of("Bob"), "Alice", PLAYER.toString(), true, n -> true);
    }
    private static final class Adapter implements BackendEngine.Adapter {
        final List<Packet> messages = new ArrayList<>();
        final List<String> consoleCommands = new ArrayList<>(), playerCommands = new ArrayList<>(), diagnostics = new ArrayList<>(), logs = new ArrayList<>();
        final List<UUID> executingPlayers = new ArrayList<>();
        final List<CurrencyAction.Operation> operations = new ArrayList<>();
        CompletableFuture<CurrencyAction.Result> currencyResult = CompletableFuture.completedFuture(CurrencyAction.Result.ok());
        boolean online = true;
        int presenceChecks;
        public boolean send(byte[] bytes) { messages.add(Packet.decode(bytes)); return true; }
        public boolean dispatchConsole(String command) { consoleCommands.add(command); return true; }
        public boolean isPlayerAvailable(UUID uuid) { presenceChecks++; return online && uuid.equals(PLAYER); }
        public boolean dispatchPlayer(UUID uuid, String command) {
            if (!online || !uuid.equals(PLAYER)) return false;
            playerCommands.add(command); executingPlayers.add(uuid);
            if (command.equals("throws")) throw new IllegalArgumentException("command error");
            return !command.equals("denied");
        }
        public CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) {
            operations.add(operation); return currencyResult;
        }
        public void feedback(String uuid, String message) { diagnostics.add(message); }
        public void log(String message) { logs.add(message); }
    }
}
