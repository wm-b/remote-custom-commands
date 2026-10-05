# Configuration

## Currency actions

Add a `currency` mapping to a command definition. The action runs on each destination selected by `server`, `broadcast`, or `/rcc target`. The destination needs the corresponding provider; the originating backend may use either Paper or Fabric. The destination does not need a copy of the command definition.

```yaml
grant-event-points:
  server: [fabric]
  currency:
    type: impactor
    action: add
    currency: impactor:event_points
    amount: "$arg2"
    player: "$arg1"
  on-success:
    - say Granted points to $arg1 on $target-server
  on-error:
    - say Could not grant points to $arg1 on $target-server
```

`/rcc execute grant-event-points Inxc 10` credits that player's account, including when they are offline. A UUID may be supplied instead of `Inxc`. `/rcc target other-fabric grant-event-points Inxc 10` changes the destination for that invocation.

| Field | Values |
| --- | --- |
| `type` | `impactor` on Fabric, `vault` on Paper, or `scoreboard` on either |
| `action` | `add`, `remove`, or `set` |
| `currency` | Impactor namespaced currency key, scoreboard objective name, or `default` for Vault |
| `amount` | Nonnegative decimal number or placeholder string; scoreboard requires a whole number |
| `player` | Player name, UUID, or placeholder string; optional, defaults to `$uuid` |

Use `player: "$arg1"` for a separately chosen player, `player: "$player"` for the invoker's name, or omit it to use their UUID. Console invocations must supply a player explicitly. All string fields support the usual placeholders. Prefer UUIDs for economy accounts across servers and after name changes; name resolution uses the destination's profile cache and profile lookup. Correct UUID forwarding must be configured across the network. Offline-mode UUIDs cannot be reverse-looked-up through Mojang; use a known name for scoreboard actions in that case.

For scoreboard actions, `currency` names an existing writable objective on the main/server scoreboard. Scores are stored under the player's name, even when they are offline; a missing score starts at zero. Names work without the player having joined that backend. UUIDs use a known profile name, the invoker's name when targeting their UUID, or an asynchronous profile lookup. An unknown UUID returns an error instead of creating a separate UUID-named score. Amounts and resulting scores must fit a signed 32-bit integer. Removing more than the existing balance fails without changing it.

```yaml
take-points:
  server: [lobby]
  currency:
    type: scoreboard
    action: remove
    currency: event_points
    amount: "$arg2"
    player: "$arg1"

set-money:
  server: [paper]
  currency:
    type: vault
    action: set
    currency: default
    amount: "100.00"
    player: "$arg1"
```

Vault requires Vault and an economy provider supporting offline accounts on the Paper destination. RCC uses the provider's `OfflinePlayer` API and creates an account when necessary. Vault exposes one active currency, so `currency` may be omitted or set to `default`. It does not select a world or another currency. `set` adjusts the balance with a deposit or withdrawal. Amounts exceeding the provider's decimal precision or Vault's numeric precision fail. See the [Vault economy API](https://github.com/MilkBowl/VaultAPI/blob/master/src/main/java/net/milkbowl/vault/economy/Economy.java).

Impactor requires Impactor 5.3.5 for Minecraft 1.21.1 on the Fabric destination. RCC fetches or creates an account by UUID and the selected currency, waits for asynchronous account loading, applies the transaction on the server thread, and waits for persistence before reporting success. Missing currencies and rejected transactions return failures. See the [Impactor economy API](https://github.com/NickImpact/ImpactorAPI/blob/dbcce5b/economy/src/main/java/net/impactdev/impactor/api/economy/EconomyService.java).

`runcmd` is optional when `currency` is supplied. When both are present, the currency action runs first; failure skips `runcmd` and triggers `on-error`. After currency succeeds, all `runcmd` entries are attempted as usual. `on-success` requires the currency action and all commands to succeed. Later command failures do not undo an already successful currency action. Use explicit callback definitions to implement refunds when appropriate. A timeout or storage failure can be inconclusive about whether a balance changed.

The affected player can be offline. Remote plugin message routing still needs another connected player on the origin and each destination; local execution with no `server` needs no carrier. No provider is required for scoreboard actions. Vault and Impactor are optional integrations and are not bundled into the RCC JARs.

## Command definitions

### Current server by default

Omit `server` to run the definition on the backend where it was invoked:

```yaml
local-greeting:
  runcmd:
    - "say Hello $arg1 on $target-server"
```

`/rcc execute local-greeting Bob` runs locally as the console. No proxy message or connected player carrier is required. This also applies to currency actions and internally invoked `rcc:` callbacks whose definitions omit `server`. Normal permission checks and success/error callbacks still apply; `$server` and `$target-server` both identify the current backend. `broadcast: true` and `/rcc target` take precedence over this default. An explicitly empty `server: []` is invalid, and missing placeholder arguments in a specified `server` do not fall back to local execution.

### Server placeholders

`server` accepts placeholders in both scalar and list entries. Resolve each entry using the invocation's arguments and player context before routing:

```yaml
dynamic-server:
  server: ["$arg1"]
  runcmd:
    - "say Hello $arg2 on $target-server"
```

`/rcc execute dynamic-server fabric Bob` targets `fabric`, with `$arg1` = `fabric` and `$arg2` = `Bob`. The server argument is still available to commands and callbacks; it is not consumed. You can combine fixed names and templates, such as `server: [lobby, "$arg1", "minigame-$arg2"]`. Duplicate destinations are attempted only once after substitution.

Supported server placeholders are `$arg1` and higher, `$player`, `$uuid`, `$multiargs`, and `$server` (the origin backend). `$target-server` is unavailable in `server` because the destination has not yet been selected; it continues to resolve in remote commands, currency fields, and callbacks. An internal `rcc:` callback resolves its next definition's server templates from the explicit arguments passed to that callback.

Each resolved entry must be one nonempty backend name containing only letters, digits, underscores, or hyphens. Missing arguments resolve to empty text; if the resulting name is invalid, that entry fails locally with `INVALID_REQUEST` and runs `on-error`. Other valid entries are still attempted. Substitution is literal, so argument values are not expanded again or split into multiple servers. Unknown valid names follow the normal routing failure behavior. `broadcast: true` ignores these templates, and `/rcc target` uses the explicitly provided server instead. Server completion suggests only fixed configured names.

Paper reads `plugins/RemoteCustomCommands/commands/*.yml`. Fabric reads `config/rcc/commands/*.yml`. Both use the same schema. Files are scanned directly inside `commands/`; subdirectories and other extensions are ignored.

```yaml
welcome:
  command: /welcome
  server:
    - lobby
    - creative
  broadcast: false
  runcmd:
    - say Welcome $player to $target-server
    - time query daytime
  register: true
  permission-required: true
  permission-node: rcc.welcome
  on-success:
    - say Request succeeded for $player on $target-server
    - say Finished processing $target-server
  on-error:
    - say Request failed for $player on $target-server
```

The top-level key is the unique identifier. `server` is an optional nonempty list of Velocity backend names; omitting it defaults to direct execution on the current backend. Duplicate names are attempted only once. `runcmd` must be a nonempty list unless a `currency` action is supplied, in which case it may be omitted or empty. Its commands run in order as each destination's console. A leading `/` is optional in a command string. `command` is an optional slash-prefixed player binding; if omitted, the identifier is used. `register` defaults to `false`, so the binding is created only when set to `true`. The command is still accessible through `/rcc execute <identifier> [args]`.

Use `/rcc target <server> <identifier> [args...]` to execute a configured command on one explicitly chosen Velocity backend. For example, `/rcc target fabric transfer-event-points-fabric-2 Inxc 1` runs that definition's `runcmd` on `fabric` with `$arg1` = `Inxc` and `$arg2` = `1`. This overrides both `server` and `broadcast` for this invocation without editing the configuration. It works with `register: false` definitions and applies the definition's normal player permission checks; console calls are exempt.

`$target-server` resolves to the override in remote commands and local callbacks. Unknown or unavailable destinations use the usual error result and `on-error` callbacks. A configured command invoked internally by a `rcc:<identifier>` callback uses its own destination settings. Command identifier completion and suggestions from configured server names are available; you can also type any other backend name registered with Velocity. Quote arguments containing spaces as usual.

`broadcast` defaults to `false`. When `true`, RCC gets Velocity's complete configured backend list at invocation time and attempts each server, including the origin. The `server` field is ignored and may be omitted. Servers with no player carrier fail explicitly; servers without RCC may time out. A failed destination does not prevent attempts on other servers.

```yaml
announce:
  broadcast: true
  runcmd:
    - say Announcement from $server to $target-server
  permission-node: rcc.announce
  on-success:
    - say Delivered to $target-server
  on-error:
    - say Delivery failed for $target-server
```

For compatibility, a single string is still accepted for `server`, `on-success`, or `on-error`. The list form is recommended. Omitted callback fields mean empty lists.

`permission-required` defaults to `true`. When `permission-node` is present, players need that permission. Otherwise they need OP status. When `permission-required: false`, the player permission check is skipped. Console calls are exempt. On Fabric, LuckPerms is used for explicit permission nodes; if it is absent, those checks deny access. OP fallback works without LuckPerms.

`on-success` and `on-error` are optional lists run **on the originating backend's console**, in their configured order. Each destination has its own result: its entire `on-success` list runs if its currency action and all remote commands succeed, otherwise its entire `on-error` list runs. Lists are run once per destination result, so a mixed outcome runs success callbacks for successful destinations and error callbacks for failed ones. Results may arrive in a different order from the server list. Callback failures are logged independently and later entries are still attempted.

Each callback entry uses its first whitespace-separated token as the command and the remaining text as that command's explicit arguments. Placeholders are resolved using the triggering invocation before dispatch. No arguments are automatically forwarded or appended. Normal commands execute through the originating platform's console dispatcher, preserving their argument text and quotes.

Use `rcc:<identifier> [args...]` to invoke another configured command internally. The identifier is the YAML top-level key. Its argument text is parsed with support for double quotes and backslash escapes, and only those explicitly supplied arguments are passed to the next definition. `rcc:<identifier>` by itself passes no arguments. Player identity is retained, but the next definition's `$arg1`, `$arg2`, and `$multiargs` refer to the arguments explicitly passed by its callback.

```yaml
on-success:
  - "rcc:transfer-event-points-fabric-2 $multiargs"
  - 'rcc:audit "$arg1" completed'
on-error:
  - "rcc:refund $arg1 $arg2"
```

Use `$multiargs` to explicitly pass the original arguments as space-separated text, or individual `$argN` placeholders to choose or reorder them. Quote a placeholder when its value should remain one argument even if it contains spaces (for example, `rcc:audit "$arg1"`). An unquoted `$multiargs` value is parsed again and does not preserve the original quote grouping. To forward known arguments while preserving spaces, use `rcc:next "$arg1" "$arg2"`.

Callback chains stop after four hops. Callback failures do not trigger another callback for the same request, and later list entries are still attempted. If broadcast discovery itself fails, `on-error` runs once with `$target-server` set to `broadcast`.

Placeholders: `$player`, `$uuid`, `$arg1` and higher, `$multiargs`, `$server` (origin), and `$target-server` (the individual destination for both remote commands and callbacks). Missing arguments and player placeholders in console calls become empty strings. Substitution is literal. Use quotes around arguments with spaces; backslash escapes the next character.

On startup, malformed files are logged and skipped while valid files load. `/rcc reload` validates the complete new set and retains the previous set if validation fails. Fabric reload also refreshes the Minecraft command tree through a data pack reload. `rcc.admin` is required for Paper `/rcc reload`; Fabric requires command permission level 4.
