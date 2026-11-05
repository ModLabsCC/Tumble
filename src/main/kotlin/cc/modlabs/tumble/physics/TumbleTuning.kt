// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.physics

import java.util.EnumMap
import java.util.Properties
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

enum class TumbleTuningKey(
    val id: String,
    val minimum: Double,
    val maximum: Double,
    val default: Double,
    val integer: Boolean = false
) {
    GRAVITY("gravity", -256.0, 256.0, -32.0),
    BUOYANCY("buoyancy", 0.0, 10.0, 1.5),
    GRAB_DISTANCE("grab-distance", 0.1, 64.0, 8.0),
    HOLD_DISTANCE("hold-distance", 0.1, 64.0, 3.0),
    GRAVITY_GUN_GRAB_DISTANCE("gravity-gun-grab-distance", 0.1, 16.0, 6.0),
    GRAVITY_GUN_HOLD_DISTANCE("gravity-gun-hold-distance", 0.1, 16.0, 2.5),
    GRAVITY_GUN_FORCE("gravity-gun-force", 0.1, 50.0, 10.0),
    LAUNCH_SPEED("launch-speed", 0.0, 200.0, 25.0),
    LAUNCH_DAMAGE_TICKS("launch-damage-ticks", 1.0, 1200.0, 60.0, true),
    EXPLOSION_RADIUS("explosion-radius", 0.1, 64.0, 8.0),
    EXPLOSION_SPEED("explosion-speed", 0.0, 200.0, 20.0),
    TNT_IMPACT_SPEED("tnt-impact-speed", 0.1, 200.0, 18.0),
    TNT_EXPLOSION_POWER("tnt-explosion-power", 0.0, 100.0, 4.0),
    MAX_PHYSICS_OBJECTS("max-physics-objects", 1.0, 8192.0, 4096.0, true),
    PLATFORM_GRACE("platform-grace", 0.0, 2.0, 0.35);

    companion object {
        fun byId(id: String) = entries.firstOrNull { it.id == id }
    }
}

class TumbleTuning(private val storage: Path? = null) {
    private val values = EnumMap<TumbleTuningKey, Double>(TumbleTuningKey::class.java).apply {
        TumbleTuningKey.entries.forEach { put(it, it.default) }
    }.also { loaded ->
        val path = storage ?: return@also
        if (!Files.isRegularFile(path)) return@also
        val properties = Properties()
        Files.newBufferedReader(path).use(properties::load)
        TumbleTuningKey.entries.forEach { key ->
            properties.getProperty(key.id)?.toDoubleOrNull()?.takeIf { value ->
                value.isFinite() && value in key.minimum..key.maximum && (!key.integer || value % 1.0 == 0.0)
            }?.let { loaded[key] = it }
        }
    }

    fun get(key: TumbleTuningKey) = values.getValue(key)

    fun set(key: TumbleTuningKey, value: Double) {
        require(value.isFinite() && value in key.minimum..key.maximum) { "${key.id} is out of range" }
        require(!key.integer || value % 1.0 == 0.0) { "${key.id} must be an integer" }
        values[key] = value
        storage?.let(::persist)
    }

    private fun persist(path: Path) {
        val parent = path.toAbsolutePath().parent
        Files.createDirectories(parent)
        val properties = Properties().apply {
            TumbleTuningKey.entries.forEach { key ->
                setProperty(key.id, this@TumbleTuning.values.getValue(key).toString())
            }
        }
        val temporary = Files.createTempFile(parent, "tumble-", ".properties.tmp")
        try {
            Files.newBufferedWriter(temporary).use { properties.store(it, "Project Tumble runtime tuning") }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
