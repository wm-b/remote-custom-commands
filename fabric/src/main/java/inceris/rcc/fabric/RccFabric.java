package inceris.rcc.fabric;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import inceris.rcc.common.*;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.luckperms.api.LuckPermsProvider;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.packet.s2c.common.CustomPayloadS2CPacket;
import net.minecraft.text.Text;
import net.minecraft.command.CommandSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.*;
import static net.minecraft.server.command.CommandManager.*;

public final class RccFabric implements ModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("rcc");
    private MinecraftServer server;
    private BackendEngine engine;
    private final Path directory = FabricLoader.getInstance().getConfigDir().resolve("rcc");
    @Override public void onInitialize() {
        LOG.info(BuildInfo.LOAD_MESSAGE);
        PayloadTypeRegistry.playC2S().register(RccPayload.ID, RccPayload.CODEC);
        PayloadTypeRegistry.playS2C().register(RccPayload.ID, RccPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(RccPayload.ID, (payload, context) -> context.server().execute(() -> engine.receive(payload.data())));
        ServerLifecycleEvents.SERVER_STARTED.register(this::start);
        ServerTickEvents.END_SERVER_TICK.register(s -> { if (s.getTicks() % 20 == 0 && engine != null) engine.tick(); });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            var management = literal("rcc");
            management.then(literal("reload").requires(src -> src.hasPermissionLevel(4)).executes(ctx -> { reload(); return 1; }));
            var executeName = argument("name", StringArgumentType.word())
                .executes(ctx -> invoke(ctx.getSource(), StringArgumentType.getString(ctx, "name"), ""))
                .then(argument("args", StringArgumentType.greedyString()).executes(ctx -> invoke(ctx.getSource(), StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "args"))));
            management.then(literal("execute").then(executeName));
            var targetName = argument("name", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(engine == null ? Set.<String>of() : engine.definitions().keySet(), builder))
                .executes(ctx -> invoke(ctx.getSource(), StringArgumentType.getString(ctx, "name"), "", StringArgumentType.getString(ctx, "server")))
                .then(argument("args", StringArgumentType.greedyString()).executes(ctx -> invoke(ctx.getSource(), StringArgumentType.getString(ctx, "name"), StringArgumentType.getString(ctx, "args"), StringArgumentType.getString(ctx, "server"))));
            var targetServer = argument("server", StringArgumentType.word())
                .suggests((ctx, builder) -> CommandSource.suggestMatching(engine == null ? List.<String>of() : engine.definitions().values().stream().flatMap(d -> d.servers().stream()).filter(s -> !s.contains("$")).distinct().sorted().toList(), builder))
                .then(targetName);
            management.then(literal("target").then(targetServer));
            dispatcher.register(management);
            Map<String, CommandDefinition> bindings;
            try { bindings = engine == null ? CommandConfig.loadAvailable(directory.resolve("commands"), LOG::warn) : engine.definitions(); }
            catch (Exception e) { LOG.error("Could not register RCC commands: {}", e.getMessage()); bindings = Map.of(); }
            for (CommandDefinition definition : bindings.values()) if (definition.register()) {
                if (dispatcher.getRoot().getChild(definition.binding()) != null) { LOG.error("RCC binding conflicts with existing command: {}", definition.binding()); continue; }
                var node = literal(definition.binding()).executes(ctx -> invoke(ctx.getSource(), definition.id(), ""))
                    .then(argument("args", StringArgumentType.greedyString()).executes(ctx -> invoke(ctx.getSource(), definition.id(), StringArgumentType.getString(ctx, "args"))));
                dispatcher.register(node);
            }
        });
    }
    private void start(MinecraftServer server) {
        this.server = server;
        try { Files.createDirectories(directory); } catch (Exception e) { throw new IllegalStateException(e); }
        Path settings = directory.resolve("server-name.txt");
        String name;
        try {
            if (!Files.exists(settings)) Files.writeString(settings, "survival\n");
            name = Files.readString(settings).trim();
        } catch (Exception e) { throw new IllegalStateException(e); }
        if (!name.matches("[a-zA-Z0-9_-]+")) throw new IllegalStateException("Invalid RCC server-name.txt");
        engine = new BackendEngine(name, new BackendEngine.Adapter() {
            public boolean send(byte[] bytes) {
                if (server.getPlayerManager().getPlayerList().isEmpty()) return false;
                server.getPlayerManager().getPlayerList().getFirst().networkHandler.sendPacket(new CustomPayloadS2CPacket(new RccPayload(bytes))); return true;
            }
            public boolean dispatchConsole(String command) {
                String actual = command.startsWith("/") ? command.substring(1) : command;
                try { return server.getCommandManager().getDispatcher().execute(actual, server.getCommandSource()) > 0; }
                catch (CommandSyntaxException e) { LOG.warn("RCC command failed: {}", e.getMessage()); return false; }
            }
            public void feedback(String uuid, String message) {
                LOG.info(message);
                if (uuid != null) {
                    ServerPlayerEntity recipient = server.getPlayerManager().getPlayer(UUID.fromString(uuid));
                    if (recipient != null) recipient.sendMessage(Text.literal(message));
                }
            }
            public void log(String message) { LOG.warn(message); }
            public java.util.concurrent.CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) {
                return FabricCurrency.apply(server, operation);
            }
            public void execute(Runnable task) { server.execute(task); }
        });
        reload(false);
    }
    private void reload() { reload(true); }
    private void reload(boolean refreshBindings) {
        try {
            var next = refreshBindings ? CommandConfig.load(directory.resolve("commands")) : CommandConfig.loadAvailable(directory.resolve("commands"), LOG::warn);
            var previous = engine.definitions();
            engine.setDefinitions(next);
            if (refreshBindings) server.reloadResources(server.getDataPackManager().getEnabledIds()).whenComplete((unused, error) -> {
                server.execute(() -> {
                    if (error != null) { engine.setDefinitions(previous); LOG.error("RCC command tree reload failed", error); }
                    else LOG.info("Reloaded {} RCC definitions", next.size());
                });
            });
            else LOG.info("Loaded {} RCC definitions", next.size());
        } catch (Exception e) { LOG.error("RCC reload rejected: {}", e.getMessage()); }
    }
    private int invoke(ServerCommandSource source, String id, String raw) {
        return invoke(source, id, raw, null);
    }
    private int invoke(ServerCommandSource source, String id, String raw, String target) {
        List<String> args;
        try { args = Arguments.parse(raw); }
        catch (IllegalArgumentException e) { source.sendError(Text.literal(e.getMessage())); return 0; }
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity p ? p : null;
        String name = player == null ? null : player.getName().getString();
        String uuid = player == null ? null : player.getUuidAsString();
        boolean op = player == null || server.getPlayerManager().isOperator(player.getGameProfile());
        if (target == null) engine.invoke(id, args, name, uuid, op, node -> permitted(player, node));
        else engine.invokeTarget(target, id, args, name, uuid, op, node -> permitted(player, node));
        return 1;
    }
    private boolean permitted(ServerPlayerEntity player, String node) {
        if (player == null) return true;
        try {
            var luckPerms = LuckPermsProvider.get();
            var user = luckPerms.getUserManager().getUser(player.getUuid());
            return user != null && user.getCachedData().getPermissionData().checkPermission(node).asBoolean();
        } catch (IllegalStateException | NoClassDefFoundError e) { return false; }
    }
}
