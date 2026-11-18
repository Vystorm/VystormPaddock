package at.esoren.vystormpaddock;

import Vystorm.vystorm_core._uiMethods.menu.MenuCategory;
import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.Panels;
import Vystorm.vystorm_core.panel.PanelsInterface;
import Vystorm.vystorm_core.panel.Result;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Loaded only behind the Core capability guard; owns registration, snapshots and service adapters. */
final class PaddockPanels implements PaddockPanel.Actions {
    private final PaddockPlugin plugin;
    private final Stable stable;
    private final HerdBook herd;
    private final RaceTracker races;
    private final Panels panels;
    private final Map<String, PaddockPanel> views = new LinkedHashMap<>();
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());

    /** @param panelsPart Core's panel part, {@code api.gui().panels()} */
    PaddockPanels(PaddockPlugin plugin, PanelsInterface panelsPart, Stable stable, HerdBook herd, RaceTracker races) {
        this.plugin = plugin; this.stable = stable; this.herd = herd; this.races = races;
        panels = panelsPart.of(plugin);
        try {
            for (String name : List.of("stable", "herd", "races")) {
                PaddockPanel view = new PaddockPanel((player, state) -> data(player, name, state), this);
                views.put(name, view);
                PanelSpec.Builder spec = PanelSpec.builder(plugin.lang().ref(PaddockPanel.titleKey(name)))
                        .access(p -> allowed(p, name)).render(view::render).fallback(p -> fallback(p, name));
                if (!name.equals("stable")) spec.menu(name.equals("herd") ? MenuCategory.CHARACTER : MenuCategory.COMMUNITY,
                        PaddockPanel.icon(name), plugin.lang().ref("vmenu-" + name + "-desc"));
                panels.register(name, spec.build());
            }
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    boolean allowed(Player player, String name) {
        return plugin.isEnabled() && player.isOnline() && PaddockPlugin.enabled(plugin, Feature.CLAIMS)
                && (!name.equals("stable") || PaddockPlugin.enabled(plugin, Feature.STABLE) && PaddockPlugin.enabled(plugin, Feature.STABLE_MENU))
                && player.hasPermission(name.equals("races") ? "vystormpaddock.race" : "vystormpaddock.use")
                && (!name.equals("herd") || herd.enabled()) && (!name.equals("races") || races.enabled());
    }

    boolean open(Player player, String name) {
        try { return panels.open(player, name) != PanelOpenResult.NONE; }
        catch (RuntimeException failure) {
            plugin.getLogger().fine("Could not open " + name + " panel: " + failure);
            return false;
        }
    }

    boolean openStable(Player player, String search, int page, StablePage.Sort sort) {
        views.get("stable").state(player, new PaddockPanel.State(search == null ? "" : search, page, sort));
        return open(player, "stable");
    }

    private void fallback(Player player, String name) {
        if (name.equals("stable")) {
            PaddockPanel.State state = views.get(name).state(player);
            plugin.openStableChest(player, state.search(), state.page(), state.sort());
        } else if (name.equals("herd")) herd.openChest(player);
        else races.openChest(player);
    }

    void forget(UUID player) { views.values().forEach(view -> view.forget(player)); }

    void close() {
        for (String name : views.keySet()) {
            try { panels.unregister(name); }
            catch (RuntimeException failure) { plugin.getLogger().fine("Could not unregister panel: " + failure); }
        }
        views.clear();
    }

    private String text(Player player, String key, Object... args) { return plugin.lang().plain(player, key, args); }
    private static PaddockPanel.Field field(String key, String value) { return new PaddockPanel.Field("panel." + key, value); }

    private PaddockPanel.Data data(Player player, String name, PaddockPanel.State state) {
        List<PaddockPanel.Row> rows = new ArrayList<>();
        int count = 0, capacity = 0, page = 0, pages = 1;
        String empty = text(player, name.equals("stable") ? "menu-no-matches" : name.equals("herd") ? "herd-empty" : "race-none");
        try {
            if (name.equals("stable")) {
                List<Stable.Stored> animals = stable.list(player.getUniqueId());
                StablePage found = StablePage.of(animals, state.search(), state.page(), state.sort(), PaddockPanel.PAGE_SIZE);
                count = animals.size(); capacity = plugin.slots(player); page = found.number(); pages = found.pages();
                for (Stable.Stored animal : found.animals()) rows.add(new PaddockPanel.Row(animal.id(), animal.name(), StableMenu.icon(animal.type()),
                        type(player, animal.type()), List.of(field("type", type(player, animal.type())),
                        field("stored", DATE.format(Instant.ofEpochMilli(animal.storedAt())))), List.of()));
            } else if (name.equals("herd")) {
                var here = player.getLocation();
                for (Herd.Entry entry : herd.entries(player)) {
                    var live = Bukkit.getEntity(entry.id());
                    String where = here.getWorld().getName().equals(entry.world())
                            ? text(player, "herd-away", "distance", Herd.distance(Math.hypot(entry.x() - here.getX(), entry.z() - here.getZ())))
                            : text(player, "herd-elsewhere");
                    rows.add(new PaddockPanel.Row(entry.id().toString(), entry.name(), StableMenu.icon(entry.type()), where,
                            List.of(field("type", type(player, entry.type())), field("world", entry.world()),
                                    field("place", Math.round(entry.x()) + " " + Math.round(entry.y()) + " " + Math.round(entry.z())),
                                    field("seen", DATE.format(Instant.ofEpochMilli(entry.seen()))),
                                    field("status", text(player, live != null && live.isValid() ? "herd-loaded" : "herd-unloaded"))), List.of()));
                }
            } else {
                for (Races.Course course : races.readyCourses()) {
                    List<Races.Time> top = Races.top(course, 10);
                    Races.Time mine = course.times().get(player.getUniqueId());
                    String none = text(player, "panel.none");
                    String record = top.isEmpty() ? none : Races.time(top.getFirst().millis()) + " (" + top.getFirst().name() + ")";
                    String best = mine == null ? none : Races.time(mine.millis()) + " (#" + Races.rank(course, player.getUniqueId()) + ")";
                    List<String> records = new ArrayList<>();
                    for (int i = 0; i < top.size(); i++) records.add(text(player, "race-top-entry", "rank", i + 1,
                            "player", top.get(i).name(), "time", Races.time(top.get(i).millis())));
                    double[] start = course.points().getFirst();
                    rows.add(new PaddockPanel.Row(course.name(), course.name(), PaddockPanel.icon(name), course.world(),
                            List.of(field("world", course.world()), field("place", Math.round(start[0]) + " " + Math.round(start[1]) + " " + Math.round(start[2])),
                                    field("checkpoints", Integer.toString(course.points().size() - 2)), field("record", record), field("best", best)), records));
                }
            }
            if (!name.equals("stable")) {
                count = rows.size();
                pages = Math.max(1, (count + PaddockPanel.PAGE_SIZE - 1) / PaddockPanel.PAGE_SIZE);
                page = Math.clamp(state.page(), 0, pages - 1);
                rows = new ArrayList<>(rows.subList(page * PaddockPanel.PAGE_SIZE, Math.min(count, (page + 1) * PaddockPanel.PAGE_SIZE)));
            }
        } catch (IOException failure) {
            plugin.getLogger().log(Level.WARNING, "Could not read " + name + " panel", failure);
            rows.clear(); empty = text(player, "failed");
        }
        List<String> navigation = List.of("stable", "herd", "races").stream().filter(n -> !n.equals(name) && allowed(player, n)).toList();
        return new PaddockPanel.Data(name, text(player, PaddockPanel.titleKey(name)), text(player, "panel.entries", "count", count),
                PaddockPanel.icon(name), List.copyOf(rows), page, pages, count, capacity, navigation, empty);
    }

    private String type(Player player, String type) {
        String key = "panel.types." + type.toLowerCase(java.util.Locale.ROOT);
        return text(player, plugin.lang().plain("en", key).equals(key) ? "panel.types.other" : key);
    }

    @Override public Result act(Player player, String action, String id) {
        String name = action.equals("summon") ? "stable" : action.equals("race-info") ? "races" : "herd";
        if (!allowed(player, name)) {
            plugin.lang().send(player, "denied");
            return Result.error(text(player, "denied"));
        }
        if (action.equals("summon")) plugin.summonId(player, id);
        else if (action.equals("race-info")) races.showCourse(player, id);
        else {
            String error = herd.act(player, id, action);
            if (error != null) {
                plugin.lang().send(player, error);
                return Result.error(text(player, error));
            }
        }
        return Result.ok();
    }

    @Override public void navigate(Player player, String destination) {
        if (!allowed(player, destination)) { plugin.lang().send(player, "denied"); return; }
        switch (destination) {
            case "stable" -> plugin.openStable(player);
            case "herd" -> herd.open(player);
            case "races" -> races.open(player);
            default -> { }
        }
    }
}
