package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StableCorruptionTest {
    @TempDir Path folder;
    private final UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Test void scalarAnimalEntrySurvivesSavingAndRemovingOtherAnimals() throws Exception {
        Path file = folder.resolve(owner + ".yml");
        Files.writeString(file, "schema: 1\nanimals:\n  damaged: preserve-me\n");
        Stable stable = new Stable(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(stable.list(owner).isEmpty());
        stable.save(owner, List.of(new Stable.Stored("healthy", "Horse", "HORSE", 1, new byte[]{1, 2})));
        assertEquals("preserve-me", YamlConfiguration.loadConfiguration(file.toFile()).getString("animals.damaged"));
        stable.save(owner, List.of());
        assertTrue(Files.exists(file));
        assertEquals("preserve-me", YamlConfiguration.loadConfiguration(file.toFile()).getString("animals.damaged"));
    }

    @Test void nonSectionAnimalRootIsNeverReplacedOrDeleted() throws Exception {
        Path file = folder.resolve(owner + ".yml");
        String original = "schema: 1\nanimals: [damaged, but, recoverable]\n";
        Files.writeString(file, original);
        Stable stable = new Stable(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertThrows(IOException.class, () -> stable.list(owner));
        assertThrows(IOException.class, () -> stable.save(owner, List.of()));
        assertEquals(original, Files.readString(file));
    }

    @Test void validAnimalsStillRoundTripAndEmptyStablesAreRemoved() throws Exception {
        Stable stable = new Stable(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        stable.save(owner, List.of(new Stable.Stored("healthy", "Horse", "HORSE", 1, new byte[]{1, 2})));
        assertEquals("Horse", stable.list(owner).getFirst().name());
        assertArrayEquals(new byte[]{1, 2}, stable.list(owner).getFirst().data());
        stable.save(owner, List.of());
        assertFalse(Files.exists(folder.resolve(owner + ".yml")));
    }
}
