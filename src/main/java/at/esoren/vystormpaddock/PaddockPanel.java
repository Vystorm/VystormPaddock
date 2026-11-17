package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.Result;
import Vystorm.vystorm_core.panel.Ui;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Shared, pure list/detail layout for the stable, herd book and race courses. Server thread only. */
final class PaddockPanel {
    static final int PAGE_SIZE = 8;
    record Field(String key, String value) {}
    record Row(String id, String name, Material icon, String subtitle, List<Field> fields, List<String> records) {}
    record Data(String name, String title, String subtitle, Material icon, List<Row> rows,
                int page, int pages, int count, int capacity, List<String> navigation, String empty) {}
    record State(String search, int page, StablePage.Sort sort) {
        static final State DEFAULT = new State("", 0, StablePage.Sort.OLDEST);
    }
    interface Source { Data read(Player player, State state); }
    interface Actions {
        Result act(Player player, String action, String id);
        void navigate(Player player, String destination);
    }
    interface Changes {
        void select(Player player, String id);
        void state(Player player, State state);
    }

    private final Source source;
    private final Actions actions;
    private final Map<UUID, String> selected = new HashMap<>();
    private final Map<UUID, State> states = new HashMap<>();
    private final Changes changes = new Changes() {
        public void select(Player player, String id) { selected.put(player.getUniqueId(), id); }
        public void state(Player player, State state) {
            states.put(player.getUniqueId(), state);
            selected.remove(player.getUniqueId());
        }
    };

    PaddockPanel(Source source, Actions actions) { this.source = source; this.actions = actions; }
    State state(Player player) { return states.getOrDefault(player.getUniqueId(), State.DEFAULT); }
    void state(Player player, State state) { changes.state(player, state); }
    void forget(UUID player) { selected.remove(player); states.remove(player); }

    Panel render(PanelContext ctx) {
        State state = state(ctx.player());
        return panel(ctx, source.read(ctx.player(), state), state, selected.get(ctx.player().getUniqueId()), actions, changes);
    }

    static Panel panel(PanelContext ctx, Data data, State state, String selected, Actions actions, Changes changes) {
        Row chosen = data.rows().stream().filter(r -> r.id().equals(selected)).findFirst()
                .orElse(data.rows().isEmpty() ? null : data.rows().getFirst());
        Ui.ListView list = Ui.list("entries").empty(data.empty())
                .items(data.rows(), r -> Ui.listItem(r.id(), r.name()).icon(Ui.item(r.icon())).subtitle(r.subtitle()))
                .selected(chosen == null ? null : chosen.id()).onSelect((a, id) -> {
                    if (data.rows().stream().noneMatch(r -> r.id().equals(id))) return Result.error(ctx.text("failed"));
                    changes.select(a.player(), id); a.refresh(); return Result.ok();
                }).grow(2);
        Ui.Box detail = Ui.col().gap(6).grow(3);
        if (chosen != null) {
            detail.child(Ui.heading(chosen.name()).level(2));
            Ui.Kv fields = Ui.kv().id("details");
            chosen.fields().forEach(f -> fields.row(ctx.text(f.key()), f.value()));
            if (data.name().equals("races")) {
                Ui.Box records = Ui.col().id("records");
                if (chosen.records().isEmpty()) records.child(Ui.text(ctx.text("race-top-empty")));
                else chosen.records().forEach(line -> records.child(Ui.text(line)));
                detail.child(Ui.tabs("course-tabs").tab("details", ctx.text("panel.details"), fields)
                        .tab("records", ctx.text("panel.records"), records));
            } else detail.child(fields);
            switch (data.name()) {
                case "stable" -> detail.child(action(ctx, actions, "summon", chosen).primary());
                case "herd" -> detail.children(action(ctx, actions, "track", chosen).primary(),
                        action(ctx, actions, "call", chosen),
                        action(ctx, actions, "remove", chosen).danger().confirm(ctx.text("panel.remove"),
                                ctx.text("panel.remove-confirm", "name", chosen.name())));
                case "races" -> detail.child(action(ctx, actions, "race-info", chosen).primary());
                default -> throw new IllegalArgumentException(data.name());
            }
        } else detail.child(Ui.text(data.empty()).id("empty"));

        Panel panel = Panel.page(data.title()).subtitle(data.subtitle()).icon(Ui.item(data.icon())).size(Panel.Size.LARGE);
        if (data.name().equals("stable")) {
            panel.child(Ui.input("search", ctx.text("panel.search")).value(state.search()).maxLength(100)
                    .search((a, query) -> {
                        changes.state(a.player(), new State(query, 0, state.sort())); a.refresh(); return Result.ok();
                    }));
            Ui.Select sort = Ui.select("sort", ctx.text("panel.sort")).value(state.sort().name());
            for (StablePage.Sort value : StablePage.Sort.values())
                sort.option(value.name(), ctx.text("panel.sort-" + value.name().toLowerCase(java.util.Locale.ROOT)));
            panel.child(sort.onChange((a, value) -> {
                try { changes.state(a.player(), new State(state.search(), 0, StablePage.Sort.valueOf(value))); }
                catch (IllegalArgumentException invalid) { return Result.error(ctx.text("failed")); }
                a.refresh(); return Result.ok();
            }));
            panel.child(Ui.progress(Math.min(data.count(), Math.max(1, data.capacity())), Math.max(1, data.capacity()))
                    .id("capacity").label(ctx.text("menu-title", "count", data.count(), "max", data.capacity())));
        }
        panel.child(Ui.row().gap(8).children(list, detail));
        panel.child(Ui.badge(ctx.text("panel.page", "page", data.page() + 1, "pages", data.pages())).id("page"));
        panel.footer(page(ctx, "previous", data.page() - 1, data.page() == 0, state, changes),
                page(ctx, "next", data.page() + 1, data.page() + 1 >= data.pages(), state, changes));
        for (String destination : data.navigation()) {
            panel.footer(Ui.button("open-" + destination).label(ctx.text(titleKey(destination)))
                    .icon(Ui.item(icon(destination))).ghost().onClick(a -> {
                        a.closeAndThen(p -> actions.navigate(p, destination)); return Result.ok();
                    }));
        }
        return panel;
    }

    private static Ui.Button action(PanelContext ctx, Actions actions, String action, Row row) {
        return Ui.button(action).label(ctx.text("panel." + action)).icon(Ui.item(switch (action) {
            case "summon" -> Material.SADDLE;
            case "track" -> Material.COMPASS;
            case "call" -> Material.LEAD;
            case "remove" -> Material.BARRIER;
            default -> Material.GOLDEN_HORSE_ARMOR;
        })).onClick(a -> {
            if (action.equals("remove")) {
                Result result = actions.act(a.player(), action, row.id());
                if (result.success()) a.refresh();
                return result;
            }
            a.closeAndThen(p -> actions.act(p, action, row.id()));
            return Result.ok();
        });
    }

    private static Ui.Button page(PanelContext ctx, String direction, int page, boolean disabled, State state, Changes changes) {
        return Ui.button(direction).label(ctx.text("menu-" + direction)).icon(Ui.item(Material.ARROW)).ghost()
                .disabled(disabled).onClick(a -> {
                    if (disabled) return Result.error(ctx.text("failed"));
                    changes.state(a.player(), new State(state.search(), Math.max(0, page), state.sort()));
                    a.refresh(); return Result.ok();
                });
    }

    static String titleKey(String name) {
        return switch (name) { case "stable" -> "herd-stable"; case "herd" -> "vmenu-herd"; default -> "vmenu-races"; };
    }
    static Material icon(String name) {
        return switch (name) { case "stable" -> Material.CHEST; case "herd" -> Material.LEAD; default -> Material.GOLDEN_HORSE_ARMOR; };
    }
}
