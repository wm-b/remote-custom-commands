package inceris.rcc.common;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.*;

public final class BackendEngine {
    public interface Adapter {
        boolean send(byte[] message);
        boolean dispatchConsole(String command);
        default boolean isPlayerAvailable(UUID playerUuid) { return false; }
        default boolean dispatchPlayer(UUID playerUuid, String command) { return false; }
        void feedback(String playerUuid, String message);
        void log(String message);
        default CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) {
            return CompletableFuture.completedFuture(CurrencyAction.Result.failure("Currency integration unavailable"));
        }
        default void execute(Runnable task) { task.run(); }
    }
    private record Pending(CommandDefinition definition, List<String> args, String player, String uuid, long deadline, int depth, String target) {}
    private record PendingCheck(Pending item, AvailabilityBatch batch) {}
    private static final class AvailabilityBatch {
        final Pending invocation;
        private int remaining;
        private boolean failed;
        AvailabilityBatch(Pending invocation, int remaining) { this.invocation = invocation; this.remaining = remaining; }
        synchronized boolean complete(boolean success) {
            failed |= !success;
            return --remaining == 0 && !failed;
        }
    }
    private record Completed(Packet.Response response, long expiresAt) {}
    private final String server;
    private final Adapter adapter;
    private final java.util.function.LongSupplier clock;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();
    private final Map<UUID, PendingCheck> checks = new ConcurrentHashMap<>();
    private final Map<UUID, Pending> discoveries = new ConcurrentHashMap<>();
    private final Map<UUID, Completed> completed = new ConcurrentHashMap<>();
    private final Set<UUID> executing = ConcurrentHashMap.newKeySet();
    private volatile Map<String, CommandDefinition> definitions = Map.of();
    public BackendEngine(String server, Adapter adapter) { this(server, adapter, System::currentTimeMillis); }
    BackendEngine(String server, Adapter adapter, java.util.function.LongSupplier clock) {
        this.server = server; this.adapter = adapter; this.clock = clock;
    }
    public void setDefinitions(Map<String, CommandDefinition> definitions) { this.definitions = Map.copyOf(definitions); }
    public Map<String, CommandDefinition> definitions() { return definitions; }
    public void invoke(String id, List<String> args, String player, String uuid, boolean op, java.util.function.Predicate<String> permission) {
        invoke(id, args, player, uuid, op, permission, 0, false, null);
    }
    public void invokeTarget(String target, String id, List<String> args, String player, String uuid, boolean op, java.util.function.Predicate<String> permission) {
        if (target == null || !target.matches("[a-zA-Z0-9_-]+")) {
            adapter.feedback(uuid, "Invalid RCC target server name."); return;
        }
        invoke(id, args, player, uuid, op, permission, 0, false, target);
    }
    private void invoke(String id, List<String> args, String player, String uuid, boolean op, java.util.function.Predicate<String> permission, int depth, boolean internal, String targetOverride) {
        if (depth > 4) { adapter.log("Callback chain limit reached"); return; }
        CommandDefinition definition = definitions.get(id);
        if (definition == null) { adapter.feedback(uuid, "Unknown RCC command: " + id); return; }
        if (!internal && player != null && definition.permissionRequired() && !(definition.permissionNode() == null ? op : permission.test(definition.permissionNode()))) {
            adapter.feedback(uuid, "You do not have permission."); return;
        }
        if (definition.checkServerAvailable().isEmpty()) executeInvocation(definition, args, player, uuid, depth, targetOverride);
        else checkAvailability(definition, args, player, uuid, depth, targetOverride);
    }
    private void checkAvailability(CommandDefinition definition, List<String> args, String player, String uuid, int depth, String targetOverride) {
        Map<String, String> targets = new LinkedHashMap<>();
        for (String template : definition.checkServerAvailable()) {
            String target = template, error = null;
            try {
                target = Placeholders.resolve(template, player, uuid, args, server, "");
                if (!target.matches("[a-zA-Z0-9_-]+")) error = "Invalid resolved RCC server name: '" + target + "'";
            } catch (RuntimeException e) { error = "Invalid server template: " + e.getMessage(); }
            targets.putIfAbsent(target, error);
        }
        Pending invocation = new Pending(definition, List.copyOf(args), player, uuid, 0, depth, targetOverride);
        AvailabilityBatch batch = new AvailabilityBatch(invocation, targets.size());
        for (var entry : targets.entrySet()) {
            UUID id = UUID.randomUUID();
            Pending item = new Pending(definition, invocation.args(), player, uuid, clock.getAsLong() + 10_000, depth, entry.getKey());
            checks.put(id, new PendingCheck(item, batch));
            if (entry.getValue() != null) {
                finish(id, new Packet.Response(id, server, item.target(), Packet.Code.INVALID_REQUEST, entry.getValue()));
                continue;
            }
            try {
                if (!adapter.send(Packet.encode(new Packet.Check(id, server, item.target()))))
                    finish(id, new Packet.Response(id, server, item.target(), Packet.Code.TRANSPORT_FAILURE, "No player carrier available"));
            } catch (RuntimeException e) {
                finish(id, new Packet.Response(id, server, item.target(), Packet.Code.INVALID_REQUEST, e.getMessage()));
            }
        }
    }
    private void executeInvocation(CommandDefinition definition, List<String> args, String player, String uuid, int depth, String targetOverride) {
        if (targetOverride != null) {
            sendRequest(definition, args, player, uuid, depth, targetOverride);
        } else if (definition.broadcast()) {
            UUID discoveryId = UUID.randomUUID();
            Pending item = new Pending(definition, List.copyOf(args), player, uuid, clock.getAsLong() + 10_000, depth, "broadcast");
            discoveries.put(discoveryId, item);
            try {
                if (!adapter.send(Packet.encode(new Packet.Discover(discoveryId, server))) && discoveries.remove(discoveryId, item))
                    callbacks(discoveryId, item, Packet.Code.TRANSPORT_FAILURE, "No player carrier available");
            } catch (RuntimeException e) {
                if (discoveries.remove(discoveryId, item)) callbacks(discoveryId, item, Packet.Code.INVALID_REQUEST, e.getMessage());
            }
        } else if (definition.servers().isEmpty()) {
            sendRequest(definition, args, player, uuid, depth, server, true);
        } else {
            Set<String> targets = new LinkedHashSet<>();
            for (String template : definition.servers()) {
                String target;
                try { target = Placeholders.resolve(template, player, uuid, args, server, ""); }
                catch (RuntimeException e) {
                    Pending item = new Pending(definition, List.copyOf(args), player, uuid, 0, depth, template);
                    callbacks(UUID.randomUUID(), item, Packet.Code.INVALID_REQUEST, "Invalid server template: " + e.getMessage());
                    continue;
                }
                if (targets.add(target)) sendRequest(definition, args, player, uuid, depth, target);
            }
        }
    }
    private void sendRequest(CommandDefinition definition, List<String> args, String player, String uuid, int depth, String target) {
        sendRequest(definition, args, player, uuid, depth, target, false);
    }
    private void sendRequest(CommandDefinition definition, List<String> args, String player, String uuid, int depth, String target, boolean local) {
        UUID requestId = UUID.randomUUID();
        Pending item = new Pending(definition, List.copyOf(args), player, uuid, clock.getAsLong() + 10_000, depth, target);
        pending.put(requestId, item);
        try {
            if (!target.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("Invalid resolved RCC server name: '" + target + "'");
            List<String> commands = definition.runCommands().stream().map(s -> Placeholders.resolve(s, player, uuid, args, server, target)).toList();
            UUID executingPlayer = null;
            if (definition.runAsPlayer() && !commands.isEmpty()) {
                if (player == null || uuid == null) throw new IllegalArgumentException("run-as-player requires a player invocation");
                executingPlayer = UUID.fromString(uuid);
            }
            var currency = definition.currency() == null ? null : definition.currency().resolve(player, uuid, args, server, target);
            byte[] message = Packet.encode(new Packet.Request(requestId, server, target, commands, currency, executingPlayer));
            if (local) executeRequest((Packet.Request) Packet.decode(message), true);
            else if (!adapter.send(message)) {
                finish(requestId, new Packet.Response(requestId, server, target, Packet.Code.TRANSPORT_FAILURE, "No player carrier available"));
            }
        } catch (RuntimeException e) {
            finish(requestId, new Packet.Response(requestId, server, target, Packet.Code.INVALID_REQUEST, e.getMessage()));
        }
    }
    public void receive(byte[] bytes) {
        Packet packet;
        try { packet = Packet.decode(bytes); } catch (IllegalArgumentException e) { adapter.log("Rejected packet: " + e.getMessage()); return; }
        if (packet instanceof Packet.Servers servers && servers.origin().equals(server)) {
            Pending item = discoveries.remove(servers.id());
            if (item == null) return;
            if (servers.names().isEmpty()) callbacks(servers.id(), item, Packet.Code.ROUTING_FAILURE, "No broadcast backends available");
            else for (String target : new LinkedHashSet<>(servers.names())) sendRequest(item.definition(), item.args(), item.player(), item.uuid(), item.depth(), target);
        } else if (packet instanceof Packet.Response response) { if (response.origin().equals(server)) finish(response.id(), response); }
        else if (packet instanceof Packet.Request request && request.target().equals(server)) {
            executeRequest(request, false);
        } else if (packet instanceof Packet.Check check && check.target().equals(server)) {
            // An availability check uses normal response deduplication without any command or currency action.
            executeRequest(new Packet.Request(check.id(), check.origin(), check.target(), List.of()), false);
        }
    }
    private void executeRequest(Packet.Request request, boolean local) {
        Completed old = completed.get(request.id());
        if (old != null) { deliverResponse(old.response(), local); return; }
        if (!executing.add(request.id())) return;
        if (request.playerUuid() != null) {
            try {
                if (!adapter.isPlayerAvailable(request.playerUuid())) {
                    completeRequest(request, CurrencyAction.Result.failure("Executing player is not online on destination backend"), local);
                    return;
                }
            } catch (RuntimeException e) {
                completeRequest(request, CurrencyAction.Result.failure("Executing player unavailable: " + e.getMessage()), local);
                return;
            }
        }
        if (request.currency() == null) completeRequest(request, CurrencyAction.Result.ok(), local);
        else {
            try {
                adapter.applyCurrency(request.currency()).whenComplete((result, error) -> adapter.execute(() ->
                    completeRequest(request, error == null && result != null ? result : CurrencyAction.Result.failure("Currency failed: " + (error == null ? "no result" : error.getMessage())), local)));
            } catch (RuntimeException | LinkageError e) { completeRequest(request, CurrencyAction.Result.failure("Currency failed: " + e.getMessage()), local); }
        }
    }
    private void completeRequest(Packet.Request request, CurrencyAction.Result currency, boolean local) {
        boolean success = currency.success();
        if (success) for (String command : request.commands()) {
            try { success &= request.playerUuid() == null ? adapter.dispatchConsole(command) : adapter.dispatchPlayer(request.playerUuid(), command); }
            catch (RuntimeException e) { success = false; adapter.log("Command failed for " + request.id() + ": " + e.getMessage()); }
        }
        String detail = currency.success() ? (success ? "OK" : "Command returned failure") : String.valueOf(currency.detail());
        if (detail.length() > 1024) detail = detail.substring(0, 1024);
        Packet.Response response = new Packet.Response(request.id(), request.origin(), server, success ? Packet.Code.SUCCESS : Packet.Code.COMMAND_FAILURE, detail);
        completed.put(request.id(), new Completed(response, clock.getAsLong() + 600_000));
        executing.remove(request.id());
        deliverResponse(response, local);
    }
    private void deliverResponse(Packet.Response response, boolean local) {
        if (local) finish(response.id(), response);
        else adapter.send(Packet.encode(response));
    }
    public void tick() {
        long now = clock.getAsLong();
        checks.forEach((id, check) -> {
            if (now >= check.item().deadline()) finish(id, new Packet.Response(id, server, check.item().target(), Packet.Code.TIMEOUT, "No response to availability check"));
        });
        pending.forEach((id, item) -> { if (now >= item.deadline()) finish(id, new Packet.Response(id, server, item.target(), Packet.Code.TIMEOUT, "No response")); });
        discoveries.forEach((id, item) -> {
            if (now >= item.deadline() && discoveries.remove(id, item)) callbacks(id, item, Packet.Code.TIMEOUT, "No broadcast server list received");
        });
        completed.entrySet().removeIf(entry -> now >= entry.getValue().expiresAt());
    }
    private void finish(UUID id, Packet.Response response) {
        PendingCheck check = checks.get(id);
        if (check != null) {
            Pending item = check.item();
            if (!response.target().equals(item.target()) || !response.origin().equals(server) || !checks.remove(id, check)) return;
            boolean ready = check.batch().complete(response.code() == Packet.Code.SUCCESS);
            if (response.code() != Packet.Code.SUCCESS)
                callbacks(id, item, response.code(), response.detail(), item.definition().onServerUnavailable());
            if (ready) {
                Pending invocation = check.batch().invocation;
                executeInvocation(invocation.definition(), invocation.args(), invocation.player(), invocation.uuid(), invocation.depth(), invocation.target());
            }
            return;
        }
        Pending item = pending.get(id);
        if (item == null || !response.target().equals(item.target()) || !response.origin().equals(server) || !pending.remove(id, item)) return;
        callbacks(id, item, response.code(), response.detail());
    }
    private void callbacks(UUID id, Pending item, Packet.Code code, String detail) {
        List<String> callbacks = code == Packet.Code.SUCCESS ? item.definition().onSuccess() : item.definition().onError();
        callbacks(id, item, code, detail, callbacks);
    }
    private void callbacks(UUID id, Pending item, Packet.Code code, String detail, List<String> callbacks) {
        // Request diagnostics belong in the console; player-facing command errors use explicit recipients.
        adapter.feedback(null, "RCC " + id + " [" + item.target() + "]: " + code + " (" + detail + ")");
        for (String callback : callbacks) {
            try {
                String resolved = Placeholders.resolveCallback(callback, item.player(), item.uuid(), item.args(), server, item.target()).strip();
                String[] parts = resolved.split("\\s+", 2);
                String callbackCommand = parts[0];
                String callbackArguments = parts.length == 2 ? parts[1] : "";
                if (callbackCommand.startsWith("rcc:")) {
                    String targetId = callbackCommand.substring(4);
                    if (!targetId.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid callback identifier");
                    if (targetId.equals(item.definition().id())) throw new IllegalArgumentException("callback recursion");
                    invoke(targetId, Arguments.parse(callbackArguments), item.player(), item.uuid(), true, node -> true, item.depth() + 1, true, null);
                } else if (!adapter.dispatchConsole(resolved)) adapter.log("Callback failed for " + id);
            } catch (RuntimeException e) { adapter.log("Callback failed for " + id + ": " + e.getMessage()); }
        }
    }
}
