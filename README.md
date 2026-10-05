# Remote Custom Commands

Remote Custom Commands (RCC) runs configured commands on another Minecraft 1.21.1 backend through a Velocity plugin message bridge. It contains a Velocity plugin, a Paper plugin, and a Fabric server mod. Java 21 is required.

## Build

Run `bash gradlew build` (Windows: `.\gradlew.bat build`). The distributable JARs are `velocity/build/libs/RemoteCustomCommands_Velocity_0-1-0.jar`, `paper/build/libs/RemoteCustomCommands_Paper_0-1-0.jar`, and `fabric/build/libs/RemoteCustomCommands_Fabric_0-1-0.jar` for version `0.1.0`. Names use `RemoteCustomCommands_<Platform>_<version>.jar`, with version dots replaced by hyphens, and follow the project version in the root `build.gradle` automatically.

The `server`, `on-success`, and `on-error` configuration fields accept lists. Set `broadcast: true` to attempt execution on every backend registered with Velocity, including the origin, and ignore `server`. Callbacks run locally for each destination's result. Existing scalar configuration values are also accepted for compatibility. Upgrade the bridge and all backends together because this version uses protocol version 3.

The optional `currency` block supports `type`, `action`, `currency`, `amount`, and `player`. Use Impactor on Fabric, Vault on Paper, or scoreboard on either platform. `player` accepts an offline player name or UUID and placeholders such as `$arg1`; it defaults to `$uuid`. Currency actions run on the destination before any `runcmd` entries. See [currency configuration](docs/configuration.md#currency-actions) for examples and provider requirements.

Use `/rcc target <server> <command-id> [args...]` on Paper or Fabric to override a definition's destinations and broadcast setting for one invocation. For example: `/rcc target fabric transfer-event-points-fabric-2 Inxc 1`.

`server` entries support invocation placeholders, such as `server: ["$arg1"]` or `server: [lobby, "minigame-$arg2"]`. Destinations are resolved and deduplicated before routing; arguments remain available to commands and callbacks.

Omit `server` to execute commands and currency actions on the current backend directly, without requiring a player carrier or a proxy message. `broadcast: true` and `/rcc target` override this default.

Callbacks use explicit arguments: `rcc:<command-id> $multiargs` passes the triggering invocation's arguments as space-separated text; `rcc:<command-id>` passes none. Use individual quoted placeholders to preserve arguments containing spaces. Update existing callback chains that relied on automatic argument forwarding.

See [installation](docs/installation.md), [configuration](docs/configuration.md), and [protocol](docs/protocol.md). The first release uses player-carried plugin messages; a player must be connected to both the origin and destination backend during remote routing. Local execution with no `server` needs no carrier. A timeout can mean the destination executed a command but its reply was lost. Do not automatically retry timed-out commands with side effects.

## Current verification

`bash gradlew build` compiles all three modules and runs the shared unit tests. Live Velocity, Paper, and Fabric server integration has not been run in this repository. Verify the server combinations in [installation](docs/installation.md) before production deployment.
