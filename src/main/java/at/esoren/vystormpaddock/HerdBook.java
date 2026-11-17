package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Herd book: remembers where each claimed animal was last seen (claim, dismount, chunk load/unload, rename, summon)
 * and lists them in a chest menu. Left click shows the way in the action bar, right click calls a loaded animal.
 */
final class HerdBook implements Listener {
    /** Close enough to count as "found" while tracking (blocks). */
    private static final double FOUND = 6;

    private record Tracking(UUID animal, String name, String world, double x, double z, long until) {}

    private final PaddockPlugin plugin;
    private final API core;
    private final Herd herd;
    private final Map<UUID, Tracking> tracking = new HashMap<>();
    private TaskGroup tasks;
    private boolean enabled = true;
    private long trackMillis = 120_000;

    HerdBook(PaddockPlugin plugin, API core, Herd herd) {
        this.plugin = plugin;
        this.core = core;
        this.herd = herd;
    }

    void configure(boolean enabled, int trackSeconds) {
        this.enabled = enabled;
        this.trackMillis = Math.max(10, trackSeconds) * 1000L;
        if (!enabled) tracking.clear();
    }

    boolean enabled() { return enabled; }

    void start() {
        tasks = core.tasks().of(plugin, "herd");
        tasks.timer(10, 10, this::tick);
    }

    void stop() {
        if (tasks != null) tasks.close();
        tracking.clear();
        try { herd.flushAll(); } catch (IOException failure) { plugin.getLogger().log(Level.WARNING, "Could not save herd book", failure); }
    }

    // ------------------------------------------------------------------ sightings

    /** Records where an owned (non-pet) animal is now. */
    void seen(AbstractHorse horse) {
        if (!enabled || Claims.pet(horse) || !horse.isValid()) return;
        UUID owner = plugin.claims().owner(horse);
        if (owner == null) return;
        Location at = horse.getLocation();
        try {
            herd.put(owner, new Herd.Entry(horse.getUniqueId(), PaddockPlugin.name(horse), horse.getType().name(), at.getWorld().getName(),
                    at.getX(), at.getY(), at.getZ(), System.currentTimeMillis()));
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not update herd book of " + owner, failure);
        }
    }

    /** Drops an animal from its owner's book (released, stored, dead). */
    void forget(UUID owner, UUID animal) {
        // also while the book is switched off, so dead, released or stored animals do not come back when it is switched on again
        if (owner == null) return;
        try { herd.remove(owner, animal); }
        catch (IOException failure) { plugin.getLogger().log(Level.WARNING, "Could not update herd book of " + owner, failure); }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDismount(EntityDismountEvent event) {
        if (event.getDismounted() instanceof AbstractHorse horse) seen(horse);
    }

    @EventHandler
    public void onUnload(EntitiesUnloadEvent event) {
        for (Entity entity : event.getEntities()) if (entity instanceof AbstractHorse horse) seen(horse);
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        // animals claimed before the herd book existed join it the first time their chunk loads
        for (Entity entity : event.getEntities()) if (entity instanceof AbstractHorse horse) seen(horse);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        tracking.remove(event.getPlayer().getUniqueId());
        try { herd.flush(event.getPlayer().getUniqueId()); }
        catch (IOException failure) { plugin.getLogger().log(Level.WARNING, "Could not save herd book of " + event.getPlayer().getName(), failure); }
    }

    // ------------------------------------------------------------------ menu

    void open(Player player) {
        if (!player.hasPermission("vystormpaddock.use")) { plugin.lang().send(player, "denied"); return; }
        if (!enabled) { plugin.lang().send(player, "herd-disabled"); return; }
        if (!plugin.openPanel(player, "herd")) openChest(player);
    }

    List<Herd.Entry> entries(Player player) throws IOException { return herd.list(player.getUniqueId()); }

    /** Resolve the owner's current entry again; never act on a panel's stale snapshot. */
    String act(Player player, String id, String action) {
        if (!plugin.isEnabled() || !player.isOnline() || !player.hasPermission("vystormpaddock.use")) return "denied";
        if (!enabled) return "herd-disabled";
        try {
            Herd.Entry entry = entries(player).stream().filter(e -> e.id().toString().equals(id)).findFirst().orElse(null);
            if (entry == null) return "herd-empty";
            switch (action) {
                case "remove" -> remove(player, entry);
                case "track" -> track(player, entry);
                case "call" -> call(player, entry);
                default -> { return "failed"; }
            }
            return null;
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not update herd book", failure);
            return "failed";
        }
    }

    void openChest(Player player) {
        Lang lang = plugin.lang();
        if (!player.hasPermission("vystormpaddock.use")) { lang.send(player, "denied"); return; }
        if (!enabled) { lang.send(player, "herd-disabled"); return; }
        try {
            List<Herd.Entry> entries = herd.list(player.getUniqueId());
            if (entries.isEmpty()) { lang.send(player, "herd-empty"); return; }
            player.openInventory(build(lang, player, entries));
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not read herd book of " + player.getName(), failure);
            lang.send(player, "failed");
        }
    }

    private static final class Menu implements InventoryHolder {
        private Inventory inventory;
        private List<Herd.Entry> entries;
        private int stableSlot;

        @Override
        public Inventory getInventory() { return inventory; }
    }

    private Inventory build(Lang lang, Player player, List<Herd.Entry> all) {
        var platform = core.compat().platform();
        Menu holder = new Menu();
        holder.entries = all.subList(0, Math.min(all.size(), 45));
        int size = Math.min(54, (holder.entries.size() + 8) / 9 * 9 + 9);
        Inventory inventory = platform.createInventory(holder, size, lang.component(player, "herd-title", "count", Integer.toString(all.size())));
        holder.inventory = inventory;
        DateTimeFormatter date = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
        Location here = player.getLocation();
        for (int i = 0; i < holder.entries.size(); i++) {
            Herd.Entry entry = holder.entries.get(i);
            boolean sameWorld = here.getWorld().getName().equals(entry.world());
            String where = sameWorld ? lang.plain(player, "herd-away", "distance", Herd.distance(Math.hypot(entry.x() - here.getX(), entry.z() - here.getZ())))
                    : lang.plain(player, "herd-elsewhere");
            Entity live = Bukkit.getEntity(entry.id());
            String status = lang.plain(player, live != null && live.isValid() ? "herd-loaded" : "herd-unloaded");
            ItemStack item = new ItemStack(StableMenu.icon(entry.type()));
            platform.editMeta(item, meta -> {
                platform.displayName(meta, lang.component(player, "menu-name", "name", entry.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                platform.lore(meta, lang.lines(player, "herd-lore", "type", entry.type().toLowerCase(Locale.ROOT).replace('_', ' '), "world", entry.world(),
                                "place", Math.round(entry.x()) + " " + Math.round(entry.y()) + " " + Math.round(entry.z()), "where", where, "status", status,
                                "date", date.format(Instant.ofEpochMilli(entry.seen()))).stream()
                        .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            });
            inventory.setItem(i, item);
        }
        holder.stableSlot = size - 1;
        ItemStack stable = new ItemStack(Material.CHEST);
        platform.editMeta(stable, meta -> {
            platform.displayName(meta, lang.component(player, "herd-stable").decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
            platform.lore(meta, lang.lines(player, "herd-stable-lore").stream().map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
        });
        inventory.setItem(holder.stableSlot, stable);
        return inventory;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(core.compat().inventory().top(event.getView()).getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != core.compat().inventory().top(event.getView()) || !(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getSlot();
        if (slot == menu.stableSlot) {
            Bukkit.getScheduler().runTask(plugin, () -> plugin.openStable(player));
            return;
        }
        if (slot >= menu.entries.size()) return;
        Herd.Entry entry = menu.entries.get(slot);
        boolean remove = event.getClick().isShiftClick() && event.getClick().isRightClick();
        boolean call = !remove && event.getClick().isRightClick();
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            String error = act(player, entry.id().toString(), remove ? "remove" : call ? "call" : "track");
            if (error != null) plugin.lang().send(player, error);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (core.compat().inventory().top(event.getView()).getHolder() instanceof Menu) event.setCancelled(true);
    }

    private void remove(Player player, Herd.Entry entry) throws IOException {
        herd.remove(player.getUniqueId(), entry.id());
        plugin.lang().send(player, "herd-removed", "name", entry.name());
    }

    /** The live animal of an entry, or null if it is not loaded; entries of animals that changed hands are dropped. */
    private AbstractHorse live(Player player, Herd.Entry entry) {
        if (!(Bukkit.getEntity(entry.id()) instanceof AbstractHorse horse) || !horse.isValid()) return null;
        if (!player.getUniqueId().equals(plugin.claims().owner(horse))) {
            forget(player.getUniqueId(), entry.id());
            return null;
        }
        seen(horse);
        return horse;
    }

    private void call(Player player, Herd.Entry entry) {
        AbstractHorse horse = live(player, entry);
        if (horse == null) {
            plugin.lang().send(player, "herd-not-loaded", "name", entry.name());
            track(player, entry);
            return;
        }
        plugin.callAnimal(player, horse);
    }

    private void track(Player player, Herd.Entry entry) {
        AbstractHorse horse = live(player, entry);
        Location at = horse != null ? horse.getLocation() : null;
        String world = at != null ? at.getWorld().getName() : entry.world();
        if (!player.getWorld().getName().equals(world)) {
            plugin.lang().send(player, "herd-other-world", "name", entry.name(), "world", world);
            return;
        }
        tracking.put(player.getUniqueId(), new Tracking(entry.id(), entry.name(), world, at != null ? at.getX() : entry.x(), at != null ? at.getZ() : entry.z(),
                System.currentTimeMillis() + trackMillis));
        plugin.lang().send(player, "herd-tracking", "name", entry.name());
    }

    /** Action bar compass for every player who is tracking an animal. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Map.Entry<UUID, Tracking>> it = tracking.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Tracking> next = it.next();
            Player player = Bukkit.getPlayer(next.getKey());
            Tracking target = next.getValue();
            if (player == null || !player.getWorld().getName().equals(target.world())) { it.remove(); continue; }
            double x = target.x(), z = target.z();
            boolean live = false;
            if (Bukkit.getEntity(target.animal()) instanceof AbstractHorse horse && horse.isValid() && horse.getWorld().equals(player.getWorld())) {
                x = horse.getLocation().getX();
                z = horse.getLocation().getZ();
                live = true;
            }
            Location here = player.getLocation();
            double dx = x - here.getX(), dz = z - here.getZ(), blocks = Math.hypot(dx, dz);
            if (blocks <= FOUND) {
                it.remove();
                plugin.lang().actionBar(player, live ? "herd-found" : "herd-arrived", "name", target.name());
            } else if (now > target.until()) {
                it.remove();
                plugin.lang().actionBar(player, "herd-track-end");
            } else {
                plugin.lang().actionBar(player, "herd-track", "arrow", Herd.arrow(here.getYaw(), dx, dz), "name", target.name(), "distance", Herd.distance(blocks));
            }
        }
    }

    /** Stops tracking for a player (command /paddock herd stop). */
    boolean stopTracking(Player player) { return tracking.remove(player.getUniqueId()) != null; }
}
