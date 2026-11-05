// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble;

import cc.modlabs.box3d.b3WorldId;
import static cc.modlabs.box3d.global.Box3D.*;

/** Owns one native world; create, step, and close it on the same thread. */
final class PhysicsWorld implements AutoCloseable {
    private final Thread owner = Thread.currentThread();
    private final b3WorldId id = b3CreateWorld(b3DefaultWorldDef());
    private boolean closed;

    void step() {
        checkOwner();
        if (closed) throw new IllegalStateException("Physics world is closed");
        b3World_Step(id, 1.0f / 60.0f, 4);
    }

    @Override
    public void close() {
        checkOwner();
        if (!closed) {
            b3DestroyWorld(id);
            closed = true;
        }
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Physics world accessed from another thread");
    }
}
