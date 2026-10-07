# Configuration

Paper reads command definitions from `plugins/RemoteCustomCommands/commands/*.yml`; Fabric reads them from `config/rcc/commands/*.yml`. Both use the same schema. Each file can contain multiple definitions, each with a unique top-level identifier:

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

Invoke a definition with `/rcc execute <identifier> [args...]`, for example `/rcc execute welcome Bob`. With `register: true`, its binding is also available as a command, such as `/welcome Bob`.

## Command fields

These fields belong inside each command definition, beneath its identifier.

| Field | Values and behavior | Default when omitted |
| --- | --- | --- |
| `command` | Slash-prefixed command binding, used when `register` is `true` | `/<identifier>` |
| `server` | Nonempty list of Velocity backend names or placeholder templates; duplicates are attempted once | Execute on the current backend |
| `broadcast` | Boolean; `true` attempts every backend configured in Velocity and ignores `server` | `false` |
| `runcmd` | Nonempty list of commands, run in order as each destination's console; a leading `/` is optional | Required unless `currency` is supplied; then it may be omitted or empty |
| `currency` | Mapping describing a currency action, performed before `runcmd`; see [Currency actions](#currency-actions) | No currency action |
| `register` | Boolean; `true` registers the `command` binding. `/rcc execute` works regardless | `false` |
| `permission-required` | Boolean; `true` checks the invoking player's permission node or OP status | `true` |
| `permission-node` | Permission required when player permission checks are enabled | Require OP status |
| `on-success` | List of callbacks run on the origin's console when a destination's currency action and all commands succeed | Empty list |
| `on-error` | List of callbacks run on the origin's console when a destination fails | Empty list |

### Destinations: `server` and `broadcast`

Omit `server` to run on the backend where the definition was invoked:

```yaml
local-greeting:
  runcmd:
    - "say Hello $arg1 on $target-server"
```

`/rcc execute local-greeting Bob` runs locally as the console without a proxy message or connected player carrier. Currency actions and internally invoked definitions also use this default when they omit `server`. Permission checks for normal invocations and success/error callbacks still apply. `$server` and `$target-server` both identify the current backend.

To select remote destinations, supply their Velocity backend names in `server`. The destination needs RCC installed, but does not need a copy of the command definition.

You can use [placeholders](#placeholders) in server entries:

```yaml
dynamic-server:
  server: ["$arg1"]
  runcmd:
    - "say Hello $arg2 on $target-server"
```

`/rcc execute dynamic-server fabric Bob` targets `fabric`, with `$arg1` = `fabric` and `$arg2` = `Bob`. The server argument remains available to commands and callbacks. Fixed names and templates can be combined, such as `server: [lobby, "$arg1", "minigame-$arg2"]`. Duplicate destinations are attempted once after substitution.

With `broadcast: true`, RCC gets Velocity's complete configured backend list at invocation time and attempts every server, including the origin. `server` is ignored and may be omitted. A failed destination does not prevent attempts on other servers.

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

Use `/rcc target <server> <identifier> [args...]` to choose one destination for an invocation. For example, `/rcc target fabric welcome Bob` runs `welcome` on `fabric`. This overrides both `server` and `broadcast`, works with `register: false`, and applies the normal player permission checks. `$target-server` resolves to the chosen destination in commands, currency fields, and callbacks.

### Permissions: `permission-required` and `permission-node`

With the default `permission-required: true`, players need the configured `permission-node`, or OP status if no node is supplied. Set `permission-required: false` to skip this check. Console invocations are exempt.

On Fabric, RCC uses LuckPerms to check explicit permission nodes. Without it, those checks deny access; RCC's OP fallback works without LuckPerms.

### Callbacks: `on-success` and `on-error`

Callbacks run **on the originating backend's console**, in their configured order. Each destination has its own result: its entire `on-success` list runs if its currency action and all commands succeed; otherwise its entire `on-error` list runs. A mixed outcome therefore runs success callbacks for successful destinations and error callbacks for failed ones. Results may arrive in a different order from the server list.

Each entry uses its first whitespace-separated token as the command and the remaining text as explicit arguments. Placeholders resolve using the triggering invocation. No arguments are automatically forwarded or appended. Normal commands use the originating platform's console dispatcher, preserving their argument text and quotes.

Use `rcc:<identifier> [args...]` to invoke another configured definition internally. The identifier is its YAML top-level key. The next definition retains the player identity, uses its own destination settings, and receives only the arguments explicitly supplied by the callback:

```yaml
on-success:
  - "rcc:transfer-event-points-fabric-2 $multiargs"
  - 'rcc:audit "$arg1" completed'
on-error:
  - "rcc:refund $arg1 $arg2"
```

Use `$multiargs` to pass the original arguments as space-separated text, or individual `$argN` placeholders to choose or reorder them. Quote placeholders that should remain one argument when their values contain spaces. See [Callback argument handling and limits](#callback-argument-handling-and-limits) for details.

### Currency actions

Add a `currency` mapping to perform an action on each selected destination. The originating backend may use either Paper or Fabric. RCC's Vault and Impactor integrations are optional; scoreboard actions require neither. See [Installation](installation.md) for integration setup.

| Field | Values |
| --- | --- |
| `type` | `impactor` on Fabric, `vault` on Paper, or `scoreboard` on either |
| `action` | `add`, `remove`, or `set` |
| `currency` | Impactor namespaced currency key, scoreboard objective name, or `default` for Vault |
| `amount` | Nonnegative decimal number or placeholder string; scoreboard requires a whole number |
| `player` | Player name, UUID, or placeholder string; optional, defaults to `$uuid` |

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

Use `player: "$arg1"` for a separately chosen player, `player: "$player"` for the invoker's name, or omit it to use their UUID. Console invocations must supply a player explicitly. Placeholders are supported in the `currency`, `amount`, and `player` fields.

For scoreboard actions, `currency` names an existing writable objective on the main/server scoreboard. RCC uses the player's name for the score and treats a missing score as zero. For Vault actions, RCC accepts an omitted `currency` or `default`; the field does not select a world or another currency.

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

When both `currency` and `runcmd` are supplied, the currency action runs first. A currency failure skips `runcmd` and triggers `on-error`. After currency succeeds, all `runcmd` entries are attempted in order. `on-success` requires both the currency action and all commands to succeed. Later command failures do not undo a successful currency action.

## Placeholders

Placeholders resolve using the invocation's arguments and player context. They are available in `runcmd`, callbacks, the currency fields listed above, and `server` entries, with the destination exception below.

| Placeholder | Resolves to |
| --- | --- |
| `$player` | Invoking player's name |
| `$uuid` | Invoking player's UUID |
| `$arg1`, `$arg2`, … | Positional argument, numbered from 1 |
| `$multiargs` | All invocation arguments joined with spaces |
| `$server` | Originating backend's name |
| `$target-server` | Individual destination's name; unavailable in `server` because the destination has not yet been selected |

Missing arguments and player placeholders in console invocations become empty strings. Substitution is literal: inserted values are not expanded again. Quote invocation arguments containing spaces; a backslash escapes the next character.

In an internal `rcc:` callback, the next definition's `$arg1`, `$arg2`, and `$multiargs` refer to the arguments explicitly passed by that callback. Its server templates resolve from those arguments too.

## Reloading configuration

On startup, malformed files are logged and skipped while valid files load. `/rcc reload` validates the complete new set and retains the previous set if validation fails. Paper requires `rcc.admin` for `/rcc reload`; Fabric requires command permission level 4 and also refreshes the Minecraft command tree through a data pack reload.

## Niche considerations

### File formats and compatibility

Only `.yml` files directly inside `commands/` are scanned; subdirectories and other extensions are ignored. Identifiers contain only letters, digits, underscores, or hyphens and must be unique across files. Registered bindings must also be unique, ignoring case, and cannot use the reserved name `rcc`.

For compatibility, a single string is accepted for `server`, `on-success`, or `on-error`; the list form is recommended. An explicitly empty `server: []` is invalid unless `broadcast: true` ignores it.

### Server template validation and completion

Each resolved server entry must be one nonempty backend name containing only letters, digits, underscores, or hyphens. Missing placeholder arguments do not fall back to local execution. If substitution produces an invalid name, that entry fails locally with `INVALID_REQUEST` and runs `on-error`; other valid entries are still attempted. Values are not split into multiple servers. Unknown valid names follow the normal routing failure behavior.

`broadcast: true` ignores server templates, and `/rcc target` uses its explicitly provided server instead. Unknown or unavailable override destinations use the usual error result and `on-error` callbacks. An internally invoked `rcc:` definition uses its own destination settings rather than inheriting the override.

Command identifier completion is available. Server completion suggests only fixed configured names, but you can type any other backend name registered with Velocity.

### Callback argument handling and limits

Internal `rcc:` argument text is parsed with support for double quotes and backslash escapes. `rcc:<identifier>` alone passes no arguments. An unquoted `$multiargs` value is parsed again and does not preserve the original quote grouping. To forward known arguments while preserving spaces, use `rcc:next "$arg1" "$arg2"`.

Callback chains stop after four hops. Callback failures are logged independently, do not trigger another callback for the same request, and do not prevent later list entries from being attempted. If broadcast discovery itself fails, `on-error` runs once with `$target-server` set to `broadcast`.

### Currency account identity and numeric limits

RCC resolves currency player names using the destination's profile cache and profile lookup.

For scoreboard actions, RCC accepts names even if the player is offline or has not joined that backend. RCC resolves UUIDs to a known profile name, the invoker's name when targeting their UUID, or an asynchronous profile lookup. An unknown UUID returns an error instead of creating a separate UUID-named score.

Scoreboard amounts and resulting scores must fit a signed 32-bit integer. Removing more than the existing balance fails without changing it.

### Currency action results

For Vault actions, RCC creates an account when necessary and implements `set` by depositing or withdrawing the difference from the current balance. RCC rejects amounts exceeding the provider's decimal precision or Vault's numeric precision.

For Impactor actions, RCC fetches or creates an account by UUID and the selected currency, then waits for persistence before reporting success. Missing currencies and rejected transactions return failures.

An RCC timeout or storage failure can be inconclusive about whether a balance changed.

### Remote transport requirements

The affected player can be offline, but remote plugin message routing still requires a connected player on the origin and each destination to carry messages. Local execution with no `server` requires no carrier unless broadcast or a target override is used.

Broadcast destinations without a player carrier fail explicitly; destinations without RCC may time out. See [Installation](installation.md) for network setup requirements.
