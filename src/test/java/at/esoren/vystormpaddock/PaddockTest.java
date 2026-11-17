package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaddockTest {
    @TempDir Path folder;

    @Test
    void trustListRoundTripsAndSkipsGarbage() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        Set<UUID> both = new LinkedHashSet<>(List.of(a, b));
        assertEquals(both, Claims.parse(Claims.format(both)));
        assertEquals(Set.of(a), Claims.parse(a + ",not-a-uuid,"));
        assertTrue(Claims.parse(null).isEmpty());
    }

    @Test
    void slotsFromPermissionsLargestWins() {
        assertEquals(3, Stable.slots(List.of(), 3));
        assertEquals(10, Stable.slots(List.of("vystormpaddock.slots.5", "vystormpaddock.slots.10", "vystormpaddock.slots.x"), 3));
        assertEquals(0, Stable.slots(List.of("vystormpaddock.slots.0"), 3));
    }

    @Test
    void stableSavesLoadsAndFinds() throws Exception {
        Stable stable = new Stable(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID player = UUID.randomUUID();
        Stable.Stored first = new Stable.Stored("aa", "Shadowfax", "HORSE", 1, new byte[]{1, 2, 3});
        Stable.Stored second = new Stable.Stored("bb", "Brownie", "DONKEY", 2, new byte[]{4});
        stable.save(player, List.of(second, first));
        List<Stable.Stored> loaded = stable.list(player);
        assertEquals(List.of("Shadowfax", "Brownie"), loaded.stream().map(Stable.Stored::name).toList());
        assertArrayEquals(new byte[]{1, 2, 3}, loaded.get(0).data());
        assertEquals("Brownie", Stable.find(loaded, "2").name());
        assertEquals("Shadowfax", Stable.find(loaded, "shadow").name());
        assertNull(Stable.find(loaded, "3"));
        stable.save(player, List.of());
        assertTrue(Files.notExists(folder.resolve(player + ".yml")));
        assertTrue(stable.list(player).isEmpty());
    }

    @Test
    void stableKeepsAnEntryWithBrokenDataOnSave() throws Exception {
        UUID player = UUID.randomUUID();
        Path file = folder.resolve(player + ".yml");
        Files.writeString(file, "schema: 1\nanimals:\n  aa:\n    name: Shadowfax\n    type: HORSE\n    stored-at: 1\n    data: '###not base64###'\n"
                + "  bb:\n    name: Brownie\n    type: DONKEY\n    stored-at: 2\n    data: BA==\n");
        Stable stable = new Stable(folder, java.util.logging.Logger.getLogger("test"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        List<Stable.Stored> loaded = stable.list(player);
        assertEquals(List.of("Brownie"), loaded.stream().map(Stable.Stored::name).toList(), "the broken entry is hidden");
        // the player summons Brownie: the stable is saved without it
        stable.save(player, List.of());
        assertTrue(Files.exists(file), "the file with the broken entry is not deleted");
        String text = Files.readString(file);
        assertTrue(text.contains("###not base64###") && text.contains("Shadowfax"), "the broken entry is kept unchanged: " + text);
        assertTrue(!text.contains("Brownie"));
        stable.save(player, List.of(new Stable.Stored("cc", "Daisy", "MULE", 3, new byte[]{7})));
        text = Files.readString(file);
        assertTrue(text.contains("###not base64###") && text.contains("Daisy"));
        assertEquals(List.of("Daisy"), stable.list(player).stream().map(Stable.Stored::name).toList());
    }

    @Test
    void summonCompletesNumbersAndNames() {
        List<Stable.Stored> animals = List.of(new Stable.Stored("aa", "Shadowfax", "HORSE", 1, new byte[0]),
                new Stable.Stored("bb", "Brownie", "DONKEY", 2, new byte[0]), new Stable.Stored("cc", "Shadow", "MULE", 3, new byte[0]));
        assertEquals(List.of("1", "2", "3", "Shadowfax", "Brownie", "Shadow"), Stable.completions(animals, ""));
        assertEquals(List.of("Shadowfax", "Shadow"), Stable.completions(animals, "sha"));
        assertEquals(List.of("2"), Stable.completions(animals, "2"));
        assertTrue(Stable.completions(List.of(), "").isEmpty());
    }

    @Test
    void deathNoticeUsesBlockCoordinates() {
        assertEquals("10 64 -4", PaddockPlugin.coords(new org.bukkit.Location(null, 10.7, 64.2, -3.5)));
    }

    @Test
    void breedingScoreSpansZeroToHundred() {
        assertEquals(0, PaddockPlugin.rating(0.1125, 0.4, 15));
        assertEquals(100, PaddockPlugin.rating(0.3375, 1.0, 30));
        assertEquals(50, PaddockPlugin.rating(0.225, 0.7, 22.5));
    }

    @Test
    void stableIconFallsBackToSaddle() {
        assertEquals(org.bukkit.Material.SADDLE, StableMenu.icon("NOT_A_MOB"));
    }

    @Test
    void horseStatsFormulas() {
        assertEquals(9.49, PaddockPlugin.blocksPerSecond(0.225), 0.01);
        assertEquals(1.09, PaddockPlugin.jumpHeight(0.4), 0.05);
        assertEquals(5.3, PaddockPlugin.jumpHeight(1.0), 0.1);
    }
}
