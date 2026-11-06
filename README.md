<h1><img src="images/dirtflow.svg" width="48" height="48" alt="" align="absmiddle"> Dirtflow mod</h1>

[Research](https://github.com/IvanBanny/dirtflow-v0) |
[Model](https://huggingface.co/IvanBanny/dirtflow-v0) |
[Engine](https://github.com/IvanBanny/dirtflow-v0-engine) |
[Mod](https://github.com/IvanBanny/dirtflow-v0-mod) |
[Demo](https://huggingface.co/spaces/IvanBanny/dirtflow-v0)

Select a region, describe a build, and preview the model's edit in Minecraft.
Accept it when you're happy with it; undo and redo are saved with the world.
Inference runs in the [Dirtflow engine](https://github.com/IvanBanny/dirtflow-v0-engine),
using the [Dirtflow v0 model](https://huggingface.co/IvanBanny/dirtflow-v0).

## Install

Use Minecraft 1.21.11, Java 21,
[Fabric Loader](https://fabricmc.net/use/installer/) 0.18.4 or later, and
[Fabric API](https://github.com/FabricMC/fabric-api/releases) 0.141.2+1.21.11 or later
for that Minecraft version. Download the JAR from
[releases](https://github.com/IvanBanny/dirtflow-v0-mod/releases) and put it in
`mods/`, alongside Fabric API.
Install both on the client and Minecraft server. Singleplayer uses the integrated
server. Start the engine separately, following its README.

The default engine address is `http://127.0.0.1:8000`, as seen from the Minecraft
server. `/df engine <address>` changes it for the world; this requires an operator
or the singleplayer owner. For an authenticated engine, set the server JVM's
`-Ddirtflow.server.url=<url>` and `-Ddirtflow.server.token=<token>`, or supply the
token through `DIRTFLOW_TOKEN`. Tokens are sent only to that configured URL.
`/df engine reset` restores the JVM default. Use HTTPS for remote connections.

Dirtflow sets `random_tick_speed` and `fire_spread_radius_around_player` to zero
on world startup to preserve generated leaves and fire. These are world-wide,
saved rules: crop growth and other random ticks also pause. Scheduled physics,
including fluids and falling blocks, continue normally.

## Use

A wooden hoe selects two corners with successive right-clicks. Another click
starts a new selection; sneak-right-click adjusts the second corner. Left-click
clears it. You can also select by coordinates:

```mcfunction
/df select ~-5 ~ ~-5 ~5 ~15 ~5
/df a small stone watchtower with a wooden roof
/df accept
/df undo
/df redo
```

`/df select 5` selects an 11-block cube around your feet. Each axis can span up to
58 blocks. The selection and its context shell must fit within build height and
loaded chunks. Dirtflow uses the thickest fitting shell of 3, 5 or 8 blocks.

Generation shows progress and intermediate ghosts. The final preview includes
carved air and stays until you accept, reject or cancel. Changing the selection
cancels its request. `/df retry` uses the previous prompt and region with fresh
world input and a random seed. Accept and reject clear the selection; cancel
keeps it. `/df select previous` restores your last complete selection.

`/df repair`, `/df completion` and `/df material` take a prompt and select the
corresponding engine task. `/df random` picks a demo prompt. `/df settings` shows
your CFG, step counts, seed and preview cadence. Defaults are CFG 5, 30 steps per
stage, and one candidate. Settings persist per player in the world directory.
`/df help` lists the commands; `/dirtflow` is an alias.

The mod pastes recognized block states as generated. Minecraft handles their
subsequent behavior. Acceptance records the current state and native block-entity
data of every changed cell. Undo restores those cells even if they changed later;
other cells stay as they are. Undo/redo journals live in `dirtflow/undo/` inside
the world save and survive normal restarts. Keep them with world backups. Chunk
saves and history files do not form a crash-atomic transaction. New generation
clears the redo branch after local snapshot validation.

## Development

`ServerSession` owns player state and routes events on the server thread.
`engine/` owns HTTP v2 and SSE, `protocol/` owns binary voxel-tensor-v1, and
`world/` owns capture, placement and history. Client rendering is in `src/client/`.

```bash
./gradlew spotlessApply build
./gradlew runClientGameTest
./gradlew runClient
```

The build writes the JAR to `build/libs/` and runs unit tests and server GameTests
in a disposable world. They cover codec failures, cancellation races,
all 29,671 registry states, native inventories,
paste rollback, persistent history and a full 58-cube edit. The client test checks
selection, previews, accept/reject and undo/redo, and saves screenshots under
`build/run/clientGameTest/screenshots/`. On Linux without a display, use
`LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ./gradlew runClientGameTest`.

To test the actual engine's codec and HTTP queue, point this check at a Python
3.12 environment with `dirtflow-engine` and its HTTP dependencies installed:

```bash
DIRTFLOW_ENGINE_PYTHON=/path/to/python ./gradlew engineIntegrationTest
```

This check replaces model sampling with a deterministic volume. Model quality
and GPU inference belong to the engine's tests. `build/reports/` contains the
Java test reports. `./gradlew spotlessCheck` checks Java formatting.

To release, update `version` in `build.gradle`, commit, and push a matching `v*`
tag. CI creates a draft prerelease with the tested JAR and its SHA-256 checksum
after all checks pass. Add release notes on GitHub, then publish the draft.
