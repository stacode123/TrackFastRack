# TrackFastRack

A tiny standalone Forge 1.20.1 mod that isolates the **track placing / breaking
flood-fill optimisation** from the `stacode123/Create` fork and re-applies it to the
published Create mod via a Mixin.

## The fix

It targets `TrackGraph#findDisconnectedGraphs(LevelAccessor, Map)` in
`com.simibubi.create.content.trains.graph.TrackGraph`. This method runs a connected-component
flood fill every time a track is placed or removed.

The released Create (≤ 6.0.8) implements the BFS frontier as an `ArrayList` and pops from the
head with `ArrayList#remove(0)`, which shifts the whole backing array on every pop. That makes
the flood fill O(n²) — on a large network (~80k nodes) a single track change walks billions of
elements. This mod swaps the frontier for an `ArrayDeque` (`poll()` is O(1)) and replaces
`stream().findFirst()` with a plain iterator, making it O(n). The change comes from fork commit
`272e97604` ("Optimise train network hot paths").

## How it works

- `dev/stacode/trackfastrack/mixin/TrackGraphMixin` uses `@Overwrite` to replace the method body,
  with `@Shadow` declarations for the members it needs (`nodes`, `locateNode`,
  `getConnectionsFrom`, `transfer`, `setId`, `setNetId`).
- All `@Shadow`/`@Overwrite` annotations are `remap = false` — the target is a third-party mod
  class (not Minecraft), so nothing needs obfuscation remapping.
- The mixin deliberately lives in `dev.stacode.trackfastrack.mixin`, **not** in Create's package:
  bundling classes into `com.simibubi.create.*` triggers a JPMS split-package error at launch
  (`Modules create and trackfastrack export package ... to module ponder`). Package-private access
  instead goes through `@Shadow` (public methods) and `TARGET#setNetId(...)` (instead of writing
  the package-private `netId` field directly).

## Build

Requires JDK 17+ and network access (it resolves Create, Ponder and Catnip from
`https://maven.createmod.net`).

```bash
./gradlew build          # produces build/libs/trackfastrack-1.0.0.jar
./gradlew runServer      # dev server with the mixin active
```

## Install

Drop the built jar into a Forge 1.20.1 `mods/` folder alongside Create 6.0.x. The mod declares a
required dependency on `create` (≥ 6.0.6) so it will not load without Create.

## Verification

On game start the mod runs a tiny smoke test: it loads `TrackGraph` (forcing the mixin apply) and
calls the replaced method once, logging `TrackGraph flood-fill self-test OK`. A clean line proves
the `@Overwrite` bound to the target method at runtime.