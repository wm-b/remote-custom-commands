package inceris.rcc.common;
import java.util.List;
public record CommandDefinition(String id, String command, List<String> servers, boolean broadcast, List<String> runCommands,
                                boolean register, boolean permissionRequired, String permissionNode,
                                List<String> onSuccess, List<String> onError, CurrencyAction currency,
                                List<String> checkServerAvailable, List<String> onServerUnavailable, boolean runAsPlayer) {
    public CommandDefinition(String id, String command, List<String> servers, boolean broadcast, List<String> runCommands,
                             boolean register, boolean permissionRequired, String permissionNode,
                             List<String> onSuccess, List<String> onError, CurrencyAction currency,
                             List<String> checkServerAvailable, List<String> onServerUnavailable) {
        this(id, command, servers, broadcast, runCommands, register, permissionRequired, permissionNode,
            onSuccess, onError, currency, checkServerAvailable, onServerUnavailable, false);
    }
    public CommandDefinition(String id, String command, List<String> servers, boolean broadcast, List<String> runCommands,
                             boolean register, boolean permissionRequired, String permissionNode,
                             List<String> onSuccess, List<String> onError, CurrencyAction currency) {
        this(id, command, servers, broadcast, runCommands, register, permissionRequired, permissionNode,
            onSuccess, onError, currency, List.of(), List.of());
    }
    public CommandDefinition(String id, String command, List<String> servers, boolean broadcast, List<String> runCommands,
                             boolean register, boolean permissionRequired, String permissionNode, List<String> onSuccess, List<String> onError) {
        this(id, command, servers, broadcast, runCommands, register, permissionRequired, permissionNode, onSuccess, onError, null);
    }
    public CommandDefinition {
        servers = List.copyOf(servers); runCommands = List.copyOf(runCommands);
        onSuccess = List.copyOf(onSuccess); onError = List.copyOf(onError);
        checkServerAvailable = List.copyOf(checkServerAvailable); onServerUnavailable = List.copyOf(onServerUnavailable);
    }
    public String binding() { return command == null ? id : command.substring(1); }
}
