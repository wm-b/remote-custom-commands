package inceris.rcc.common;
import java.util.*;
import java.util.regex.*;
public final class Placeholders {
    private static final Pattern TOKEN = Pattern.compile("\\$(?:target-server|multiargs|server|player|uuid|arg[1-9][0-9]*)");
    private static final Pattern CALLBACK_TOKEN = Pattern.compile("\\$(?:attempted-server|target-server|multiargs|server|player|uuid|arg[1-9][0-9]*)");
    private Placeholders() {}
    public static boolean isServerTemplate(String template) {
        // A destination cannot refer to $target-server before it has been selected.
        return template.length() <= 4096 && !template.contains("$target-server")
            && TOKEN.matcher(template).replaceAll("x").matches("[a-zA-Z0-9_-]+");
    }
    public static String resolve(String template, String player, String uuid, List<String> args, String origin, String target) {
        return resolve(template, player, uuid, args, origin, target, TOKEN);
    }
    public static String resolveCallback(String template, String player, String uuid, List<String> args, String origin, String target) {
        return resolve(template, player, uuid, args, origin, target, CALLBACK_TOKEN);
    }
    private static String resolve(String template, String player, String uuid, List<String> args, String origin, String target, Pattern tokens) {
        Matcher matcher = tokens.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group().substring(1);
            String value = switch (key) {
                case "player" -> player == null ? "" : player;
                case "uuid" -> uuid == null ? "" : uuid;
                case "server" -> origin;
                case "target-server", "attempted-server" -> target;
                case "multiargs" -> String.join(" ", args);
                default -> { int index = Integer.parseInt(key.substring(3)) - 1; yield index < args.size() ? args.get(index) : ""; }
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
