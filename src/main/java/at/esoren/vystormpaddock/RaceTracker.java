package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.schedule.TaskGroup;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Time trials on horseback. Riding out of a course's start circle starts the clock, the checkpoints follow in order,
 * the last point is the finish. Getting off, teleporting or leaving the world cancels the run.
 */
final class RaceTracker implements Listener {
    private static final class Run {
        final Races.Course course;
        final long started;
        int next = 1;

        Run(Races.Course course, long started) {
            this.course = course;
            this.started = started;
        }
    }

    private final PaddockPlugin plugin;
    private final API core;
    private final Races races;
    private final Map<UUID, Run> runs = new HashMap<>();
    private final Map<UUID, Races.Course> ready = new HashMap<>();
    private TaskGroup tasks;
    private long ticks;
    private boolean enabled = true, announceRecords = true, showCheckpoints = true;
    private long maxMillis = 600_000;
    private double defaultRadius = 4;

    RaceTracker(PaddockPlugin plugin, API core, Races races) {
        this.plugin = plugin;
        this.core = core;
        this.races = races;
    }

    void configure(boolean enabled, int maxMinutes, double defaultRadius, boolean announceRecords, boolean showCheckpoints) {
        this.enabled = enabled;
        this.maxMillis = Math.max(1, maxMinutes) * 60_000L;
        this.defaultRadius = Math.clamp(defaultRadius, 1, 16);
        this.announceRecords = announceRecords;
        this.showCheckpoints = showCheckpoints;
        try { races.load(); }
        catch (IOException failure) { plugin.getLogger().log(Level.SEVERE, "Could not read races.yml; races are off until it is fixed", failure); }
        // runs keep pointing at the old course objects; a reload ends them
        plugin.betsRefundAll();
        runs.clear();
        ready.clear();
    }

    boolean enabled() { return enabled; }

    /** The courses and times, for placeholders. */
    Races races() { return races; }

    void start() {
        tasks = core.tasks().of(plugin, "races");
        tasks.timer(1, 1, this::tick);
    }

    void stop() {
        if (tasks != null) tasks.close();
        runs.clear();
        ready.clear();
    }

    private Lang lang() { return plugin.lang(); }

    // ------------------------------------------------------------------ the clock

    private void tick() {
        ticks++;
        if (ticks % 5 == 0 && plugin.training() != null) plugin.training().tick(System.currentTimeMillis());
        if (!enabled || races.courses().isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            Run run = runs.get(id);
            boolean riding = player.getVehicle() instanceof AbstractHorse;
            Location at = player.getLocation();
            if (run != null) {
                if (!riding || !at.getWorld().getName().equals(run.course.world())) { cancel(player, "race-cancelled"); continue; }
                if (now - run.started > maxMillis) { cancel(player, "race-timeout"); continue; }
                int next = Races.advance(run.course, run.next, at.getX(), at.getY(), at.getZ());
                if (next != run.next) {
                    run.next = next;
                    if (next >= run.course.points().size()) { finish(player, run, now); continue; }
                    player.playSound(at, Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 1.6f);
                }
                if (ticks % 4 == 0) lang().actionBar(player, "race-progress", "time", Races.time(now - run.started),
                        "checkpoint", Integer.toString(run.next), "total", Integer.toString(run.course.points().size() - 1));
                if (showCheckpoints && ticks % 10 == 0) {
                    double[] point = run.course.points().get(run.next);
                    player.spawnParticle(Particle.END_ROD, point[0], point[1] + 1.5, point[2], 8, 0.3, 1.0, 0.3, 0.01);
                }
                continue;
            }
            if (!riding || !player.hasPermission("vystormpaddock.race")) { if (ready.remove(id) != null) plugin.betsCancelled(id); continue; }
            Races.Course start = Races.startAt(races.courses(), at.getWorld().getName(), at.getX(), at.getY(), at.getZ());
            Races.Course was = ready.get(id);
            if (start != null) {
                if (start != was || ticks % 20 == 0) lang().actionBar(player, "race-ready", "course", start.name());
                ready.put(id, start);
                if (start != was) plugin.betsOpened(player, start);
            } else if (was != null) {
                ready.remove(id);
                runs.put(id, new Run(was, now));
                plugin.betsLocked(player, was);
                player.playSound(at, Sound.ENTITY_HORSE_ANGRY, 1f, 1f);
                lang().send(player, "race-go", "course", was.name(), "total", Integer.toString(was.points().size() - 1));
            }
        }
    }

    private void cancel(Player player, String key) {
        Run run = runs.remove(player.getUniqueId());
        if (run != null) { lang().send(player, key, "course", run.course.name()); plugin.betsSettled(player.getUniqueId(), -1); }
    }

    private void finish(Player player, Run run, long now) {
        runs.remove(player.getUniqueId());
        long millis = now - run.started;
        plugin.betsSettled(player.getUniqueId(), millis);
        Races.Course course = run.course;
        List<Races.Time> before = Races.top(course, 1);
        boolean best = Races.record(course, player.getUniqueId(), player.getName(), millis, now);
        if (best) save();
        int rank = Races.rank(course, player.getUniqueId());
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.2f);
        lang().send(player, best ? "race-finish-best" : "race-finish", "course", course.name(), "time", Races.time(millis), "rank", Integer.toString(rank),
                "best", Races.time(course.times().get(player.getUniqueId()).millis()));
        boolean newRecord = best && rank == 1 && (before.isEmpty() || !before.getFirst().player().equals(player.getUniqueId()));
        if (newRecord && announceRecords && !before.isEmpty())
            for (Player other : Bukkit.getOnlinePlayers()) lang().send(other, "race-record", "player", player.getName(), "course", course.name(), "time", Races.time(millis));
    }

    private boolean save() {
        try {
            races.save();
            return true;
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not save races.yml", failure);
            return false;
        }
    }

    /** Stage OBSERVE of Vystorm Core, registered in PaddockPlugin.handleSharedEvents; not called for a cancelled teleport. */
    public void onTeleport(PlayerTeleportEvent event) {
        if (!runs.containsKey(event.getPlayer().getUniqueId())) return;
        Location from = event.getFrom(), to = event.getTo();
        if (from.getWorld() != to.getWorld() || from.distanceSquared(to) > 64) cancel(event.getPlayer(), "race-cancelled");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (runs.remove(event.getPlayer().getUniqueId()) != null) plugin.betsSettled(event.getPlayer().getUniqueId(), -1);
        if (ready.remove(event.getPlayer().getUniqueId()) != null) plugin.betsCancelled(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ player commands and menu

    /** /paddock race [NAME|stop] */
    void race(Player player, String[] args) {
        if (!enabled) { lang().send(player, "feature-off"); return; }
        if (!player.hasPermission("vystormpaddock.race")) { lang().send(player, "denied"); return; }
        if (args.length < 2) { open(player); return; }
        if (args[1].equalsIgnoreCase("stop")) {
            if (runs.containsKey(player.getUniqueId())) cancel(player, "race-cancelled");
            else lang().send(player, "race-not-running");
            return;
        }
        showCourse(player, args[1]);
    }

    /** Resolve a fresh course directly, including courses whose names also happen to be command keywords. */
    void showCourse(Player player, String name) {
        if (!plugin.isEnabled() || !player.isOnline() || !player.hasPermission("vystormpaddock.race")) { lang().send(player, "denied"); return; }
        if (!enabled) { lang().send(player, "feature-off"); return; }
        Races.Course course = races.get(name);
        if (course == null || !course.ready()) { lang().send(player, "race-unknown", "course", name); return; }
        info(player, course);
    }

    private void info(Player player, Races.Course course) {
        double[] start = course.points().getFirst();
        lang().send(player, "race-info", "course", course.name(), "world", course.world(), "place", place(start),
                "checkpoints", Integer.toString(course.points().size() - 2));
        List<Races.Time> top = Races.top(course, 10);
        if (top.isEmpty()) lang().send(player, "race-top-empty");
        for (int i = 0; i < top.size(); i++)
            lang().send(player, "race-top-entry", "rank", Integer.toString(i + 1), "player", top.get(i).name(), "time", Races.time(top.get(i).millis()));
        int rank = Races.rank(course, player.getUniqueId());
        if (rank > 10) lang().send(player, "race-your-rank", "rank", Integer.toString(rank), "time", Races.time(course.times().get(player.getUniqueId()).millis()));
    }

    private static String place(double[] point) { return Math.round(point[0]) + " " + Math.round(point[1]) + " " + Math.round(point[2]); }

    List<Races.Course> readyCourses() {
        List<Races.Course> out = new ArrayList<>();
        for (Races.Course course : races.courses()) if (course.ready()) out.add(course);
        return out;
    }

    private static final class Menu implements InventoryHolder {
        private Inventory inventory;
        private List<Races.Course> courses;

        @Override
        public Inventory getInventory() { return inventory; }
    }

    void open(Player player) {
        if (!player.hasPermission("vystormpaddock.race")) { lang().send(player, "denied"); return; }
        if (!enabled) { lang().send(player, "feature-off"); return; }
        if (!plugin.openPanel(player, "races")) openChest(player);
    }

    void openChest(Player player) {
        if (!player.hasPermission("vystormpaddock.race")) { lang().send(player, "denied"); return; }
        if (!enabled) { lang().send(player, "feature-off"); return; }
        List<Races.Course> courses = readyCourses();
        if (courses.isEmpty()) { lang().send(player, "race-none"); return; }
        Menu holder = new Menu();
        holder.courses = courses.subList(0, Math.min(54, courses.size()));
        int size = Math.clamp((holder.courses.size() + 8) / 9 * 9, 9, 54);
        Inventory inventory = core.compat().platform().createInventory(holder, size, lang().component(player, "race-title"));
        holder.inventory = inventory;
        for (int i = 0; i < holder.courses.size(); i++) {
            Races.Course course = holder.courses.get(i);
            List<Races.Time> top = Races.top(course, 1);
            Races.Time mine = course.times().get(player.getUniqueId());
            String record = top.isEmpty() ? "-" : Races.time(top.getFirst().millis()) + " (" + top.getFirst().name() + ")";
            String best = mine == null ? "-" : Races.time(mine.millis()) + " (#" + Races.rank(course, player.getUniqueId()) + ")";
            ItemStack item = new ItemStack(Material.GOLDEN_HORSE_ARMOR);
            core.compat().platform().editMeta(item, meta -> {
                core.compat().platform().displayName(meta, lang().component(player, "menu-name", "name", course.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                core.compat().platform().lore(meta, lang().lines(player, "race-lore", "world", course.world(), "place", place(course.points().getFirst()),
                                "checkpoints", Integer.toString(course.points().size() - 2), "record", record, "best", best).stream()
                        .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            });
            inventory.setItem(i, item);
        }
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(core.compat().inventory().top(event.getView()).getHolder() instanceof Menu menu)) return;
        event.setCancelled(true);
        if (event.getClickedInventory() != core.compat().inventory().top(event.getView()) || !(event.getWhoClicked() instanceof Player player)) return;
        if (event.getSlot() >= menu.courses.size()) return;
        Races.Course course = menu.courses.get(event.getSlot());
        Bukkit.getScheduler().runTask(plugin, () -> {
            player.closeInventory();
            info(player, course);
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (core.compat().inventory().top(event.getView()).getHolder() instanceof Menu) event.setCancelled(true);
    }

    // ------------------------------------------------------------------ course editing

    /** /paddock course create|point|undo|radius|reset|delete NAME */
    void course(Player player, String[] args) {
        if (!enabled || !PaddockPlugin.enabled(plugin, Feature.COURSES)) { lang().send(player, "feature-off"); return; }
        if (!player.hasPermission("vystormpaddock.course")) { lang().send(player, "denied"); return; }
        if (args.length < 3) { lang().send(player, "usage-course"); return; }
        String action = args[1].toLowerCase(Locale.ROOT), name = args[2];
        if (races.broken()) { lang().send(player, "failed"); return; }
        Location at = player.getLocation();
        if (action.equals("create")) {
            if (!Races.NAME.matcher(name).matches()) { lang().send(player, "course-bad-name"); return; }
            if (races.get(name) != null) { lang().send(player, "course-exists", "course", name); return; }
            Races.Course course = races.create(name, at.getWorld().getName(), defaultRadius);
            course.points().add(new double[]{at.getX(), at.getY(), at.getZ()});
            if (save()) lang().send(player, "course-created", "course", name, "radius", fmt(defaultRadius));
            else lang().send(player, "failed");
            return;
        }
        Races.Course course = races.get(name);
        if (course == null) { lang().send(player, "race-unknown", "course", name); return; }
        endRuns(course);
        switch (action) {
            case "point" -> {
                if (!course.world().equals(at.getWorld().getName())) { lang().send(player, "course-other-world", "world", course.world()); return; }
                course.points().add(new double[]{at.getX(), at.getY(), at.getZ()});
                if (!course.times().isEmpty()) course.times().clear(); // old times were set on a different track
                lang().send(player, "course-point", "course", course.name(), "number", Integer.toString(course.points().size() - 1));
            }
            case "undo" -> {
                if (course.points().size() <= 1) { lang().send(player, "course-no-point"); return; }
                course.points().removeLast();
                course.times().clear();
                lang().send(player, "course-undone", "course", course.name(), "count", Integer.toString(course.points().size() - 1));
            }
            case "radius" -> {
                double radius;
                try { radius = Math.clamp(Double.parseDouble(args.length > 3 ? args[3] : ""), 1, 16); }
                catch (NumberFormatException bad) { lang().send(player, "usage-course"); return; }
                course.radius(radius);
                lang().send(player, "course-radius", "course", course.name(), "radius", fmt(radius));
            }
            case "reset" -> {
                course.times().clear();
                lang().send(player, "course-reset", "course", course.name());
            }
            case "delete" -> {
                races.delete(name);
                lang().send(player, "course-deleted", "course", course.name());
            }
            default -> { lang().send(player, "usage-course"); return; }
        }
        if (!save()) lang().send(player, "failed");
    }

    private void endRuns(Races.Course course) {
        runs.entrySet().removeIf(entry -> entry.getValue().course == course);
        ready.values().removeIf(value -> value == course);
    }

    private static String fmt(double value) { return String.format(Locale.ROOT, "%.1f", value); }

    List<String> courseNames(String prefix, boolean onlyReady) {
        List<String> out = new ArrayList<>();
        for (Races.Course course : races.courses())
            if ((!onlyReady || course.ready()) && course.name().toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(course.name());
        return out;
    }
}
