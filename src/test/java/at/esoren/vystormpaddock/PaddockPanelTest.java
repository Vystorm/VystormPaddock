package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.Panel;
import Vystorm.vystorm_core.panel.PanelAction;
import Vystorm.vystorm_core.panel.PanelContext;
import Vystorm.vystorm_core.panel.PanelSession;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Ui;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PaddockPanelTest {
    private final Player player = mock(Player.class);
    private final PaddockPanel.Actions actions = mock(PaddockPanel.Actions.class);
    private final PaddockPanel.Changes changes = mock(PaddockPanel.Changes.class);

    static Lang lang() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            try (var input = PaddockPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(input.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return Lang.create("vystormpaddock", files);
    }

    private PanelContext context(String code) throws Exception {
        Lang lang = lang();
        return new PanelContext() {
            public Player player() { return player; }
            public String arg() { return ""; }
            public String text(String key, Object... args) { return lang.plain(code, key, args); }
            public Component component(String key, Object... args) { return Component.text(text(key, args)); }
            public PanelSession session() { return null; }
        };
    }

    private static PaddockPanel.Row row(String id, String name) {
        return new PaddockPanel.Row(id, name, Material.HORSE_SPAWN_EGG, "world",
                List.of(new PaddockPanel.Field("panel.world", "world")), List.of("1. Alex 00:12.500"));
    }

    private static PaddockPanel.Data data(String menu, List<PaddockPanel.Row> rows, int page, int pages) {
        return new PaddockPanel.Data(menu, "Menu", "2 entries", PaddockPanel.icon(menu), rows,
                page, pages, 2, 3, List.of("stable", "herd", "races").stream().filter(n -> !n.equals(menu)).toList(), "Empty");
    }

    private Panel panel(String menu, String chosen) throws Exception {
        return PaddockPanel.panel(context("en"), data(menu, List.of(row("a", "Amber"), row("b", "Blaze")), 0, 2),
                PaddockPanel.State.DEFAULT, chosen, actions, changes);
    }

    @Test void givenStable_whenRendered_thenSearchSortCapacityAndSummonArePresent() throws Exception {
        JsonObject document = PanelTesting.render(panel("stable", "b"));
        assertTrue(PanelTesting.ids(document).containsAll(List.of("entries", "search", "sort", "capacity", "summon", "next", "previous")));
        assertEquals("b", PanelTesting.find(document, "entries").get("selected").getAsString());
        assertTrue(document.toString().contains("Stable (2/3)"));
        assertTrue(document.toString().contains("Summon"));
        assertTrue(PanelTesting.find(document, "previous").get("disabled").getAsBoolean());
        assertFalse(PanelTesting.find(document, "next").get("disabled").getAsBoolean());
    }

    @Test void givenHerd_whenRendered_thenTrackCallAndConfirmedRemovalArePresent() throws Exception {
        JsonObject document = PanelTesting.render(panel("herd", "b"));
        assertTrue(PanelTesting.ids(document).containsAll(List.of("track", "call", "remove", "open-stable")));
        String confirmation = PanelTesting.find(document, "remove").get("confirm").toString();
        assertTrue(confirmation.contains("Blaze"), confirmation);
        assertFalse(confirmation.contains("Amber"), confirmation);
        assertNull(PanelTesting.find(document, "summon"));
    }

    @Test void givenRaceCourses_whenRendered_thenDetailsAndTopTenHaveTabs() throws Exception {
        JsonObject document = PanelTesting.render(panel("races", "a"));
        assertTrue(PanelTesting.ids(document).containsAll(List.of("course-tabs", "records", "race-info", "details")));
        assertTrue(document.toString().contains("Alex 00:12.500"));
        assertTrue(document.toString().contains("Top 10"));
    }

    @Test void givenEmptyMenus_whenRendered_thenNoAnimalOrCourseActionsRemain() throws Exception {
        for (String menu : List.of("stable", "herd", "races")) {
            JsonObject document = PanelTesting.render(PaddockPanel.panel(context("en"), data(menu, List.of(), 0, 1),
                    PaddockPanel.State.DEFAULT, "gone", actions, changes));
            assertNotNull(PanelTesting.find(document, "empty"));
            for (String action : List.of("summon", "track", "call", "remove", "race-info")) assertNull(PanelTesting.find(document, action));
            assertTrue(PanelTesting.find(document, "next").get("disabled").getAsBoolean());
        }
    }

    @Test void givenMissingSelection_whenRendered_thenFirstEntryIsSelected() throws Exception {
        assertEquals("a", PanelTesting.find(PanelTesting.render(panel("stable", "gone")), "entries").get("selected").getAsString());
    }

    @Test void givenEachLanguage_whenRendered_thenNoPanelTranslationKeysLeak() throws Exception {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            for (String menu : List.of("stable", "herd", "races")) {
                JsonObject document = PanelTesting.render(PaddockPanel.panel(context(code), data(menu, List.of(row("a", "Amber")), 0, 1),
                        PaddockPanel.State.DEFAULT, null, actions, changes));
                assertFalse(document.toString().contains("panel."), code + ": " + document);
            }
        }
    }

    @Test void givenSearchAndSortState_whenPlayerQuits_thenStateIsForgottenAndOtherPlayersAreUnaffected() {
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Player other = mock(Player.class);
        when(other.getUniqueId()).thenReturn(UUID.randomUUID());
        PaddockPanel view = new PaddockPanel((p, s) -> null, actions);
        PaddockPanel.State state = new PaddockPanel.State("Amber", 2, StablePage.Sort.NAME);
        view.state(player, state);
        view.state(other, state);
        view.forget(player.getUniqueId());
        assertEquals(PaddockPanel.State.DEFAULT, view.state(player));
        assertEquals(state, view.state(other));
    }

    // The public testing API validates documents; inspect the builder callback only to exercise UI hand-over semantics.
    private static Object handler(Object container, String id) throws Exception {
        if (container instanceof Ui.Element<?> element) {
            var idField = Ui.Element.class.getDeclaredField("id"); idField.setAccessible(true);
            if (id.equals(idField.get(element))) {
                var field = Ui.Element.class.getDeclaredField("handler"); field.setAccessible(true);
                return field.get(element);
            }
        }
        var children = (container instanceof Panel ? Panel.class : Ui.Element.class).getDeclaredField("children");
        children.setAccessible(true);
        for (Object child : (List<?>) children.get(container)) {
            Object found = handler(child, id);
            if (found != null) return found;
        }
        return null;
    }

    @Test void givenSummonButton_whenClicked_thenServiceWaitsUntilPanelCloses() throws Exception {
        PanelAction action = mock(PanelAction.class);
        ((Ui.Click) handler(panel("stable", "b"), "summon")).handle(action);
        verify(action).closeAndThen(any());
        verifyNoInteractions(actions);
    }

    @Test void givenSuccessfulRemoval_whenClicked_thenOnlySelectedEntryIsRemovedAndPanelRefreshes() throws Exception {
        PanelAction action = mock(PanelAction.class);
        when(action.player()).thenReturn(player);
        when(actions.act(player, "remove", "b")).thenReturn(Vystorm.vystorm_core.panel.Result.ok());
        assertTrue(((Ui.Click) handler(panel("herd", "b"), "remove")).handle(action).success());
        verify(actions).act(player, "remove", "b");
        verify(action).refresh();
        verify(action, never()).closeAndThen(any());
    }
}
