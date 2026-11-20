package at.esoren.vystormpaddock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Herd book: the last known place of every claimed animal a player owns, one YAML file per player under {@code herd/}.
 * Entries are cached while in use; a file is only rewritten when an animal is new, renamed or moved a few blocks.
 */
public final class Herd {
    /** One animal out in the world. */
    public record Entry(UUID id, String name, String type, String world, double x, double y, double z, long seen) {}

    /** Most entries kept per player by default ({@code herd-book.max-animals}); the longest unseen ones drop out first. */
    static final int MAX = 200;
    /** Highest value {@code herd-book.max-animals} may set. */
    static final int MAX_LIMIT = 10_000;
    /** Moves shorter than this (blocks) are kept in memory only. */
    static final double SAVE_DISTANCE = 4;

    private final Path folder;
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    private final Map<UUID, Map<UUID, Entry>> cache = new HashMap<>();
    private volatile int max = MAX;

    /** Sets the most entries kept per player (1 to {@link #MAX_LIMIT}); a smaller book shrinks at its next new entry (1.18.2). */
    void max(int limit) { max = Math.clamp(limit, 1, MAX_LIMIT); }

    int max() { return max; }

    /** @param files Core's file part, {@code api.data().files()} */
    public Herd(Path folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) { this.folder = folder; this.files = files; }

    private Path file(UUID player) { return folder.resolve(player + ".yml"); }

    /** All entries of a player, newest sighting first. */
    public List<Entry> list(UUID player) throws IOException {
        List<Entry> out = new ArrayList<>(book(player).values());
        out.sort((a, b) -> Long.compare(b.seen(), a.seen()));
        return out;
    }

    public Entry get(UUID player, UUID animal) throws IOException { return book(player).get(animal); }

    /** Records a sighting; writes the file only when it matters (see {@link #worthSaving}). */
    public void put(UUID player, Entry entry) throws IOException {
        Map<UUID, Entry> book = book(player);
        Entry old = book.put(entry.id(), entry);
        if (!worthSaving(old, entry)) return;
        int limit = max;
        if (book.size() > limit) {
            List<Entry> byAge = new ArrayList<>(book.values());
            byAge.sort((a, b) -> Long.compare(a.seen(), b.seen()));
            for (int i = 0; i < byAge.size() - limit; i++) book.remove(byAge.get(i).id());
        }
        save(player, book);
    }

    public boolean remove(UUID player, UUID animal) throws IOException {
        Map<UUID, Entry> book = book(player);
        if (book.remove(animal) == null) return false;
        save(player, book);
        return true;
    }

    /** Writes the cached book of a player (also the in-memory-only moves) and drops it from the cache. */
    public void flush(UUID player) throws IOException {
        Map<UUID, Entry> book = cache.remove(player);
        if (book != null) save(player, book);
    }

    /** Writes and drops every cached book (plugin disable). */
    public void flushAll() throws IOException {
        for (UUID player : List.copyOf(cache.keySet())) flush(player);
    }

    static boolean worthSaving(Entry old, Entry now) {
        if (old == null || !old.name().equals(now.name()) || !old.world().equals(now.world())) return true;
        double dx = old.x() - now.x(), dy = old.y() - now.y(), dz = old.z() - now.z();
        return dx * dx + dy * dy + dz * dz >= SAVE_DISTANCE * SAVE_DISTANCE;
    }

    private Map<UUID, Entry> book(UUID player) throws IOException {
        Map<UUID, Entry> book = cache.get(player);
        if (book == null) {
            book = read(player);
            cache.put(player, book);
        }
        return book;
    }

    private Map<UUID, Entry> read(UUID player) throws IOException {
        Path file = file(player);
        Map<UUID, Entry> out = new LinkedHashMap<>();
        if (!Files.exists(file)) return out;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file)); }
        catch (InvalidConfigurationException broken) { throw new IOException("Herd file " + file + " is broken", broken); }
        ConfigurationSection animals = yaml.getConfigurationSection("animals");
        if (animals == null) return out;
        for (String key : animals.getKeys(false)) {
            ConfigurationSection entry = animals.getConfigurationSection(key);
            if (entry == null) continue;
            try {
                UUID id = UUID.fromString(key);
                out.put(id, new Entry(id, entry.getString("name", "?"), entry.getString("type", "HORSE"), entry.getString("world", "world"),
                        entry.getDouble("x"), entry.getDouble("y"), entry.getDouble("z"), entry.getLong("seen")));
            } catch (IllegalArgumentException broken) {
                // skip entries with a broken id; they vanish with the next write
            }
        }
        return out;
    }

    private void save(UUID player, Map<UUID, Entry> book) throws IOException {
        Files.createDirectories(folder);
        Path file = file(player);
        if (book.isEmpty()) { Files.deleteIfExists(file); return; }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        for (Entry entry : book.values()) {
            String base = "animals." + entry.id();
            yaml.set(base + ".name", entry.name());
            yaml.set(base + ".type", entry.type());
            yaml.set(base + ".world", entry.world());
            yaml.set(base + ".x", Math.round(entry.x() * 10) / 10.0);
            yaml.set(base + ".y", Math.round(entry.y() * 10) / 10.0);
            yaml.set(base + ".z", Math.round(entry.z() * 10) / 10.0);
            yaml.set(base + ".seen", entry.seen());
        }
        files.write(file, yaml);
    }

    private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};

    /**
     * Arrow from the player's view to a target {@code dx}/{@code dz} blocks away. Minecraft yaw: 0 = south (+z),
     * growing clockwise, so a positive difference means "turn right".
     */
    static String arrow(float yaw, double dx, double dz) {
        double target = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = ((target - yaw) % 360 + 540) % 360 - 180;
        int index = (int) Math.floorMod(Math.round(relative / 45), 8);
        return ARROWS[index];
    }

    /** "123 m" below a kilometre, "1.2 km" above. */
    static String distance(double blocks) {
        return blocks < 1000 ? Math.round(blocks) + " m" : String.format(Locale.ROOT, "%.1f km", blocks / 1000);
    }
}
