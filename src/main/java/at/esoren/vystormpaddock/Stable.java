package at.esoren.vystormpaddock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * A player's virtual stable: animals stored as serialized entity data (Paper keeps them upgradable across versions),
 * one YAML file per player under {@code stables/}. Writes go to a temp file first and replace the old one atomically.
 */
public final class Stable {
    /** One stored animal. {@code data} is the serialized entity, Base64 in the file. */
    public record Stored(String id, String name, String type, long storedAt, byte[] data) {}

    private final Path folder;
    private final Logger log;
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;
    /** Broken entries already reported, so opening the stable does not repeat the warning. */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** @param files Core's file part, {@code api.data().files()} */
    public Stable(Path folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) { this(folder, Logger.getLogger("VystormPaddock"), files); }

    public Stable(Path folder, Logger log, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) {
        this.folder = folder;
        this.log = log;
        this.files = files;
    }

    private Path file(UUID player) { return folder.resolve(player + ".yml"); }

    private static YamlConfiguration read(Path file) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.loadFromString(Files.readString(file)); }
        catch (InvalidConfigurationException broken) { throw new IOException("Stable file " + file + " is broken", broken); }
        if (yaml.contains("animals") && !yaml.isConfigurationSection("animals"))
            throw new IOException("Stable file " + file + " has an invalid animals section; it was left unchanged");
        return yaml;
    }

    private static Stored decode(String id, ConfigurationSection entry) {
        return new Stored(id, entry.getString("name", "?"), entry.getString("type", "HORSE"), entry.getLong("stored-at"),
                Base64.getDecoder().decode(entry.getString("data", "")));
    }

    public List<Stored> list(UUID player) throws IOException {
        Path file = file(player);
        List<Stored> out = new ArrayList<>();
        if (!Files.exists(file)) return out;
        YamlConfiguration yaml = read(file);
        ConfigurationSection animals = yaml.getConfigurationSection("animals");
        if (animals == null) return out;
        for (String id : animals.getKeys(false)) {
            ConfigurationSection entry = animals.getConfigurationSection(id);
            if (entry == null) {
                if (reported.add(player + "/" + id))
                    log.severe("Stable of " + player + ": animal '" + id + "' in " + file.getFileName()
                            + " is not a section. It is hidden but kept unchanged in the file.");
                continue;
            }
            try {
                out.add(decode(id, entry));
            } catch (IllegalArgumentException broken) {
                // keep the rest readable; the broken entry is kept unchanged in the file by save()
                if (reported.add(player + "/" + id))
                    log.severe("Stable of " + player + ": animal '" + id + "' (" + entry.getString("name", "?") + ") in " + file.getFileName()
                            + " has unreadable data (" + broken.getMessage() + "). It is hidden but kept unchanged in the file; fix its 'data' value.");
            }
        }
        out.sort((a, b) -> Long.compare(a.storedAt(), b.storedAt()));
        return out;
    }

    /** Removes the durable entry before spawning, restoring it if a veto or exception prevented spawning. */
    boolean release(UUID player, List<Stored> animals, Stored chosen,
                    java.util.function.BooleanSupplier spawn, java.util.function.BooleanSupplier inWorld) throws IOException {
        if (!animals.remove(chosen)) return false;
        save(player, animals);
        boolean spawned = false;
        try {
            spawned = spawn.getAsBoolean();
            return spawned;
        } finally {
            // A callback can throw after adding the entity. Never create a second stored copy in that case.
            if (!spawned && !inWorld.getAsBoolean()) {
                animals.add(chosen);
                save(player, animals);
            }
        }
    }

    public void save(UUID player, Collection<Stored> animals) throws IOException {
        Files.createDirectories(folder);
        Path file = file(player);
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("schema", 1);
        // entries list() could not read are carried over untouched instead of being dropped
        Set<String> ids = new HashSet<>();
        for (Stored animal : animals) ids.add(animal.id());
        int kept = 0;
        if (Files.exists(file)) {
            ConfigurationSection old = read(file).getConfigurationSection("animals");
            if (old != null) for (String id : old.getKeys(false)) {
                if (ids.contains(id)) continue;
                ConfigurationSection entry = old.getConfigurationSection(id);
                if (entry == null) {
                    yaml.set("animals." + id, old.get(id));
                    kept++;
                    continue;
                }
                try { decode(id, entry); }
                catch (IllegalArgumentException broken) {
                    for (var value : entry.getValues(true).entrySet())
                        if (!(value.getValue() instanceof ConfigurationSection)) yaml.set("animals." + id + "." + value.getKey(), value.getValue());
                    kept++;
                }
            }
        }
        if (animals.isEmpty() && kept == 0) { Files.deleteIfExists(file); return; }
        for (Stored animal : animals) {
            String base = "animals." + animal.id();
            yaml.set(base + ".name", animal.name());
            yaml.set(base + ".type", animal.type());
            yaml.set(base + ".stored-at", animal.storedAt());
            yaml.set(base + ".data", Base64.getEncoder().encodeToString(animal.data()));
        }
        files.write(file, yaml);
    }

    /** Stable entry by list number (1-based) or name (case-insensitive, first match). */
    public static Stored find(List<Stored> animals, String query) {
        try {
            int number = Integer.parseInt(query);
            return number >= 1 && number <= animals.size() ? animals.get(number - 1) : null;
        } catch (NumberFormatException byName) {
            String wanted = query.toLowerCase(Locale.ROOT);
            for (Stored animal : animals) if (animal.name().toLowerCase(Locale.ROOT).equals(wanted)) return animal;
            for (Stored animal : animals) if (animal.name().toLowerCase(Locale.ROOT).startsWith(wanted)) return animal;
            return null;
        }
    }

    /** Tab completion for summon: list numbers and names (each once) that start with what was typed. */
    public static List<String> completions(List<Stored> animals, String typed) {
        String prefix = typed.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < animals.size(); i++) if (Integer.toString(i + 1).startsWith(prefix)) out.add(Integer.toString(i + 1));
        for (Stored animal : animals) {
            String name = animal.name();
            if (name.toLowerCase(Locale.ROOT).startsWith(prefix) && !out.contains(name)) out.add(name);
        }
        return out;
    }

    /**
     * Stable size from permissions {@code vystormpaddock.slots.<n>} (largest wins), otherwise the configured default.
     */
    public static int slots(Collection<String> grantedSlotPermissions, int fallback) {
        int best = -1;
        for (String permission : grantedSlotPermissions) {
            int dot = permission.lastIndexOf('.');
            try { best = Math.max(best, Integer.parseInt(permission.substring(dot + 1))); } catch (NumberFormatException ignored) { }
        }
        return best >= 0 ? best : fallback;
    }
}
