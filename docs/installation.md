# Installation

For `currency.type: vault`, install Vault and an economy plugin with offline account support on the Paper destination. For `currency.type: impactor`, install [Impactor 5.3.5 for Fabric 1.21.1](https://modrinth.com/mod/impactor) and its required dependencies on the Fabric destination. Scoreboard actions need neither provider. These integrations are optional and are not bundled. The affected player does not need to be online; remote execution requires another player to carry the RCC plugin messages. Omit `server` for direct local execution without a carrier.

1. Run Java 21 and Minecraft 1.21.1 on Velocity and each backend.
2. Place `RemoteCustomCommands_Velocity_0-1-0.jar` in Velocity's `plugins/` directory.
3. Place `RemoteCustomCommands_Paper_0-1-0.jar` in each Paper server's `plugins/` directory. Set `server-name` in `plugins/RemoteCustomCommands/config.yml` to that backend's exact name in `velocity.toml`.
4. Place `RemoteCustomCommands_Fabric_0-1-0.jar` and Fabric API in each Fabric server's `mods/` directory. Set `config/rcc/server-name.txt` to that backend's exact Velocity name. LuckPerms is optional, but required for explicit permission nodes on Fabric.
5. Add command definitions in the backend's `commands/` directory, then restart or run `/rcc reload`.

The filenames above are for version `0.1.0`. Builds automatically append the project version, replacing dots with hyphens. Upgrade the Velocity bridge and all RCC backends together; these builds use protocol version 3 and cannot communicate with older builds. Remove the previous RCC JAR when installing a renamed JAR to avoid loading two copies.

For remote execution, each destination must also have RCC installed. It does not need the command definition; the origin sends the already resolved command list and optional currency action. Velocity must have a player on each destination backend to carry a plugin message. Remote execution needs a connected player on the origin even for a console invocation. Definitions that omit `server` execute directly on the current backend without a carrier, unless broadcast or a target override is used. If either carrier is unavailable, that destination's request fails or times out. Unknown destination names fail immediately when the origin can receive the reply. Broadcast attempts all registered backends, including ones without RCC or connected players.

For a smoke test, create a command on Paper targeting a Fabric backend with `runcmd: ["say RCC test"]`. Connect one player to each backend and invoke `/rcc execute <id>` on Paper. Repeat with Fabric as origin and with each same-platform pair. Check the destination console and the origin callback. Then disconnect the destination player and confirm a routing failure or timeout. Test malformed YAML and reload to confirm the previous definitions remain active.

The bridge only accepts packets from backend connections and suppresses client-originated packets on its channel. Secure the proxy-to-backend network so players cannot connect directly to a backend; normal Velocity forwarding security remains necessary.
