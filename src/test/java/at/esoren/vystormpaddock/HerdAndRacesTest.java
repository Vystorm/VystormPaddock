package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HerdAndRacesTest {
    @TempDir Path folder;

    // ------------------------------------------------------------------ herd book

    private static Herd.Entry entry(UUID id, String name, double x, long seen) { return new Herd.Entry(id, name, "HORSE", "world", x, 64, 0, seen); }

    @Test
    void herdBookSurvivesRestart() throws Exception {
        UUID player = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        Herd herd = new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        herd.put(player, entry(a, "Shadowfax", 10, 1));
        herd.put(player, entry(b, "Brownie", -20.26, 2));
        herd.flushAll();
        Herd again = new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        List<Herd.Entry> loaded = again.list(player);
        assertEquals(List.of("Brownie", "Shadowfax"), loaded.stream().map(Herd.Entry::name).toList());
        assertEquals(-20.3, loaded.getFirst().x(), 0.001);
        assertTrue(again.remove(player, a));
        assertFalse(again.remove(player, a));
        assertTrue(again.remove(player, b));
        assertTrue(Files.notExists(folder.resolve(player + ".yml")));
    }

    @Test
    void smallMovesStayInMemoryUntilFlush() throws Exception {
        UUID player = UUID.randomUUID(), a = UUID.randomUUID();
        Herd herd = new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        herd.put(player, entry(a, "Shadowfax", 0, 1));
        herd.put(player, entry(a, "Shadowfax", 1, 2));
        assertEquals(0, new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(player, a).x(), 0.001);
        assertEquals(1, herd.get(player, a).x(), 0.001);
        herd.flush(player);
        assertEquals(1, new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).get(player, a).x(), 0.001);
        assertTrue(Herd.worthSaving(entry(a, "Shadowfax", 0, 1), entry(a, "Shadowfax", 5, 2)));
        assertTrue(Herd.worthSaving(entry(a, "Shadowfax", 0, 1), entry(a, "Bucky", 0, 2)));
        assertTrue(Herd.worthSaving(null, entry(a, "Shadowfax", 0, 1)));
    }

    @Test
    void herdBookDropsTheLongestUnseenAboveTheLimit() throws Exception {
        UUID player = UUID.randomUUID(), oldest = UUID.randomUUID();
        Herd herd = new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        herd.put(player, entry(oldest, "Old", 0, 0));
        for (int i = 1; i <= Herd.MAX; i++) herd.put(player, entry(UUID.randomUUID(), "H" + i, 0, i));
        assertEquals(Herd.MAX, herd.list(player).size());
        assertNull(herd.get(player, oldest));
    }

    @Test
    void herdBookSkipsBrokenIds() throws Exception {
        UUID player = UUID.randomUUID(), good = UUID.randomUUID();
        Files.writeString(folder.resolve(player + ".yml"), "schema: 1\nanimals:\n  nonsense:\n    name: X\n  " + good + ":\n    name: Good\n    world: w\n    x: 1\n");
        List<Herd.Entry> loaded = new Herd(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).list(player);
        assertEquals(1, loaded.size());
        assertEquals("Good", loaded.getFirst().name());
    }

    @Test
    void arrowPointsRelativeToView() {
        // yaw 0 looks south (+z)
        assertEquals("↑", Herd.arrow(0, 0, 10));
        assertEquals("↓", Herd.arrow(0, 0, -10));
        assertEquals("→", Herd.arrow(0, -10, 0)); // west is on your right when you look south
        assertEquals("←", Herd.arrow(0, 10, 0));
        assertEquals("↑", Herd.arrow(180, 0, -10)); // looking north, target north
        assertEquals("↗", Herd.arrow(-90, 10, 10)); // looking east, target south-east
        assertEquals("↑", Herd.arrow(720, 0, 5));
    }

    @Test
    void distanceInMetresAndKilometres() {
        assertEquals("12 m", Herd.distance(12.4));
        assertEquals("1.5 km", Herd.distance(1500));
    }

    // ------------------------------------------------------------------ races

    private static Races.Course course(String name) {
        Races.Course course = new Races.Course(name, "world", 4);
        course.points().add(new double[]{0, 64, 0});
        course.points().add(new double[]{50, 64, 0});
        course.points().add(new double[]{50, 64, 50});
        return course;
    }

    @Test
    void checkpointsCountInOrderOnly() {
        Races.Course course = course("Oval");
        assertEquals(1, Races.advance(course, 1, 0, 64, 0)); // standing at the start does not skip gate 1
        assertEquals(1, Races.advance(course, 1, 50, 64, 50)); // the finish before gate 1 does not count
        assertEquals(2, Races.advance(course, 1, 52, 66, 1));
        assertEquals(3, Races.advance(course, 2, 50, 64, 47));
        assertEquals(3, Races.advance(course, 3, 50, 64, 50)); // done, stays done
        assertFalse(Races.reached(new double[]{0, 64, 0}, 4, 0, 80, 0)); // far above does not count
        assertTrue(Races.reached(new double[]{0, 64, 0}, 4, 2.8, 61.5, 2.8));
    }

    @Test
    void startCircleFindsReadyCoursesOnly() {
        Races.Course ready = course("Oval");
        Races.Course unfinished = new Races.Course("Draft", "world", 4);
        unfinished.points().add(new double[]{0, 64, 0});
        assertNull(Races.startAt(List.of(unfinished), "world", 0, 64, 0));
        assertSame(ready, Races.startAt(List.of(unfinished, ready), "world", 1, 64, 1));
        assertNull(Races.startAt(List.of(ready), "world_nether", 0, 64, 0));
        assertNull(Races.startAt(List.of(ready), "world", 10, 64, 0));
    }

    @Test
    void personalBestsAndRanking() {
        Races.Course course = course("Oval");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID(), c = UUID.randomUUID();
        assertTrue(Races.record(course, a, "Anna", 30_000, 1));
        assertFalse(Races.record(course, a, "Anna", 31_000, 2));
        assertTrue(Races.record(course, a, "Anna", 29_500, 3));
        assertTrue(Races.record(course, b, "Ben", 29_500, 4));
        assertTrue(Races.record(course, c, "Cleo", 20_000, 5));
        assertEquals(List.of("Cleo", "Anna", "Ben"), Races.top(course, 10).stream().map(Races.Time::name).toList());
        assertEquals(1, Races.top(course, 1).size());
        assertEquals(2, Races.rank(course, a));
        assertEquals(0, Races.rank(course, UUID.randomUUID()));
    }

    @Test
    void timesReadLikeAStopwatch() {
        assertEquals("0:05.43", Races.time(5_437));
        assertEquals("1:02.35", Races.time(62_350));
        assertEquals("12:00.00", Races.time(720_000));
    }

    @Test
    void coursesAndTimesSurviveRestart() throws Exception {
        Path file = folder.resolve("races.yml");
        Races races = new Races(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        races.load(); // no file yet
        Races.Course oval = races.create("Oval", "world", 5);
        oval.points().addAll(course("x").points());
        UUID rider = UUID.randomUUID();
        Races.record(oval, rider, "Anna", 12_345, 99);
        races.save();
        Races again = new Races(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        again.load();
        Races.Course loaded = again.get("oval");
        assertEquals("Oval", loaded.name());
        assertEquals(5, loaded.radius());
        assertEquals(3, loaded.points().size());
        assertEquals(50, loaded.points().get(2)[2]);
        assertEquals(12_345, loaded.times().get(rider).millis());
        assertTrue(again.delete("OVAL"));
        assertNull(again.get("Oval"));
    }

    @Test
    void brokenRaceFileIsNeverOverwritten() throws Exception {
        Path file = folder.resolve("races.yml");
        Files.writeString(file, "courses: [unclosed");
        Races races = new Races(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertThrows(IOException.class, races::load);
        assertTrue(races.broken());
        assertThrows(IOException.class, races::save);
        assertEquals("courses: [unclosed", Files.readString(file));
    }

    @Test
    void pointsParseAndSkipGarbage() {
        assertEquals(-3.5, Races.point("1 64.0 -3.5")[2]);
        assertNull(Races.point("1 2"));
        assertNull(Races.point("a b c"));
        assertEquals("1.0 64.0 -3.5", Races.format(new double[]{1, 64, -3.5}));
        assertTrue(Races.NAME.matcher("Big_Oval-2").matches());
        assertFalse(Races.NAME.matcher("bad name").matches());
    }

    // ------------------------------------------------------------------ texts

    private static YamlConfiguration lang(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(HerdAndRacesTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
    }

    @Test
    void englishAndGermanHaveTheSameKeys() {
        Set<String> en = lang("en").getKeys(false), de = lang("de").getKeys(false);
        assertEquals(en, de);
        for (String key : List.of("herd-lore", "herd-track", "race-progress", "race-record", "usage-course", "vmenu-herd", "vmenu-races"))
            assertTrue(en.contains(key), key);
    }

    @Test
    void usageLinesHaveNoRawArgumentTags() {
        for (String code : List.of("en", "de")) {
            YamlConfiguration yaml = lang(code);
            for (String key : yaml.getKeys(false))
                if (key.startsWith("usage")) assertFalse(yaml.getString(key).matches(".*<(?!/?(gray|red|green|yellow|gold|white|dark_gray)>)[a-zA-Z_-]+>.*"), code + ":" + key);
        }
    }
}
