# GameHavocHytalePlugin

GameHavocHytalePlugin is a small collection of Hytale server tweaks and fixes for the GameHavoc community server.

It ships as a single plugin but contains multiple sub-systems.

## Modules

### HydrationTale

Fixes a hydration consistency issue with tilled soil on dedicated servers.

#### What it fixes

In the vanilla server, adjacent-water hydration for tilled soil is only evaluated through the
**crop growth modifier** pipeline. That means:

- If there is **no crop**, adjacent water is not checked.
- If the crop stops ticking (fully grown, inactive chunk, or low server activity), the soil can
  **dry out even while next to water**.
- Hydration can appear to "wake up" when you plant, till nearby, or when a growth phase advances,
  and then silently stop later.

The module makes the adjacency rule authoritative by checking **tilled soil itself** rather than
crop growth.

#### Behavior

- If a tilled soil block is orthogonally adjacent to water, it stays in the **Watered** state.
- If water is removed, the block can dry as usual.
- Diagonals are not considered.

#### How it works (high level)

The mod runs a lightweight tick system for `TilledSoilBlock` and:

1. Looks up the four orthogonal neighbor fluid IDs.
2. Sets `externalWater` accordingly.
3. Forces the block state to update if needed.

This mirrors the built-in water check, but it runs independently of crop growth.

### SleepyTale

Allows night to pass when a configurable percentage of players are in bed, rather than requiring all players.
Currently the threshold is set in code (see `REQUIRED_SLEEP_FRACTION`).

### IsekaiTale

Separates player inventories between persistent worlds and enforces the destination world's default gamemode on transfer.

This is intended for setups like:

- Adventure world + Creative build world on the same server
- Portal-connected "hub" worlds
- Future world resets/migrations without carrying items across worlds

#### What it does

- Detects persistent world transfers using server events (no polling).
- Stashes the player's inventory layout for the world they are leaving.
- Clears inventory during transfer.
- Restores the exact saved layout (hotbar, armor, utility/tools, backpack) for the destination world, if one exists.
- Applies the destination world's default gamemode (for example, Adventure vs Creative).

#### Important behavior

- Temporary instance worlds (names starting with `instance-`) are ignored.
  - This preserves normal adventure-instance behavior (Forgotten Temple, Windrider Valley, etc.).
- Login does not trigger inventory swapping (there is no source world on login).
- If a restore cannot fully fit (capacity mismatch), leftovers remain in the destination world stash file instead of being dropped.

## Install

1. Build the jar:

```
mvn -DskipTests package
```

2. Copy the output jar to your server mods folder:

```
..\Server\mods
```

3. Restart the server.

## Notes

- Fluid keys used by default: `Water`, `Water_Source`, `Water_Finite`.
- If your server assets rename those, update `DEFAULT_FLUID_KEYS` in:

```
GameHavocHytalePlugin/src/main/java/com/gamehavochytaleplugin/systems/HydrationTaleSystem.java
```

- Sleep threshold is configured in:

```
GameHavocHytalePlugin/src/main/java/com/gamehavochytaleplugin/systems/SleepyTaleSystem.java
```

- HydrationTale implementation lives in:

```
GameHavocHytalePlugin/src/main/java/com/gamehavochytaleplugin/systems/HydrationTaleSystem.java
```

- IsekaiTale implementation lives in:

```
GameHavocHytalePlugin/src/main/java/com/gamehavochytaleplugin/systems/IsekaiTaleSystem.java
```
