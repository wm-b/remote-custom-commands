# Remote Custom Commands

Remote Custom Commands (RCC) runs configured commands on another Minecraft 1.21.1 backend through a Velocity plugin message bridge. It contains a Velocity plugin, a Paper plugin, and a Fabric server mod. Java 21 is required.

## Build

Run `bash gradlew build` (Windows: `.\gradlew.bat build`). The distributable JARs are `velocity/build/libs/RemoteCustomCommands_Velocity_0-1-0.jar`, `paper/build/libs/RemoteCustomCommands_Paper_0-1-0.jar`, and `fabric/build/libs/RemoteCustomCommands_Fabric_0-1-0.jar` for version `0.1.0`.

Use `/rcc target <server> <command-id> [args...]` on Paper or Fabric to override a definition's destinations and broadcast setting for one invocation. For example: `/rcc target fabric transfer-event-points-fabric-2 Inxc 1`.

See [installation](docs/installation.md), [configuration](docs/configuration.md), and [protocol](docs/protocol.md) for more detail.
