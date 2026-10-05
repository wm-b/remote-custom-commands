package inceris.rcc.paper;

import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RccPaperTest {
    @Test void unregisterUsesMapRemovalAndRemovesAllAliases() {
        Command owned = command("custom"), other = command("other");
        Map<String, Command> backing = new LinkedHashMap<>();
        backing.put("custom", owned);
        backing.put("rcc:custom", owned);
        backing.put("alias", owned);
        backing.put("other", other);
        List<String> removed = new ArrayList<>();
        Map<String, Command> known = new AbstractMap<>() {
            @Override public Set<Entry<String, Command>> entrySet() {
                // Reproduce Paper's iterator: reading is supported, Iterator.remove is not.
                return new AbstractSet<>() {
                    @Override public Iterator<Entry<String, Command>> iterator() {
                        return backing.entrySet().stream().iterator();
                    }
                    @Override public int size() { return backing.size(); }
                };
            }
            @Override public Command get(Object key) { return backing.get(key); }
            @Override public Command remove(Object key) { removed.add((String) key); return backing.remove(key); }
        };
        CommandMap map = (CommandMap) Proxy.newProxyInstance(CommandMap.class.getClassLoader(), new Class<?>[] { CommandMap.class },
            (proxy, method, args) -> {
                if (method.getName().equals("getKnownCommands")) return known;
                throw new UnsupportedOperationException(method.getName());
            });
        owned.register(map);
        assertThrows(UnsupportedOperationException.class, () -> known.entrySet().removeIf(e -> e.getValue() == owned));
        RccPaper.removeCommand(map, owned);
        assertEquals(List.of("custom", "rcc:custom", "alias"), removed);
        assertEquals(Map.of("other", other), backing);
        assertFalse(owned.isRegistered());
    }
    private static Command command(String name) {
        return new Command(name) {
            @Override public boolean execute(CommandSender sender, String label, String[] args) { return true; }
        };
    }
}
