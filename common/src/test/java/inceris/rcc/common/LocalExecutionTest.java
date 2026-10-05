package inceris.rcc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalExecutionTest {
    @TempDir Path temp;
    @Test void omittedServerRunsLocallyWithoutTransportAndResolvesCallbacks() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            local:
              runcmd: ["say $arg1 $server $target-server"]
              on-success: ["say success $target-server", "rcc:next $arg1"]
            next:
              runcmd: ["say next $arg1"]
            """);
        assertTrue(engine.definitions().get("local").servers().isEmpty());
        engine.invoke("local", List.of("Bob"), null, null, true, n -> true);
        assertEquals(List.of("say Bob origin origin", "say success origin", "say next Bob"), adapter.commands);
        assertEquals(0, adapter.messages.size());
        assertEquals(2, adapter.feedback.size());
        assertTrue(adapter.feedback.stream().allMatch(s -> s.contains("[origin]: SUCCESS")));
    }
    @Test void localCommandFailureAttemptsRemainingCommandsAndRunsErrorCallback() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            local:
              runcmd: [fail, "say later"]
              on-success: [say success]
              on-error: ["say failed $target-server"]
            """);
        engine.invoke("local", List.of(), null, null, true, n -> true);
        assertEquals(List.of("fail", "say later", "say failed origin"), adapter.commands);
        assertEquals(0, adapter.messages.size());
        assertTrue(adapter.feedback.getFirst().contains("COMMAND_FAILURE"));
    }
    @Test void localCurrencyWaitsForResultAndUsesNormalSuccessAndErrorHandling() throws Exception {
        for (boolean success : List.of(true, false)) {
            var adapter = new Adapter();
            var engine = load(adapter, """
                local:
                  currency: {type: scoreboard, action: add, currency: points, player: "$arg1", amount: "$arg2"}
                  runcmd: [say after]
                  on-success: ["say success $target-server"]
                  on-error: ["say failure $target-server"]
                """);
            engine.invoke("local", List.of("Bob", "5"), null, null, true, n -> true);
            assertEquals("Bob", adapter.currency.player());
            assertTrue(adapter.commands.isEmpty());
            assertTrue(adapter.feedback.isEmpty());
            adapter.result.complete(success ? CurrencyAction.Result.ok() : CurrencyAction.Result.failure("Insufficient balance"));
            assertEquals(success ? List.of("say after", "say success origin") : List.of("say failure origin"), adapter.commands);
            assertEquals(0, adapter.messages.size());
            assertEquals(1, adapter.feedback.size());
        }
    }
    @Test void permissionsTargetAndBroadcastStillApplyToDefinitionsWithoutServer() throws Exception {
        var adapter = new Adapter();
        var engine = load(adapter, """
            local:
              permission-node: rcc.local
              runcmd: [say local]
            broadcast:
              broadcast: true
              runcmd: [say all]
            """);
        engine.invoke("local", List.of(), "Alice", "id", true, n -> false);
        assertTrue(adapter.commands.isEmpty());
        assertTrue(adapter.messages.isEmpty());
        engine.invokeTarget("fabric", "local", List.of(), null, null, true, n -> true);
        assertEquals("fabric", ((Packet.Request) adapter.messages.getFirst()).target());
        assertTrue(adapter.commands.isEmpty());
        engine.invoke("broadcast", List.of(), null, null, true, n -> true);
        assertInstanceOf(Packet.Discover.class, adapter.messages.getLast());
        Files.writeString(temp.resolve("bad.yml"), "bad:\n  server: []\n  runcmd: [say bad]\n");
        assertThrows(IllegalArgumentException.class, () -> CommandConfig.load(temp));
    }
    private BackendEngine load(Adapter adapter, String yaml) throws Exception {
        Files.writeString(temp.resolve("commands.yml"), yaml);
        var engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(CommandConfig.load(temp));
        return engine;
    }
    private static final class Adapter implements BackendEngine.Adapter {
        final List<Packet> messages = new ArrayList<>();
        final List<String> commands = new ArrayList<>(), feedback = new ArrayList<>();
        final CompletableFuture<CurrencyAction.Result> result = new CompletableFuture<>();
        CurrencyAction.Operation currency;
        public boolean send(byte[] message) { messages.add(Packet.decode(message)); return false; }
        public boolean dispatchConsole(String command) { commands.add(command); return !command.equals("fail"); }
        public void feedback(String uuid, String message) { feedback.add(message); }
        public void log(String message) {}
        public CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) { currency = operation; return result; }
    }
}
