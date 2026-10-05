package inceris.rcc.common;
import java.util.List;
public record CommandDefinition(String id, String command, List<String> servers, boolean broadcast, List<String> runCommands,
                                boolean register, boolean permissionRequired, String permissionNode,
                                List<String> onSuccess, List<String> onError, CurrencyAction currency) {
    public CommandDefinition(String id, String command, List<String> servers, boolean broadcast, List<String> runCommands,
                             boolean register, boolean permissionRequired, String permissionNode, List<String> onSuccess, List<String> onError) {
        this(id, command, servers, broadcast, runCommands, register, permissionRequired, permissionNode, onSuccess, onError, null);
    }
    public CommandDefinition {
        servers = List.copyOf(servers); runCommands = List.copyOf(runCommands);
        onSuccess = List.copyOf(onSuccess); onError = List.copyOf(onError);
    }
    public String binding() { return command == null ? id : command.substring(1); }
}
