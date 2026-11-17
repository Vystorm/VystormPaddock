package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StablePageTest {
    @TempDir Path folder;

    private static Stable.Stored animal(int i) {
        return new Stable.Stored("id-" + i, "Silver " + i, i % 2 == 0 ? "HORSE" : "SKELETON_HORSE", i, new byte[]{1});
    }

    @Test
    void everyAnimalIsReachableAcrossPages() {
        List<Stable.Stored> all = IntStream.range(0, 137).mapToObj(StablePageTest::animal).toList();
        List<Stable.Stored> seen = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            StablePage page = StablePage.of(all, "", i);
            assertEquals(4, page.pages());
            assertEquals(137, page.matches());
            seen.addAll(page.animals());
        }
        assertEquals(all, seen);
    }

    @Test
    void givenPanelPageSize_whenBrowsingAllPages_thenEveryStoredAnimalRemainsReachable() {
        List<Stable.Stored> all = IntStream.range(0, 137).mapToObj(StablePageTest::animal).toList();
        List<Stable.Stored> seen = new ArrayList<>();
        for (int i = 0; i < 18; i++) {
            StablePage page = StablePage.of(all, "horse", i, StablePage.Sort.OLDEST, PaddockPanel.PAGE_SIZE);
            assertEquals(18, page.pages());
            assertTrue(page.animals().size() <= 8);
            seen.addAll(page.animals());
        }
        assertEquals(all, seen);
    }

    @Test
    void boundariesAndEmptyResultsAreClamped() {
        for (int size : List.of(0, 1, 44, 45, 46, 90, 91)) {
            List<Stable.Stored> all = IntStream.range(0, size).mapToObj(StablePageTest::animal).toList();
            StablePage last = StablePage.of(all, null, Integer.MAX_VALUE);
            assertEquals(Math.max(1, (size + 44) / 45) - 1, last.number());
            assertEquals(0, StablePage.of(all, "", -5).number());
        }
        StablePage empty = StablePage.of(List.of(animal(0)), "missing", 5);
        assertEquals(1, empty.pages());
        assertEquals(0, empty.matches());
        assertNull(empty.animalId(0));
    }

    @Test
    void searchesNamesAndTypesWithoutChangingOrderOrInput() {
        List<Stable.Stored> all = List.of(animal(0), animal(1), animal(2));
        assertEquals(List.of(animal(1).id()), StablePage.of(all, " SKELETON_horse ", 0).animals().stream().map(Stable.Stored::id).toList());
        assertEquals("id-2", StablePage.of(all, "sILVer 2", 0).animalId(0));
        assertEquals(all, StablePage.of(all, "horse", 0).animals());
        assertEquals(3, all.size());
    }

    @Test
    void snapshotKeepsIdsAfterSourceChangesAndRejectsControlSlots() {
        List<Stable.Stored> source = new ArrayList<>(List.of(animal(0), animal(1)));
        StablePage page = StablePage.of(source, "", 0);
        source.removeFirst();
        assertEquals("id-0", page.animalId(0));
        assertEquals("id-1", page.animalId(1));
        for (int slot : List.of(-1, 2, 45, 48, 49, 53)) assertNull(page.animalId(slot));
    }

    @Test
    void sortRunsBeforePaginationAndRetainsStableIds() {
        List<Stable.Stored> all = IntStream.range(0, 91).mapToObj(StablePageTest::animal).toList();
        StablePage first = StablePage.of(all, "", 0, StablePage.Sort.NEWEST);
        StablePage second = StablePage.of(all, "", 1, first.sort());
        assertEquals("id-90", first.animalId(0));
        assertEquals("id-46", first.animalId(44));
        assertEquals("id-45", second.animalId(0));
        assertEquals("id-0", all.getFirst().id(), "sorting does not mutate the source list");
        assertEquals(StablePage.Sort.NEWEST, second.sort());
    }

    @Test
    void namesAndTypesSortDeterministicallyAfterFiltering() {
        Stable.Stored z = new Stable.Stored("z", "Zulu", "HORSE", 1, new byte[0]);
        Stable.Stored a = new Stable.Stored("a", "alpha", "SKELETON_HORSE", 3, new byte[0]);
        Stable.Stored b = new Stable.Stored("b", "Bravo", "HORSE", 2, new byte[0]);
        List<Stable.Stored> all = List.of(z, a, b);
        assertEquals(List.of(a, b, z), StablePage.of(all, "horse", 0, StablePage.Sort.NAME).animals());
        assertEquals(List.of(b, z, a), StablePage.of(all, "horse", 0, StablePage.Sort.TYPE).animals());
        assertEquals(List.of(z, b, a), StablePage.of(all, "horse", 0, StablePage.Sort.OLDEST).animals());
        assertEquals(StablePage.Sort.OLDEST, StablePage.Sort.TYPE.next());
        assertEquals(StablePage.Sort.NEWEST, StablePage.Sort.OLDEST.next());
    }

    @Test
    void staleMenuSelectionNeverSummonsReplacement() throws Exception {
        Stable stable = new Stable(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        UUID owner = UUID.randomUUID();
        stable.save(owner, List.of(animal(1)));
        PaddockPlugin plugin = mock(PaddockPlugin.class, CALLS_REAL_METHODS);
        Lang lang = mock(Lang.class);
        set(plugin, "lang", lang);
        set(plugin, "stable", stable);
        set(plugin, "disabledWorlds", java.util.Set.of());
        set(plugin, "lastSummon", new java.util.HashMap<>());
        Player player = mock(Player.class);
        when(player.hasPermission("vystormpaddock.use")).thenReturn(true);
        when(player.getUniqueId()).thenReturn(owner);
        org.bukkit.World world = mock(org.bukkit.World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);
        plugin.summonId(player, "id-0");
        verify(lang).send(player, "not-in-stable");
        assertEquals("id-1", stable.list(owner).getFirst().id());
    }

    @Test
    void menuSelectionRechecksPermission() throws Exception {
        PaddockPlugin plugin = mock(PaddockPlugin.class, CALLS_REAL_METHODS);
        Lang lang = mock(Lang.class);
        set(plugin, "lang", lang);
        Player player = mock(Player.class);
        plugin.summonId(player, "id-0");
        verify(lang).send(player, "denied");
    }

    private static void set(PaddockPlugin plugin, String name, Object value) throws Exception {
        var field = PaddockPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(plugin, value);
    }
}
