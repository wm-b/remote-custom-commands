package inceris.rcc.common;
import java.util.*;
public final class Arguments {
    private Arguments() {}
    public static List<String> parse(String input) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false, started = false, escaped = false;
        for (char c : input.toCharArray()) {
            if (escaped) { current.append(c); escaped = false; started = true; }
            else if (c == '\\') { escaped = true; started = true; }
            else if (c == '"') { quoted = !quoted; started = true; }
            else if (Character.isWhitespace(c) && !quoted) { if (started) { result.add(current.toString()); current.setLength(0); started = false; } }
            else { current.append(c); started = true; }
        }
        if (escaped || quoted) throw new IllegalArgumentException("unterminated escape or quote");
        if (started) result.add(current.toString());
        return List.copyOf(result);
    }
}
