// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

public final class RemovalCrashHook {
    public static ServerLevel level;
    public static void crash(String stage, Path directory) throws Exception {
        if (level == null || !stage.equals(System.getProperty("tumble.removal.stage"))) return;
        if (directory != null && !directory.endsWith("tumble-prop-removal-journal")) return;
        switch (System.getProperty("tumble.removal.flush", "none")) {
            case "block" -> level.getChunkSource().save(true);
            case "entity" -> {
                var field = level.getClass().getDeclaredField("entityManager");
                field.setAccessible(true);
                ((PersistentEntitySectionManager<?>) field.get(level)).saveAll();
            }
            case "none" -> {}
            default -> throw new IllegalArgumentException("unknown flush");
        }
        Files.writeString(Path.of("removal-crash-reached.txt"), stage);
        Runtime.getRuntime().halt(86);
    }
}
