package inceris.rcc.common;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CurrencyTest {
    @TempDir Path temp;
    private static final String UUID_TEXT = "66d531e3-0dd4-4eea-92d7-8665b295117a";
    @Test void currencyOnlyConfigResolvesExplicitOfflinePlayerAndTargetOverride() throws Exception {
        Files.writeString(temp.resolve("money.yml"), """
            money:
              broadcast: true
              currency:
                type: impactor
                action: add
                currency: impactor:event_points
                amount: $arg2
                player: $arg1
            """);
        var definitions = CommandConfig.load(temp);
        var adapter = new Adapter();
        var engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(definitions);
        engine.invokeTarget("fabric", "money", List.of(UUID_TEXT, "12.50"), null, null, true, n -> true);
        var request = (Packet.Request) adapter.messages.getFirst();
        assertEquals("fabric", request.target());
        assertTrue(request.commands().isEmpty());
        assertEquals(UUID_TEXT, request.currency().player());
        assertEquals(new BigDecimal("12.50"), request.currency().amount());
        assertEquals(request, Packet.decode(Packet.encode(request)));
    }
    @Test void defaultPlayerUsesInvokerUuidAndConsoleMustSupplyPlayer() throws Exception {
        Files.writeString(temp.resolve("money.yml"), """
            money:
              server: paper
              currency: {type: vault, action: set, amount: 10}
              on-error: [say error]
            """);
        var adapter = new Adapter();
        var engine = new BackendEngine("origin", adapter);
        engine.setDefinitions(CommandConfig.load(temp));
        engine.invoke("money", List.of(), "Alice", UUID_TEXT, true, n -> true);
        var request = (Packet.Request) adapter.messages.getFirst();
        assertEquals(UUID_TEXT, request.currency().player());
        assertEquals("Alice", request.currency().nameHint());
        engine.invoke("money", List.of(), null, null, true, n -> true);
        assertEquals(1, adapter.messages.size());
        assertEquals(List.of("say error"), adapter.commands);
    }
    @Test void malformedCurrencyConfigurationsAreRejected() throws Exception {
        for (String block : List.of("{type: unknown, action: add, amount: 1}", "{type: vault, action: nope, amount: 1}",
                "{type: vault, action: add, amount: -1}", "{type: scoreboard, action: set, currency: points, amount: 1.5}",
                "{type: scoreboard, action: add, amount: 1}", "{type: vault, action: add}",
                "{type: vault, action: add, amount: .nan}")) {
            Files.writeString(temp.resolve("money.yml"), "money:\n  server: paper\n  currency: " + block + "\n");
            assertThrows(IllegalArgumentException.class, () -> CommandConfig.load(temp), block);
        }
    }
    @Test void asyncCurrencyRunsOnceBeforeCommandsAndResponsesAreReplayed() {
        var adapter = new Adapter();
        var engine = new BackendEngine("target", adapter);
        byte[] request = Packet.encode(request());
        engine.receive(request); engine.receive(request);
        assertEquals(1, adapter.operations.size());
        assertTrue(adapter.commands.isEmpty());
        assertTrue(adapter.messages.isEmpty());
        adapter.result.complete(CurrencyAction.Result.ok());
        assertEquals(List.of("say after"), adapter.commands);
        assertEquals(Packet.Code.SUCCESS, ((Packet.Response) adapter.messages.getFirst()).code());
        engine.receive(request);
        assertEquals(1, adapter.operations.size());
        assertEquals(1, adapter.commands.size());
        assertEquals(2, adapter.messages.size());
    }
    @Test void currencyFailureSkipsCommandsAndRunsOriginErrorCallback() {
        var origin = new Adapter(); var destination = new Adapter();
        var from = new BackendEngine("origin", origin); var to = new BackendEngine("target", destination);
        from.setDefinitions(Map.of("money", new CommandDefinition("money", null, List.of("target"), false, List.of("say after"), false, false, null,
            List.of("say success"), List.of("say error"), new CurrencyAction(CurrencyAction.Type.VAULT, CurrencyAction.Action.REMOVE, "default", "1", "$arg1"))));
        from.invoke("money", List.of(UUID_TEXT), null, null, true, n -> true);
        to.receive(Packet.encode(origin.messages.getFirst()));
        destination.result.complete(CurrencyAction.Result.failure("Insufficient balance"));
        assertTrue(destination.commands.isEmpty());
        var response = (Packet.Response) destination.messages.getFirst();
        assertEquals(Packet.Code.COMMAND_FAILURE, response.code());
        from.receive(Packet.encode(response)); from.receive(Packet.encode(response));
        assertEquals(List.of("say error"), origin.commands);
    }
    @Test void exceptionalProviderCompletesRequestWithFailure() {
        var adapter = new Adapter(); var engine = new BackendEngine("target", adapter);
        engine.receive(Packet.encode(request()));
        adapter.result.completeExceptionally(new IllegalStateException("storage failed"));
        assertEquals(Packet.Code.COMMAND_FAILURE, ((Packet.Response) adapter.messages.getFirst()).code());
        assertTrue(adapter.commands.isEmpty());
    }
    @Test void scoreboardArithmeticRejectsOverdraftOverflowAndFractionalAmounts() {
        var remove = operation(CurrencyAction.Action.REMOVE, "5");
        assertEquals(2, remove.scoreAfter(7));
        assertThrows(IllegalArgumentException.class, () -> remove.scoreAfter(3));
        assertEquals(5, operation(CurrencyAction.Action.SET, "5").scoreAfter(100));
        assertThrows(ArithmeticException.class, () -> operation(CurrencyAction.Action.ADD, "1").scoreAfter(Integer.MAX_VALUE));
        assertThrows(IllegalArgumentException.class, () -> operation(CurrencyAction.Action.ADD, "1.1"));
        assertThrows(IllegalArgumentException.class, () -> operation(CurrencyAction.Action.ADD, "1e100"));
        assertThrows(IllegalArgumentException.class, () -> Packet.decode(Packet.encode(new Packet.Request(UUID.randomUUID(), "origin", "target", List.of()))));
    }
    private static CurrencyAction.Operation operation(CurrencyAction.Action action, String amount) {
        return new CurrencyAction.Operation(CurrencyAction.Type.SCOREBOARD, action, "points", new BigDecimal(amount), UUID_TEXT, "Alice");
    }
    private static Packet.Request request() {
        return new Packet.Request(UUID.randomUUID(), "origin", "target", List.of("say after"), operation(CurrencyAction.Action.ADD, "2"));
    }
    private static class Adapter implements BackendEngine.Adapter {
        final List<Packet> messages = new ArrayList<>();
        final List<String> commands = new ArrayList<>();
        final List<CurrencyAction.Operation> operations = new ArrayList<>();
        final CompletableFuture<CurrencyAction.Result> result = new CompletableFuture<>();
        public boolean send(byte[] bytes) { messages.add(Packet.decode(bytes)); return true; }
        public boolean dispatchConsole(String command) { commands.add(command); return true; }
        public void feedback(String uuid, String message) {}
        public void log(String message) {}
        public CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) { operations.add(operation); return result; }
    }
}
