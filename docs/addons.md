# Making an addon

The `cc.modlabs.tumble.api.TumbleApi` class gives server-side mods access to Tumble props. Call it from the Minecraft server thread, such as a command or server tick callback. Keep the returned UUID if you need to find the prop again.

```java
UUID prop = TumbleApi.spawn(level, position, Blocks.OAK_LOG.defaultBlockState());

// On a later server tick, after checking prop is not null:
if (TumbleApi.snapshot(level, prop) != null) {
    TumbleApi.applyImpulse(level, prop, new Vec3(0, 3, 0));
}
```

The example shows the calls, not a complete wait loop: a newly spawned body will usually not be ready in the same tick. `status` distinguishes a pending body from a failed one, or a prop that is unavailable because it was removed or unloaded. It also reports failure if the physics service stops. `snapshot` returns `null` until a body has a published snapshot and while its prop is unloaded. `applyImpulse` returns whether the command was queued, not whether it has been simulated. It rejects impulses that would change the prop's speed by more than 64 blocks per second. `remove` returns `false` if the prop is not loaded in that level.

Spawning requires a loaded chunk inside the world border and a block with a collision shape. Blocks with block entities are not supported. Addons control their own permissions: check the player and location before spawning or removing props on their behalf. Force components must be finite and fit in a float.

The API is early and may change as assemblies are added. The `physics-core` module also has a [separate low-level API](../physics-core/README.md); it creates its own world and does not access the running server simulation.

For a small working Fabric mod, see [simple-addon](../examples/simple-addon). Build Tumble first, then run `./gradlew -p examples/simple-addon build` from the repository root. Install both JARs and use `/tumble_example` as an operator to spawn a prop.
