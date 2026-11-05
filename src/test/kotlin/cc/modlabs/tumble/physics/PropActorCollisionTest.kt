// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.cos
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropActorCollisionTest {
    @Test
    fun `aabb contact follows the hard collision face`() {
        val contact = PropActorCollision.aabbContact(
            aabb(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5),
            aabb(0.49, -0.9, -0.3, 1.09, 0.9, 0.3)
        )

        assertNotNull(contact)
        assertEquals(1.0, contact.normal.x)
        assertEquals(0.01, contact.penetration, 1e-9)
    }

    @Test
    fun `side overlap pushes actor away from prop`() {
        val contact = PropActorCollision.contact(
            PhysicsTransform(PhysicsVector.ZERO),
            aabb(0.4, -0.9, -0.3, 1.0, 0.9, 0.3)
        )

        assertNotNull(contact)
        assertTrue(contact.translation.x > 0.0)
        assertEquals(0.0, contact.translation.y, 1e-9)
        assertEquals(0.1, contact.penetration, 1e-9)
    }

    @Test
    fun `actor standing in top face is moved upward`() {
        val contact = PropActorCollision.contact(
            PhysicsTransform(PhysicsVector.ZERO),
            aabb(-0.3, 0.45, -0.3, 0.3, 2.25, 0.3)
        )

        assertNotNull(contact)
        assertTrue(contact.translation.y > 0.0)
        assertEquals(0.05, contact.penetration, 1e-9)
    }

    @Test
    fun `separated actor has no contact`() {
        assertNull(
            PropActorCollision.contact(
                PhysicsTransform(PhysicsVector.ZERO),
                aabb(0.6, -0.9, -0.3, 1.2, 0.9, 0.3)
            )
        )
    }

    @Test
    fun `rotated prop uses oriented box rather than broadphase bounds`() {
        val angle = PI / 4.0
        val rotation = PhysicsRotation(0.0, sin(angle / 2.0), 0.0, cos(angle / 2.0))

        assertNotNull(
            PropActorCollision.contact(
                PhysicsTransform(PhysicsVector.ZERO, rotation),
                aabb(0.55, -0.2, -0.1, 0.75, 0.2, 0.1)
            )
        )
        assertNull(
            PropActorCollision.contact(
                PhysicsTransform(PhysicsVector.ZERO, rotation),
                aabb(0.62, -0.2, 0.62, 0.70, 0.2, 0.70)
            )
        )
    }

    @Test
    fun `partial local box preserves slab height`() {
        val slab = PhysicsBox(
            PhysicsVector(-0.5, -0.5, -0.5),
            PhysicsVector(0.5, 0.0, 0.5)
        )

        assertNotNull(
            PropActorCollision.contact(
                PhysicsTransform(PhysicsVector.ZERO),
                slab,
                aabb(-0.2, -0.05, -0.2, 0.2, 1.75, 0.2)
            )
        )
        assertNull(
            PropActorCollision.contact(
                PhysicsTransform(PhysicsVector.ZERO),
                slab,
                aabb(-0.2, 0.1, -0.2, 0.2, 1.9, 0.2)
            )
        )
    }

    @Test
    fun `swept actor lands without tunneling and slides along side`() {
        val shape = PropSweepShape(0, PhysicsTransform(PhysicsVector.ZERO), PhysicsBox.UNIT)
        val falling = PropActorCollision.clipMovement(
            aabb(-0.3, 2.0, -0.3, 0.3, 3.8, 0.3),
            PhysicsVector(0.0, -5.0, 0.0),
            listOf(shape)
        )
        val sliding = PropActorCollision.clipMovement(
            aabb(0.6, -0.4, -0.3, 1.2, 1.4, 0.3),
            PhysicsVector(-1.0, 0.0, 0.5),
            listOf(shape)
        )

        assertTrue(falling.displacement.y > -1.51)
        assertTrue(falling.contacts.any { it.normal.y > 0.9 })
        assertTrue(sliding.displacement.x > -0.11)
        assertTrue(sliding.displacement.z > 0.49)
    }

    @Test
    fun `standing actor walks and jumps without sticking to the prop`() {
        val shape = PropSweepShape(0, PhysicsTransform(PhysicsVector.ZERO), PhysicsBox.UNIT)
        val standing = aabb(-0.3, 0.5, -0.3, 0.3, 2.3, 0.3)

        val walking = PropActorCollision.clipMovement(
            standing,
            PhysicsVector(0.4, -0.08, 0.0),
            listOf(shape)
        )
        val jumpingAtSide = PropActorCollision.clipMovement(
            aabb(0.5, -0.4, -0.3, 1.1, 1.4, 0.3),
            PhysicsVector(-0.1, 0.42, 0.0),
            listOf(shape)
        )

        assertEquals(0.4, walking.displacement.x, 1e-9)
        assertEquals(0.0, walking.displacement.y, 1e-6)
        assertEquals(0.0, jumpingAtSide.displacement.x, 1e-6)
        assertEquals(0.42, jumpingAtSide.displacement.y, 1e-9)
    }

    @Test
    fun `actor crosses a dense prop floor equally in both directions`() {
        val shapes = (-4..4).flatMap { x ->
            (-1..1).map { z ->
                PropSweepShape(
                    x * 3 + z,
                    PhysicsTransform(PhysicsVector(x.toDouble(), 0.0, z.toDouble())),
                    PhysicsBox.UNIT
                )
            }
        }
        val standing = aabb(-0.3, 0.5, -0.3, 0.3, 2.3, 0.3)

        val positive = PropActorCollision.clipMovement(standing, PhysicsVector(1.2, -0.08, 0.0), shapes)
        val negative = PropActorCollision.clipMovement(standing, PhysicsVector(-1.2, -0.08, 0.0), shapes)

        assertEquals(1.2, positive.displacement.x, 1e-9)
        assertEquals(-1.2, negative.displacement.x, 1e-9)
        assertEquals(positive.displacement.y, negative.displacement.y, 1e-9)
    }

    @Test
    fun `standing weight does not become a repeated downward impulse`() {
        assertTrue(!transfersActorImpulse(PhysicsVector(0.0, 1.0, 0.0)))
        assertTrue(transfersActorImpulse(PhysicsVector(1.0, 0.0, 0.0)))
    }

    @Test
    fun `actor push starts responsively and stops accelerating at minecart speed`() {
        assertTrue(actorPushImpulse(2.0, 0.0, 0.65) > 0.35)
        assertEquals(0.0, actorPushImpulse(2.0, 1.25, 0.65), 1e-9)
    }

    @Test
    fun `tilted side does not consume jump movement`() {
        val angle = -PI / 12.0
        val rotation = PhysicsRotation(0.0, 0.0, sin(angle / 2.0), cos(angle / 2.0))
        val shape = PropSweepShape(0, PhysicsTransform(PhysicsVector.ZERO, rotation), PhysicsBox.UNIT)

        val jumping = PropActorCollision.clipMovement(
            aabb(0.6, -0.4, -0.3, 1.2, 1.4, 0.3),
            PhysicsVector(-0.1, 0.42, 0.0),
            listOf(shape)
        )

        assertEquals(0.42, jumping.displacement.y, 1e-9)
    }

    private fun aabb(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double) =
        PhysicsAabb(PhysicsVector(minX, minY, minZ), PhysicsVector(maxX, maxY, maxZ))
}
