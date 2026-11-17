package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Camel;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

class HorseComparisonTest {
    private final Player player = mock(Player.class);
    private final List<String> chat = new ArrayList<>();
    private Lang lang;
    private MockedStatic<HorseComparison.Stats> stats;
    private static final Vystorm.vystorm_core.compat.CompatInterface COMPAT = new Vystorm.vystorm_core.compat.CompatInterface() {};

    @BeforeEach
    void setup() throws Exception {
        // Paper's attribute registry needs a running server; supply snapshots at that boundary.
        stats = mockStatic(HorseComparison.Stats.class);
        try (var in = getClass().getResourceAsStream("/lang/en.yml")) {
            lang = Lang.create("VystormPaddock", Map.of("en", new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        }
        when(player.locale()).thenReturn(Locale.US);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(player).sendMessage(any(Component.class));
    }

    @AfterEach
    void close() { stats.close(); }

    private Horse horse(String name, double speed, double jump, double health) {
        Horse horse = mock(Horse.class);
        when(horse.getCustomName()).thenReturn(name);
        when(horse.customName()).thenReturn(Component.text(name));
        stats.when(() -> HorseComparison.Stats.read(any(), eq(horse))).thenReturn(new HorseComparison.Stats(speed, jump, health));
        return horse;
    }

    @Test
    void comparesTargetMinusMountAndUsesMaximumHealth() {
        Horse ridden = horse("Ridden", 0.225, 1.0, 20);
        Horse target = horse("Target", 0.3375, 0.4, 30);
        when(player.getVehicle()).thenReturn(ridden);
        when(player.getTargetEntity(9)).thenReturn(target);
        HorseComparison.show(COMPAT, player, lang, 9);
        assertEquals(4, chat.size());
        assertTrue(chat.get(0).contains("Ridden (ridden) → Target (looked at)"));
        assertEquals("Speed (blocks/s): 9.5 → 14.2 (+4.7)", chat.get(1));
        assertEquals("Jump (blocks): 5.3 → 1.1 (-4.2)", chat.get(2));
        assertEquals("Max health (❤): 10.0 → 15.0 (+5.0)", chat.get(3));
        verify(ridden, never()).getHealth();
        verify(target, never()).getHealth();
        verify(player).getTargetEntity(9);
    }

    @Test
    void equalAndNearlyEqualValuesDoNotShowNegativeZero() {
        Horse a = horse("A", 0.225, 0.7, 20), b = horse("B", 0.224999, 0.7, 20);
        when(player.getVehicle()).thenReturn(a);
        when(player.getTargetEntity(6)).thenReturn(b);
        HorseComparison.show(COMPAT, player, lang, 6);
        for (String row : chat.subList(1, 4)) assertTrue(row.endsWith("(+0.0)"), row);
    }

    @Test
    void requiresRidingAnOrdinaryHorse() {
        HorseComparison.show(COMPAT, player, lang, 6);
        when(player.getVehicle()).thenReturn(mock(Camel.class));
        HorseComparison.show(COMPAT, player, lang, 6);
        assertEquals(List.of("Ride a horse to compare it with another horse.", "Ride a horse to compare it with another horse."), chat);
        verify(player, never()).getTargetEntity(anyInt());
    }

    @Test
    void rejectsMissingTargetOtherSpeciesAndTheMountItself() {
        Horse ridden = mock(Horse.class);
        when(player.getVehicle()).thenReturn(ridden);
        HorseComparison.show(COMPAT, player, lang, 6);
        when(player.getTargetEntity(6)).thenReturn(mock(Camel.class));
        HorseComparison.show(COMPAT, player, lang, 6);
        when(player.getTargetEntity(6)).thenReturn(ridden);
        HorseComparison.show(COMPAT, player, lang, 6);
        assertEquals(3, chat.size());
        for (String message : chat) assertEquals("Look at another horse within reach while riding.", message);
    }
}
