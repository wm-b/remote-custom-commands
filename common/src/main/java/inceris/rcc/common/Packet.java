package inceris.rcc.common;
import java.io.*;
import java.util.*;

public sealed interface Packet permits Packet.Request, Packet.Response, Packet.Discover, Packet.Servers {
    int MAX_BYTES = 30000;
    String CHANNEL = "rcc:bridge";
    record Request(UUID id, String origin, String target, List<String> commands, CurrencyAction.Operation currency) implements Packet {
        public Request(UUID id, String origin, String target, List<String> commands) { this(id, origin, target, commands, null); }
        public Request { commands = List.copyOf(commands); }
    }
    record Response(UUID id, String origin, String target, Code code, String detail) implements Packet {}
    record Discover(UUID id, String origin) implements Packet {}
    record Servers(UUID id, String origin, List<String> names) implements Packet {
        public Servers { names = List.copyOf(names); }
    }
    enum Code { SUCCESS, COMMAND_FAILURE, ROUTING_FAILURE, TRANSPORT_FAILURE, TIMEOUT, INVALID_REQUEST }
    static byte[] encode(Packet packet) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(3);
            if (packet instanceof Request request) {
                out.writeByte(1); id(out, request.id()); out.writeUTF(request.origin()); out.writeUTF(request.target());
                out.writeShort(request.commands().size());
                for (String command : request.commands()) out.writeUTF(command);
                out.writeBoolean(request.currency() != null);
                if (request.currency() != null) {
                    var c = request.currency();
                    out.writeUTF(c.type().name()); out.writeUTF(c.action().name()); out.writeUTF(c.currency());
                    out.writeUTF(c.amount().toString()); out.writeUTF(c.player()); out.writeUTF(c.nameHint());
                }
            } else if (packet instanceof Response response) {
                out.writeByte(2); id(out, response.id()); out.writeUTF(response.origin()); out.writeUTF(response.target());
                out.writeByte(response.code().ordinal()); out.writeUTF(response.detail());
            } else if (packet instanceof Discover discover) {
                out.writeByte(3); id(out, discover.id()); out.writeUTF(discover.origin());
            } else if (packet instanceof Servers servers) {
                if (servers.names().size() > 65535) throw new IllegalArgumentException("too many broadcast servers");
                out.writeByte(4); id(out, servers.id()); out.writeUTF(servers.origin());
                out.writeShort(servers.names().size());
                for (String name : servers.names()) out.writeUTF(name);
            }
            out.flush();
            if (bytes.size() > MAX_BYTES) throw new IllegalArgumentException("message too large");
            return bytes.toByteArray();
        } catch (IOException e) { throw new IllegalArgumentException("invalid message", e); }
    }
    static Packet decode(byte[] bytes) {
        if (bytes.length > MAX_BYTES || bytes.length < 19) throw new IllegalArgumentException("invalid message size");
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readUnsignedByte() != 3) throw new IllegalArgumentException("unsupported protocol");
            int type = in.readUnsignedByte();
            UUID id = new UUID(in.readLong(), in.readLong());
            String origin = in.readUTF();
            if (!origin.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid origin name");
            String target = type == 1 || type == 2 ? in.readUTF() : "";
            if ((type == 1 || type == 2) && !target.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid server name");
            Packet result;
            if (type == 1) {
                int count = in.readUnsignedShort();
                if (count > 100) throw new IllegalArgumentException("invalid command count");
                List<String> commands = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String command = in.readUTF();
                    if (command.isBlank() || command.length() > 4096) throw new IllegalArgumentException("invalid command");
                    commands.add(command);
                }
                CurrencyAction.Operation currency = null;
                if (in.readBoolean()) currency = new CurrencyAction.Operation(CurrencyAction.Type.valueOf(in.readUTF()), CurrencyAction.Action.valueOf(in.readUTF()), in.readUTF(), new java.math.BigDecimal(in.readUTF()), in.readUTF(), in.readUTF());
                if (count == 0 && currency == null) throw new IllegalArgumentException("empty request");
                result = new Request(id, origin, target, commands, currency);
            } else if (type == 2) {
                int code = in.readUnsignedByte();
                if (code >= Code.values().length) throw new IllegalArgumentException("invalid response code");
                result = new Response(id, origin, target, Code.values()[code], in.readUTF());
            } else if (type == 3) result = new Discover(id, origin);
            else if (type == 4) {
                int count = in.readUnsignedShort();
                if (count > in.available() / 3) throw new IllegalArgumentException("invalid server count");
                List<String> names = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    String name = in.readUTF();
                    if (!name.matches("[a-zA-Z0-9_-]+")) throw new IllegalArgumentException("invalid server name");
                    names.add(name);
                }
                result = new Servers(id, origin, names);
            } else throw new IllegalArgumentException("invalid message type");
            if (in.available() != 0) throw new IllegalArgumentException("trailing data");
            return result;
        } catch (IOException e) { throw new IllegalArgumentException("malformed message", e); }
    }
    private static void id(DataOutputStream out, UUID id) throws IOException { out.writeLong(id.getMostSignificantBits()); out.writeLong(id.getLeastSignificantBits()); }
}
