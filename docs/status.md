# What's left

Tumble is an early development build. It has single-block props, tools, a server-side addon API and automated server and client GameTests. The work below is still needed before calling it a finished release.

## Moving structures

- Finish rigid assemblies: convert connected blocks into one body, preserve their shapes and states, and make them safe to save, unload and reload.
- Prevent lost or duplicated blocks if conversion or rollback is interrupted. Handle unloaded terrain, rotating structures and attached props safely.
- Persist and restore welds and other constraints across unloads and restarts. Finish motors and the remaining structure tools.
- Test a 250-block staircase with multiple players, including standing, riding, reconnecting and crossing chunk boundaries.

An assembly prototype lives on the `codex/tumble-assemblies` branch. It is not ready for normal play: terrain-unload handling, connected-body persistence and recovery still have known gaps.

The `codex/tumble-assembly-recovery` branch reproduces a case where a linked prop saved before an assembly unloads can restart while its terrain is missing. Its GameTest currently fails. Durable connections and crash-safe recovery remain unfinished.

## Reliability and compatibility

- Exercise real chunk unload and disk reload, multiplayer latency and reconnects, protection mods, and terrain with mixed materials and partial shapes. Automated tests cover some of these paths, but not all combinations.
- Measure full-server performance and memory use with many props and players. The existing benchmark measures the physics core, not an entire server.
- Validate the example addon as a loaded mod, beyond compiling it and testing the API directly.
- Maintain snapshot support separately from the Minecraft 26.3 build. The experimental `codex/tumble-snapshot` branch targets a specific snapshot and needs ongoing compatibility checks.
- Research block entities, inventories and redstone in moving structures before supporting them. Conversion currently rejects blocks with block entities.

## Releases

Jenkins runs the build, GameTests and benchmark, and archives the resulting JARs. A GitHub release and its downloadable JAR have not been published yet. Prepare release notes and publish a tested build when the remaining release checks are complete.
