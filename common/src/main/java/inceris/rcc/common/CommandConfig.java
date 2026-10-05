package inceris.rcc.common;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
public final class CommandConfig {
    private CommandConfig() {}
    public static Map<String, CommandDefinition> load(Path directory) throws IOException { return load(directory, null); }
    public static Map<String, CommandDefinition> loadAvailable(Path directory, Consumer<String> errors) throws IOException { return load(directory, Objects.requireNonNull(errors)); }
    private static Map<String, CommandDefinition> load(Path directory, Consumer<String> errors) throws IOException {
        Files.createDirectories(directory);
        Map<String, CommandDefinition> result = new LinkedHashMap<>();
        Set<String> bindings = new HashSet<>();
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().endsWith(".yml")).sorted().toList()) {
                try {
                    Map<String, CommandDefinition> parsed = parse(file);
                    Set<String> fileBindings = new HashSet<>(bindings);
                    for (CommandDefinition definition : parsed.values()) {
                        if (result.containsKey(definition.id())) throw new IllegalArgumentException("duplicate identifier: " + definition.id());
                        if (definition.register() && !fileBindings.add(definition.binding().toLowerCase(Locale.ROOT))) throw new IllegalArgumentException("duplicate binding: " + definition.binding());
                    }
                    result.putAll(parsed);
                    bindings = fileBindings;
                } catch (RuntimeException e) {
                    if (errors == null) throw new IllegalArgumentException(file + ": " + e.getMessage(), e);
                    errors.accept(file + ": " + e.getMessage());
                }
            }
        }
        return Map.copyOf(result);
    }
    private static Map<String, CommandDefinition> parse(Path file) throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(0);
        Object parsed;
        try (var reader = Files.newBufferedReader(file)) { parsed = new Yaml(new SafeConstructor(options)).load(reader); }
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("expected mapping");
        Map<String, CommandDefinition> result = new LinkedHashMap<>();
        for (var entry : root.entrySet()) {
            if (!(entry.getKey() instanceof String id) || !id.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid identifier");
            if (!(entry.getValue() instanceof Map<?, ?> values)) throw new IllegalArgumentException(id + " must be a mapping");
            String command = optional(values, "command");
            if (command != null && !command.matches("/[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid command for " + id);
            boolean broadcast = bool(values, "broadcast", false);
            List<String> servers = broadcast ? List.of() : strings(values, "server");
            if (!broadcast && values.get("server") != null && servers.isEmpty()) throw new IllegalArgumentException("empty server list for " + id);
            if (servers.stream().anyMatch(s -> !Placeholders.isServerTemplate(s))) throw new IllegalArgumentException("invalid server template for " + id);
            servers = List.copyOf(new LinkedHashSet<>(servers));
            CurrencyAction currency = currency(values.get("currency"));
            Object raw = values.get("runcmd");
            if (raw == null && currency != null) raw = List.of();
            if (!(raw instanceof List<?> list) || (list.isEmpty() && currency == null) || list.size() > 100 || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 4096)) throw new IllegalArgumentException("invalid runcmd for " + id);
            List<String> commands = list.stream().map(String::valueOf).toList();
            boolean register = bool(values, "register", false);
            if (register && (command == null ? id : command.substring(1)).equalsIgnoreCase("rcc")) throw new IllegalArgumentException("reserved rcc binding for " + id);
            boolean permissionRequired = bool(values, "permission-required", true);
            String node = optional(values, "permission-node");
            if (node != null && !node.matches("[a-zA-Z0-9_.-]+")) throw new IllegalArgumentException("invalid permission-node for " + id);
            List<String> success = strings(values, "on-success"), error = strings(values, "on-error");
            result.put(id, new CommandDefinition(id, command, servers, broadcast, commands, register, permissionRequired, node, success, error, currency));
        }
        return result;
    }
    private static CurrencyAction currency(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof Map<?, ?> values)) throw new IllegalArgumentException("currency must be a mapping");
        String type = optional(values, "type"), action = optional(values, "action");
        if (type == null || action == null) throw new IllegalArgumentException("currency requires type and action");
        CurrencyAction.Type kind = CurrencyAction.Type.valueOf(type.toUpperCase(Locale.ROOT));
        CurrencyAction.Action operation = CurrencyAction.Action.valueOf(action.toUpperCase(Locale.ROOT));
        String key = optional(values, "currency");
        if (key == null) key = "";
        if (kind != CurrencyAction.Type.VAULT && key.isBlank()) throw new IllegalArgumentException("currency.currency is required");
        Object amount = values.get("amount");
        if (!(amount instanceof String || amount instanceof Number) || amount.toString().isBlank() || amount.toString().length() > 256) throw new IllegalArgumentException("currency.amount must be a number or placeholder string");
        if (!amount.toString().contains("$")) {
            var number = new java.math.BigDecimal(amount.toString());
            new CurrencyAction.Operation(kind, operation, key, number, "Validation", "");
        }
        String player = optional(values, "player");
        if (player != null && (player.isBlank() || player.length() > 256)) throw new IllegalArgumentException("invalid currency.player");
        return new CurrencyAction(kind, operation, key, amount.toString(), player == null ? "$uuid" : player);
    }
    private static List<String> strings(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) return List.of();
        // Keep existing configurations working while lists are the canonical format.
        if (value instanceof String s && !s.isBlank()) return List.of(s);
        if (!(value instanceof List<?> list) || list.size() > 100 || list.stream().anyMatch(v -> !(v instanceof String s) || s.isBlank() || s.length() > 4096))
            throw new IllegalArgumentException(key + " must be a list of nonempty strings");
        return list.stream().map(String::valueOf).toList();
    }
    private static String optional(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof String s)) throw new IllegalArgumentException(key + " must be a string");
        return s;
    }
    private static boolean bool(Map<?, ?> values, String key, boolean fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException(key + " must be a boolean");
        return b;
    }
}
