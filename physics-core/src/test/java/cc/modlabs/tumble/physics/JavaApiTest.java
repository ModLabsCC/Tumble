// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class JavaApiTest {
    @Test
    void createsStepsAndRemovesABodyFromJava() {
        try (var world = new PhysicsWorld()) {
            var definition = new PhysicsBodyDefinition(new PhysicsTransform(new PhysicsVector(0, 10, 0)));
            long body = world.createBody(definition);
            assertTrue(world.containsBody(body));
            world.step();
            var snapshot = world.snapshot(body);
            assertNotNull(snapshot);
            assertEquals(body, snapshot.getBodyId());
            assertTrue(snapshot.getTransform().getPosition().getY() < 10);
            assertTrue(world.setLinearVelocity(body, new PhysicsVector(1, 0, 0)));
            long other = world.createBody(new PhysicsBodyDefinition(new PhysicsTransform(new PhysicsVector(2, 10, 0))));
            assertTrue(world.createWeld(body, other));
            assertTrue(world.destroyWeld(body, other));
            assertTrue(world.destroyBody(other));
            assertTrue(world.destroyBody(body));
            assertFalse(world.containsBody(body));
        }
    }
}
