package at.esoren.vystormpaddock;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Ownership lives on the animal itself (persistent data): owner UUID, owner name and trusted UUIDs. It therefore
 * survives restarts, chunk unloads and the trip into and out of the stable without any database.
 */
public final class Claims {
    private final NamespacedKey owner, ownerName, trusted, sire, dam, generation;
    /** Vystorm Pets marks its animals with these keys; such animals belong to Pets, Paddock only protects them. */
    static final NamespacedKey PET_ID = NamespacedKey.fromString("vystormpets:pet_id"), PET_OWNER = NamespacedKey.fromString("vystormpets:pet_owner");

    public Claims(Plugin plugin) {
        owner = new NamespacedKey(plugin, "owner");
        ownerName = new NamespacedKey(plugin, "owner_name");
        trusted = new NamespacedKey(plugin, "trusted");
        sire = new NamespacedKey(plugin, "sire");
        dam = new NamespacedKey(plugin, "dam");
        generation = new NamespacedKey(plugin, "generation");
    }

    /** True for animals managed by Vystorm Pets. */
    public static boolean pet(Entity entity) { return entity.getPersistentDataContainer().has(PET_ID, PersistentDataType.STRING); }

    /** Owner: Paddock claim, else the Vystorm Pets owner, else null. */
    public UUID owner(Entity entity) {
        String raw = entity.getPersistentDataContainer().get(owner, PersistentDataType.STRING);
        if (raw == null) raw = entity.getPersistentDataContainer().get(PET_OWNER, PersistentDataType.STRING);
        try { return raw == null ? null : UUID.fromString(raw); } catch (IllegalArgumentException broken) { return null; }
    }

    public String ownerName(Entity entity) {
        String name = entity.getPersistentDataContainer().get(ownerName, PersistentDataType.STRING);
        if (name == null) {
            UUID id = owner(entity);
            name = id == null ? null : org.bukkit.Bukkit.getOfflinePlayer(id).getName();
        }
        return name == null ? "?" : name;
    }

    public boolean claimed(Entity entity) { return owner(entity) != null; }

    public void claim(Entity entity, Player player) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(owner, PersistentDataType.STRING, player.getUniqueId().toString());
        data.set(ownerName, PersistentDataType.STRING, player.getName());
        data.remove(trusted);
    }

    public void release(Entity entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.remove(owner);
        data.remove(ownerName);
        data.remove(trusted);
    }

    public Set<UUID> trusted(Entity entity) { return parse(entity.getPersistentDataContainer().get(trusted, PersistentDataType.STRING)); }

    public void trusted(Entity entity, Set<UUID> players) {
        if (players.isEmpty()) entity.getPersistentDataContainer().remove(trusted);
        else entity.getPersistentDataContainer().set(trusted, PersistentDataType.STRING, format(players));
    }

    /** Unclaimed animals are open to everyone; claimed ones to the owner, trusted players and bypass admins. */
    public boolean allowed(Entity entity, Player player) {
        UUID id = owner(entity);
        if (id == null || id.equals(player.getUniqueId())) return true;
        if (player.hasPermission("vystormpaddock.bypass")) return true;
        return trusted(entity).contains(player.getUniqueId());
    }

    static Set<UUID> parse(String raw) {
        Set<UUID> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            try { out.add(UUID.fromString(part.trim())); } catch (IllegalArgumentException ignored) { }
        }
        return out;
    }

    static String format(Set<UUID> players) {
        StringBuilder out = new StringBuilder();
        for (UUID id : players) {
            if (!out.isEmpty()) out.append(',');
            out.append(id);
        }
        return out.toString();
    }

    /** Remembers both parents (names) and the generation of a foal. */
    public void pedigree(Entity child, String sireName, String damName, int parentGeneration) {
        PersistentDataContainer data = child.getPersistentDataContainer();
        data.set(sire, PersistentDataType.STRING, sireName);
        data.set(dam, PersistentDataType.STRING, damName);
        data.set(generation, PersistentDataType.INTEGER, parentGeneration + 1);
    }

    public String[] parents(Entity entity) {
        PersistentDataContainer data = entity.getPersistentDataContainer();
        String s = data.get(sire, PersistentDataType.STRING), d = data.get(dam, PersistentDataType.STRING);
        return s == null || d == null ? null : new String[]{s, d};
    }

    public int generation(Entity entity) { return entity.getPersistentDataContainer().getOrDefault(generation, PersistentDataType.INTEGER, 0); }
}
