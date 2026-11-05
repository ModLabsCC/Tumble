# Physics core

This module owns the native physics simulation and has no Minecraft or Fabric dependencies. It includes code adapted from earlier ModLabs work with permission.

The bundled native build uses double-precision world positions. The core tests check sub-block positions at both world borders.

The core can be used from Java:

```java
import cc.modlabs.tumble.physics.*;

try (var world = new PhysicsWorld()) {
    var position = new PhysicsVector(0, 10, 0);
    long body = world.createBody(new PhysicsBodyDefinition(new PhysicsTransform(position)));
    world.step();
    PhysicsBodySnapshot snapshot = world.snapshot(body);
    world.destroyBody(body);
}
```

Create, use and close each world on the same thread. Body IDs are local to their world. This low-level API creates a separate simulation; it does not give access to Tumble's server-managed world.
