package inceris.rcc.paper;

import inceris.rcc.common.*;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.util.*;

public final class RccPaper extends JavaPlugin implements PluginMessageListener {
    private BackendEngine engine;
    private final List<Command> registered = new ArrayList<>();
    @Override public void onEnable() {
        saveDefaultConfig();
        String server = getConfig().getString("server-name", "");
        if (!server.matches("[a-zA-Z0-9_-]+")) { getLogger().severe("Set server-name in config.yml"); Bukkit.getPluginManager().disablePlugin(this); return; }
        engine = new BackendEngine(server, new BackendEngine.Adapter() {
            public boolean send(byte[] message) {
                Player player = Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
                if (player == null) return false;
                player.sendPluginMessage(RccPaper.this, Packet.CHANNEL, message);
                return true;
            }
            public boolean dispatchConsole(String command) { return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), strip(command)); }
            public void feedback(String uuid, String message) {
                getLogger().info(message);
                if (uuid != null) {
                    Player recipient = Bukkit.getPlayer(UUID.fromString(uuid));
                    if (recipient != null) recipient.sendMessage(message);
                }
            }
            public void log(String message) { getLogger().warning(message); }
            public java.util.concurrent.CompletionStage<CurrencyAction.Result> applyCurrency(CurrencyAction.Operation operation) {
                return PaperCurrency.apply(RccPaper.this, operation);
            }
            public void execute(Runnable task) {
                if (Bukkit.isPrimaryThread()) task.run();
                else Bukkit.getScheduler().runTask(RccPaper.this, task);
            }
        });
        Bukkit.getMessenger().registerIncomingPluginChannel(this, Packet.CHANNEL, this);
        Bukkit.getMessenger().registerOutgoingPluginChannel(this, Packet.CHANNEL);
        Command admin = new RccCommand("rcc", null);
        Bukkit.getCommandMap().register("rcc", "rcc", admin);
        registered.add(admin);
        reloadCommands(true);
        Bukkit.getScheduler().runTaskTimer(this, engine::tick, 20, 20);
        getLogger().info(BuildInfo.LOAD_MESSAGE);
    }
    @Override public void onDisable() { unregister(); }
    @Override public void onPluginMessageReceived(String channel, Player player, byte[] bytes) {
        if (channel.equals(Packet.CHANNEL)) engine.receive(bytes);
    }
    private void reloadCommands() { reloadCommands(false); }
    private void reloadCommands(boolean startup) {
        Map<String, CommandDefinition> next;
        try { next = startup ? CommandConfig.loadAvailable(getDataFolder().toPath().resolve("commands"), getLogger()::warning) : CommandConfig.load(getDataFolder().toPath().resolve("commands")); }
        catch (Exception e) { getLogger().severe("RCC reload rejected: " + e.getMessage()); return; }
        CommandMap map = Bukkit.getCommandMap();
        for (CommandDefinition definition : next.values()) {
            if (definition.register() && map.getCommand(definition.binding()) != null && registered.stream().noneMatch(c -> c.getName().equals(definition.binding()))) {
                getLogger().severe("RCC binding conflicts with existing command: " + definition.binding()); return;
            }
        }
        unregisterCustom();
        engine.setDefinitions(next);
        for (CommandDefinition definition : next.values()) if (definition.register()) {
            Command command = new RccCommand(definition.binding(), definition.id());
            map.register(definition.binding(), "rcc", command); registered.add(command);
        }
        Bukkit.getOnlinePlayers().forEach(Player::updateCommands);
        getLogger().info("Loaded " + next.size() + " RCC definitions");
    }
    private void unregister() {
        CommandMap map = Bukkit.getCommandMap();
        for (Command command : registered) {
            removeCommand(map, command);
        }
        registered.clear();
    }
    private void unregisterCustom() {
        CommandMap map = Bukkit.getCommandMap();
        for (Command command : List.copyOf(registered)) if (command instanceof RccCommand rcc && rcc.id != null) {
            removeCommand(map, command);
            registered.remove(command);
        }
    }
    static void removeCommand(CommandMap map, Command command) {
        Map<String, Command> known = map.getKnownCommands();
        // Paper's Brigadier-backed entry iterator does not implement remove().
        // Snapshot matching keys, then remove through the map to update its command tree.
        List<String> bindings = known.entrySet().stream()
            .filter(entry -> entry.getValue() == command)
            .map(Map.Entry::getKey).toList();
        for (String binding : bindings) {
            if (known.get(binding) == command) known.remove(binding);
        }
        command.unregister(map);
    }
    private final class RccCommand extends Command {
        private final String id;
        RccCommand(String name, String id) { super(name); this.id = id; }
        @Override public boolean execute(CommandSender sender, String label, String[] raw) {
            if (id == null) {
                if (raw.length == 0) { sender.sendMessage("/rcc reload | /rcc execute <name> [args] | /rcc target <server> <name> [args]"); return true; }
                if (raw[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("rcc.admin")) { sender.sendMessage("No permission"); return true; }
                    reloadCommands(); return true;
                }
                if (raw[0].equalsIgnoreCase("target")) {
                    if (raw.length < 3) { sender.sendMessage("/rcc target <server> <name> [args]"); return true; }
                    invoke(sender, raw[2], Arrays.copyOfRange(raw, 3, raw.length), raw[1]); return true;
                }
                if (!raw[0].equalsIgnoreCase("execute") || raw.length < 2) return false;
                invoke(sender, raw[1], Arrays.copyOfRange(raw, 2, raw.length)); return true;
            }
            invoke(sender, id, raw); return true;
        }
        @Override public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
            if (id == null && args.length == 1) return List.of("reload", "execute", "target").stream().filter(s -> s.startsWith(args[0])).toList();
            if (id == null && args.length == 2 && args[0].equalsIgnoreCase("execute")) return engine.definitions().keySet().stream().filter(s -> s.startsWith(args[1])).toList();
            if (id == null && args.length == 2 && args[0].equalsIgnoreCase("target")) return engine.definitions().values().stream().flatMap(d -> d.servers().stream()).filter(s -> !s.contains("$")).distinct().filter(s -> s.startsWith(args[1])).sorted().toList();
            if (id == null && args.length == 3 && args[0].equalsIgnoreCase("target")) return engine.definitions().keySet().stream().filter(s -> s.startsWith(args[2])).sorted().toList();
            return List.of();
        }
    }
    private void invoke(CommandSender sender, String id, String[] raw) {
        invoke(sender, id, raw, null);
    }
    private void invoke(CommandSender sender, String id, String[] raw, String target) {
        Player player = sender instanceof Player p ? p : null;
        List<String> args;
        try { args = Arguments.parse(String.join(" ", raw)); }
        catch (IllegalArgumentException e) { sender.sendMessage(e.getMessage()); return; }
        if (target == null) engine.invoke(id, args, player == null ? null : player.getName(), player == null ? null : player.getUniqueId().toString(), sender.isOp(), sender::hasPermission);
        else engine.invokeTarget(target, id, args, player == null ? null : player.getName(), player == null ? null : player.getUniqueId().toString(), sender.isOp(), sender::hasPermission);
    }
    private static String strip(String command) { return command.startsWith("/") ? command.substring(1) : command; }
}
