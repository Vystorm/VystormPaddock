package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.panel.PanelOpenResult;
import Vystorm.vystorm_core.panel.PanelSpec;
import Vystorm.vystorm_core.panel.PanelTesting;
import Vystorm.vystorm_core.panel.Panels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class PaddockPanelsTest {
    private final PaddockPlugin plugin = mock(PaddockPlugin.class);
    private final Stable stable = mock(Stable.class);
    private final HerdBook herd = mock(HerdBook.class);
    private final RaceTracker races = mock(RaceTracker.class);
    private final Panels core = mock(Panels.class);
    private final Player player = mock(Player.class);
    private final Map<String, PanelSpec> specs = new HashMap<>();

    private PaddockPanels create() throws Exception {
        when(plugin.lang()).thenReturn(PaddockPanelTest.lang());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        when(plugin.isEnabled()).thenReturn(true);
        when(player.isOnline()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(anyString())).thenReturn(true);
        when(herd.enabled()).thenReturn(true);
        when(races.enabled()).thenReturn(true);
        doAnswer(call -> { specs.put(call.getArgument(0), call.getArgument(1)); return null; }).when(core).register(anyString(), any());
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(core);
            return new PaddockPanels(plugin, Vystorm.vystorm_core.panel.PanelsInterface.DEFAULT, stable, herd, races);
        }
    }

    @Test void givenMenus_whenRegistered_thenAllHaveAccessChecksAndChestFallbacksWithoutDuplicateMenuEntries() throws Exception {
        create();
        assertEquals(java.util.Set.of("stable", "herd", "races"), specs.keySet());
        assertNull(specs.get("stable").menuCategory());
        assertNotNull(specs.get("herd").menuCategory());
        assertNotNull(specs.get("races").menuCategory());
        for (PanelSpec spec : specs.values()) assertTrue(spec.access().test(player));
        when(player.hasPermission(anyString())).thenReturn(false);
        for (PanelSpec spec : specs.values()) assertFalse(spec.access().test(player));
        specs.get("herd").fallback().accept(player);
        specs.get("races").fallback().accept(player);
        verify(herd).openChest(player);
        verify(races).openChest(player);
    }

    @Test void givenStableFilters_whenCoreUsesFallback_thenChestKeepsSearchPageAndSort() throws Exception {
        PaddockPanels bridge = create();
        when(core.open(player, "stable")).thenAnswer(call -> {
            specs.get("stable").fallback().accept(player);
            return PanelOpenResult.FALLBACK;
        });
        assertTrue(bridge.openStable(player, "Amber", 2, StablePage.Sort.NAME));
        verify(plugin).openStableChest(player, "Amber", 2, StablePage.Sort.NAME);
    }

    @Test void givenUnavailableOrFailingCore_whenOpened_thenCallerIsToldToUseChest() throws Exception {
        PaddockPanels bridge = create();
        when(core.open(player, "stable")).thenReturn(PanelOpenResult.NONE)
                .thenThrow(new IllegalStateException("unavailable"));
        for (int i = 0; i < 2; i++) assertFalse(bridge.open(player, "stable"));
    }

    @Test void givenRevokedAccessOrDisabledFeature_whenActionRuns_thenNoServiceMutationOccurs() throws Exception {
        PaddockPanels bridge = create();
        when(player.hasPermission(anyString())).thenReturn(false);
        assertFalse(bridge.act(player, "summon", "animal").success());
        verify(plugin, never()).summonId(any(), anyString());
        when(player.hasPermission(anyString())).thenReturn(true);
        when(herd.enabled()).thenReturn(false);
        assertFalse(bridge.act(player, "remove", "animal").success());
        verify(herd, never()).act(any(), anyString(), anyString());
        when(races.enabled()).thenReturn(false);
        assertFalse(bridge.act(player, "race-info", "course").success());
        verify(races, never()).showCourse(any(), anyString());
    }

    @Test void givenRaceNamedStop_whenDetailsRequested_thenItIsResolvedAsACourse() throws Exception {
        PaddockPanels bridge = create();
        bridge.act(player, "race-info", "stop");
        verify(races).showCourse(player, "stop");
    }

    @Test void givenManyCourses_whenRendered_thenPageContainsEightEntries() throws Exception {
        PaddockPanels bridge = create();
        List<Races.Course> courses = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            Races.Course course = new Races.Course("track" + i, "world", 4);
            course.points().add(new double[]{0, 64, 0});
            course.points().add(new double[]{10, 64, 10});
            courses.add(course);
        }
        when(races.readyCourses()).thenReturn(courses);
        String document = PanelTesting.render(specs.get("races"), player, "").toString();
        assertTrue(document.contains("track7"));
        assertFalse(document.contains("track8"));
        bridge.close();
        for (String name : List.of("stable", "herd", "races")) verify(core).unregister(name);
    }
}
