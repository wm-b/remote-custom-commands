package inceris.rcc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class AvailabilityTest {
    @TempDir Path temp;
    private long now = 1_000;

    @Test void scalarListAndDefaultsLoadAndInvalidChecksAreRejected() throws Exception {
        var config = config("""
            scalar:
              check-server-available: paper
              on-server-unavailable: ["say unavailable $attempted-server"]
              runcmd: [say hi]
            list:
              check-server-available: [paper, "$arg1", paper]
              runcmd: [say hi]
            default:
              runcmd: [say hi]
            """);
        assertEquals(List.of("paper"), config.get("scalar").checkServerAvailable());
        assertEquals(List.of("say unavailable $attempted-server"), config.get("scalar").onServerUnavailable());
        assertEquals(List.of("paper", "$arg1"), config.get("list").checkServerAvailable());
        assertTrue(config.get("default").checkServerAvailable().isEmpty());
        assertTrue(config.get("default").onServerUnavailable().isEmpty());
        for (String invalid : List.of("[bad/server]", "['$target-server']", "['$attempted-server']", "[42]", "true", "['']")) {
            assertThrows(IllegalArgumentException.class, () -> config("bad:\n  runcmd: [say hi]\n  check-server-available: " + invalid + "\n"), invalid);
        }
    }

    @Test void allUniqueChecksMustSucceedBeforeAnyExecutionAndDuplicateRepliesAreIgnored() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              server: [destination-a, destination-b]
              check-server-available: [paper, "$arg1", paper]
              runcmd: ["say $arg2 $target-server"]
              on-success: ["say OK $attempted-server"]
              on-error: ["say FAILED $attempted-server"]
              on-server-unavailable: ["say unavailable $attempted-server"]
            """);
        invoke(engine, "test", List.of("fabric", "Bob"));
        var paper = (Packet.Check) adapter.messages.get(0);
        var fabric = (Packet.Check) adapter.messages.get(1);
        assertEquals(List.of("paper", "fabric"), List.of(paper.target(), fabric.target()));
        reply(engine, fabric, Packet.Code.SUCCESS);
        reply(engine, fabric, Packet.Code.SUCCESS);
        assertEquals(2, adapter.messages.size());
        assertTrue(adapter.commands.isEmpty());
        reply(engine, paper, Packet.Code.SUCCESS);
        reply(engine, paper, Packet.Code.SUCCESS);
        assertEquals(4, adapter.messages.size());
        var first = (Packet.Request) adapter.messages.get(2);
        var second = (Packet.Request) adapter.messages.get(3);
        assertEquals(List.of("say Bob destination-a"), first.commands());
        assertEquals(List.of("say Bob destination-b"), second.commands());
        reply(engine, second, Packet.Code.COMMAND_FAILURE);
        reply(engine, first, Packet.Code.SUCCESS);
        reply(engine, second, Packet.Code.COMMAND_FAILURE);
        assertEquals(List.of("say FAILED destination-b", "say OK destination-a"), adapter.commands);
    }

    @Test void eachFailedCheckRunsOnlyUnavailableCallbacksAndBlocksCommandsAndCurrency() throws Exception {
        for (Packet.Code failure : Packet.Code.values()) {
            if (failure == Packet.Code.SUCCESS) continue;
            var adapter = new Adapter();
            var engine = load(adapter, gatedCurrency());
            invoke(engine, "test", List.of("Bob"));
            var first = (Packet.Check) adapter.messages.get(0);
            var second = (Packet.Check) adapter.messages.get(1);
            reply(engine, first, failure);
            reply(engine, first, failure);
            reply(engine, second, Packet.Code.ROUTING_FAILURE);
            reply(engine, second, Packet.Code.SUCCESS);
            assertEquals(List.of("say unavailable paper Bob origin paper", "say unavailable fabric Bob origin fabric"), adapter.commands, failure.toString());
            assertTrue(adapter.operations.isEmpty());
            assertEquals(2, adapter.messages.size());
        }
    }

    @Test void oneFailureStillBlocksExecutionAfterOtherChecksSucceed() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, gatedCurrency());
        invoke(engine, "test", List.of("Bob"));
        reply(engine, (Packet.Check) adapter.messages.getFirst(), Packet.Code.ROUTING_FAILURE);
        reply(engine, (Packet.Check) adapter.messages.getLast(), Packet.Code.SUCCESS);
        assertEquals(List.of("say unavailable paper Bob origin paper"), adapter.commands);
        assertTrue(adapter.operations.isEmpty());
        assertEquals(2, adapter.messages.size());
    }

    @Test void transportFailureAndSendExceptionRunOnlyUnavailableCallbacks() throws Exception {
        for (boolean throwsError : List.of(false, true)) {
            var adapter = new Adapter();
            adapter.available = false;
            adapter.throwsError = throwsError;
            var engine = load(adapter, gatedCurrency());
            invoke(engine, "test", List.of("Bob"));
            assertEquals(List.of("say unavailable paper Bob origin paper", "say unavailable fabric Bob origin fabric"), adapter.commands);
            assertTrue(adapter.operations.isEmpty());
            assertEquals(2, adapter.messages.size());
        }
    }

    @Test void unansweredChecksTimeoutOnceAndLateSuccessCannotReleaseGate() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, gatedCurrency());
        invoke(engine, "test", List.of("Bob"));
        var first = (Packet.Check) adapter.messages.getFirst();
        var second = (Packet.Check) adapter.messages.getLast();
        reply(engine, first, Packet.Code.SUCCESS);
        now += 9_999;
        engine.tick();
        assertTrue(adapter.commands.isEmpty());
        now++;
        engine.tick(); engine.tick();
        reply(engine, second, Packet.Code.SUCCESS);
        assertEquals(List.of("say unavailable fabric Bob origin fabric"), adapter.commands);
        assertTrue(adapter.operations.isEmpty());
        assertEquals(2, adapter.messages.size());
    }

    @Test void mismatchedRepliesCannotReleaseGate() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, gatedCurrency());
        invoke(engine, "test", List.of("Bob"));
        var check = (Packet.Check) adapter.messages.getFirst();
        engine.receive(Packet.encode(new Packet.Response(check.id(), "wrong", check.target(), Packet.Code.SUCCESS, "OK")));
        engine.receive(Packet.encode(new Packet.Response(check.id(), "origin", "wrong", Packet.Code.SUCCESS, "OK")));
        reply(engine, (Packet.Check) adapter.messages.getLast(), Packet.Code.SUCCESS);
        assertTrue(adapter.operations.isEmpty());
        assertEquals(2, adapter.messages.size());
        reply(engine, check, Packet.Code.SUCCESS);
        assertEquals(1, adapter.operations.size());
        assertEquals(List.of("say executed", "say success"), adapter.commands);
    }

    @Test void checksUsePluginMessagesEvenForOriginAndExecuteNoCommandsAtDestination() throws Exception {
        var origin = new Adapter();
        var engine = load(origin, """
            test:
              check-server-available: origin
              runcmd: [say local]
              on-success: ["say OK $attempted-server"]
            """);
        invoke(engine, "test", List.of());
        var check = (Packet.Check) origin.messages.getFirst();
        assertTrue(origin.commands.isEmpty());
        var destination = new Adapter();
        var backend = new BackendEngine("origin", destination);
        backend.receive(Packet.encode(check)); backend.receive(Packet.encode(check));
        assertTrue(destination.commands.isEmpty());
        assertTrue(destination.operations.isEmpty());
        assertEquals(2, destination.messages.size());
        assertEquals(destination.messages.getFirst(), destination.messages.getLast());
        engine.receive(Packet.encode(destination.messages.getFirst()));
        engine.receive(Packet.encode(destination.messages.getLast()));
        assertEquals(List.of("say local", "say OK origin"), origin.commands);
        assertEquals(1, origin.messages.size());
    }

    @Test void synchronousResponsesStillWaitForAllChecksAndDeduplicateResolvedNames() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              check-server-available: [paper, "$arg1", fabric]
              runcmd: [say local]
            """);
        adapter.replyTo = engine;
        invoke(engine, "test", List.of("paper"));
        assertEquals(2, adapter.messages.size());
        assertEquals(List.of("say local"), adapter.commands);
        assertEquals(2, adapter.messagesAtDispatch);
    }

    @Test void invalidResolvedNamesBlockExecutionWithoutFallingBackToLocal() throws Exception {
        for (List<String> args : List.of(List.<String>of(), List.of("bad/server"), List.of("two servers"))) {
            var adapter = new Adapter();
            var engine = load(adapter, """
                test:
                  check-server-available: "$arg1"
                  runcmd: [say executed]
                  on-error: [say error]
                  on-server-unavailable: ["say unavailable <$attempted-server>"]
                """);
            invoke(engine, "test", args);
            assertEquals(List.of("say unavailable <" + (args.isEmpty() ? "" : args.getFirst()) + ">"), adapter.commands);
            assertTrue(adapter.messages.isEmpty());
        }
    }

    @Test void gatePrecedesBroadcastDiscoveryAndTargetOverride() throws Exception {
        for (boolean override : List.of(false, true)) {
            var adapter = new Adapter();
            var engine = load(adapter, """
                test:
                  broadcast: true
                  check-server-available: paper
                  runcmd: [say hi]
                """);
            if (override) engine.invokeTarget("chosen", "test", List.of(), null, null, true, n -> true);
            else invoke(engine, "test", List.of());
            assertEquals(1, adapter.messages.size());
            reply(engine, (Packet.Check) adapter.messages.getFirst(), Packet.Code.SUCCESS);
            if (override) assertEquals("chosen", ((Packet.Request) adapter.messages.getLast()).target());
            else assertInstanceOf(Packet.Discover.class, adapter.messages.getLast());
        }
    }

    @Test void unavailableCallbacksCanInvokeDefinitionsWithAttemptedServerAsExplicitArgument() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            test:
              check-server-available: paper
              runcmd: [say never]
              on-error: [say error]
              on-server-unavailable: ["rcc:next $attempted-server $arg1", "say later"]
            next:
              runcmd: ["say next $arg1 $arg2 $player $uuid"]
            """);
        engine.invoke("test", List.of("Bob"), "Alice", "player-uuid", true, n -> true);
        reply(engine, (Packet.Check) adapter.messages.getFirst(), Packet.Code.ROUTING_FAILURE);
        assertEquals(List.of("say next paper Bob Alice player-uuid", "say later"), adapter.commands);
    }

    @Test void permissionsAreCheckedBeforeAvailabilityMessages() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, gatedCurrency());
        engine.invoke("test", List.of("Bob"), "Alice", "id", false, n -> false);
        assertTrue(adapter.messages.isEmpty());
        assertTrue(adapter.commands.isEmpty());
    }

    @Test void requestResultsAreLoggedWithoutPlayerRecipientsButPermissionErrorsStillReachPlayers() throws Exception {
        for (Packet.Code code : List.of(Packet.Code.SUCCESS, Packet.Code.COMMAND_FAILURE, Packet.Code.ROUTING_FAILURE)) {
            var adapter = new Adapter();
            var engine = load(adapter, """
                test:
                  server: paper
                  runcmd: [say remote]
                """);
            engine.invoke("test", List.of(), "Alice", "player-uuid", true, n -> true);
            reply(engine, (Packet.Request) adapter.messages.getFirst(), code);
            assertEquals(1, adapter.consoleFeedback.size());
            assertTrue(adapter.consoleFeedback.getFirst().contains(code.name()));
            assertTrue(adapter.playerFeedback.isEmpty());
        }
        var adapter = new Adapter();
        var engine = load(adapter, gatedCurrency());
        engine.invoke("test", List.of("Bob"), "Alice", "player-uuid", true, n -> true);
        reply(engine, (Packet.Check) adapter.messages.getFirst(), Packet.Code.ROUTING_FAILURE);
        assertEquals(1, adapter.consoleFeedback.size());
        assertTrue(adapter.playerFeedback.isEmpty());
        engine.invoke("test", List.of("Bob"), "Alice", "player-uuid", false, n -> false);
        assertEquals(List.of("You do not have permission."), adapter.playerFeedback);
    }

    @Test void checkProtocolValidatesNamesAndCallbackPlaceholderIsLiteralOutsideCallbacks() {
        var check = new Packet.Check(UUID.randomUUID(), "origin", "paper");
        assertEquals(check, Packet.decode(Packet.encode(check)));
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(Packet.encode(new Packet.Check(check.id(), "origin", "bad/server"))));
        assertFalse(Placeholders.isServerTemplate("$attempted-server"));
        assertEquals("$attempted-server", Placeholders.resolve("$attempted-server", null, null, List.of(), "origin", "paper"));
        assertEquals("paper $attempted-server", Placeholders.resolveCallback("$attempted-server $arg1", null, null, List.of("$attempted-server"), "origin", "paper"));
    }

    private static String gatedCurrency() {
        return """
            test:
              check-server-available: [paper, fabric]
              currency: {type: scoreboard, action: add, currency: points, amount: 1, player: Bob}
              runcmd: [say executed]
              on-success: [say success]
              on-error: [say error]
              on-server-unavailable: ["say unavailable $attempted-server $arg1 $server $target-server"]
            """;
    }
    private Map<String, CommandDefinition> config(String yaml) throws Exception {
        Files.writeString(temp.resolve("commands.yml"), yaml);
        return CommandConfig.load(temp);
    }
    private BackendEngine load(Adapter adapter, String yaml) throws Exception {
        var engine = new BackendEngine("origin", adapter, () -> now);
        engine.setDefinitions(config(yaml));
        return engine;
    }
    private static void invoke(BackendEngine engine, String id, List<String> args) {
        engine.invoke(id, args, null, null, true, n -> true);
    }
    private static void reply(BackendEngine engine, Packet.Check check, Packet.Code code) {
        engine.receive(Packet.encode(new Packet.Response(check.id(), check.origin(), check.target(), code, "result")));
    }
    private static void reply(BackendEngine engine, Packet.Request request, Packet.Code code) {
        engine.receive(Packet.encode(new Packet.Response(request.id(), request.origin(), request.target(), code, "result")));
    }
    private static final class Adapter implements BackendEngine.Adapter {
        final List<Packet> messages = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<String> consoleFeedback = new ArrayList<>(), playerFeedback = new ArrayList<>();
        final List<CurrencyAction.Operation> operations = new ArrayList<>();
        boolean available = true, throwsError;
        int messagesAtDispatch;
        BackendEngine replyTo;
        public boolean send(byte[] bytes) {
            Packet packet = Packet.decode(bytes);
            messages.add(packet);
            if (throwsError) throw new IllegalStateException("transport failed");
            if (available && replyTo != null && packet instanceof Packet.Check check) reply(replyTo, check, Packet.Code.SUCCESS);
            return available;
        }
        public boolean dispatchConsole(String command) { commands.add(command); messagesAtDispatch = messages.size(); return true; }
        public CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) {
            operations.add(operation); return CompletableFuture.completedFuture(CurrencyAction.Result.ok());
        }
        public void feedback(String uuid, String message) {
            consoleFeedback.add(message);
            if (uuid != null) playerFeedback.add(message);
        }
        public void log(String message) {}
    }
}
