// SPDX-License-Identifier: MPL-2.0
package cc.modlabs.tumble.gametest;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;

public final class RemovalCrashHook {
    public static ServerLevel level;
    public static Object connectionJournal;
    public static void failConnectionWriteAfterStarted(Path directory) throws Exception {
        if (level == null || connectionJournal == null || !"connectionIo".equals(System.getProperty("tumble.removal.stage")) ||
                directory == null || !directory.endsWith("tumble-prop-removal-journal")) return;
        var blocked = Path.of("connection-io-obstruction");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("keep"), "obstruction");
        var field = connectionJournal.getClass().getDeclaredField("path");
        field.setAccessible(true);
        field.set(connectionJournal, blocked);
        Files.writeString(Path.of("removal-crash-reached.txt"), "connectionIo");
    }
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
