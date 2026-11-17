package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The shared event handlers of Paddock, called by Vystorm Core's real stage dispatcher together with the handlers of
 * a second plugin: a claim denies in PROTECT before any feature acts, and Paddock's own bookkeeping (OBSERVE) only
 * follows what really happened.
 */
class PaddockStagesTest {
    private static final UUID OWNER = UUID.randomUUID(), STRANGER = UUID.randomUUID();
    private final List<String> calls = new ArrayList<>();
    private final Plugin feature = EventStageTesting.plugin("AFeaturePlugin");
    private final Claims claims = mock(Claims.class);
    private final HerdBook herdBook = mock(HerdBook.class);
    private final AnimalTraining training = mock(AnimalTraining.class);
    private final RaceTracker races = mock(RaceTracker.class);
    private final Map<UUID, UUID> lastRidden = new HashMap<>();
    private final Horse horse = mock(Horse.class);
    private final Player owner = player(OWNER), stranger = player(STRANGER);
    private EventStageTesting stages;
    private PaddockPlugin paddock;

    @BeforeEach
    void setUp() throws Exception {
        paddock = mock(PaddockPlugin.class, CALLS_REAL_METHODS);
        for (var entry : Map.<String, Object>of("core", mock(API.class, RETURNS_DEEP_STUBS), "lang", mock(Lang.class), "claims", claims,
                "herdBook", herdBook, "training", training, "raceTracker", races, "lastRidden", lastRidden,
                "lastWarning", new HashMap<UUID, Long>(), "protectEnvironment", true).entrySet()) {
            Field field = PaddockPlugin.class.getDeclaredField(entry.getKey());
            field.setAccessible(true);
            field.set(paddock, entry.getValue());
        }
        doReturn("VystormPaddock").when(paddock).getName();
        doReturn(true).when(paddock).isEnabled();
        when(horse.getUniqueId()).thenReturn(UUID.randomUUID());
        when(horse.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        when(claims.owner(horse)).thenReturn(OWNER);
        when(claims.claimed(horse)).thenReturn(true);
        when(claims.allowed(horse, owner)).thenReturn(true);
        stages = EventStageTesting.detached();
    }

    private static Player player(UUID id) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        return player;
    }

    /** A second plugin that registers first and sorts first by name: only the stage puts Paddock ahead of it. */
    private <E extends Event> void aFeaturePluginHandles(Class<E> type) {
        for (EventStage stage : List.of(EventStage.ADJUST, EventStage.ACT, EventStage.OBSERVE))
            stages.handle(feature, type, stage, event -> calls.add(stage.name()));
        paddock.handleSharedEvents(stages.stages());
    }

    private EntityDamageByEntityEvent hit(Player attacker) {
        return new EntityDamageByEntityEvent(attacker, horse, EntityDamageEvent.DamageCause.ENTITY_ATTACK, mock(DamageSource.class), 2);
    }

    @Test
    void givenClaimedHorse_whenAStrangerHitsIt_thenNoLaterStageSeesTheHit() {
        aFeaturePluginHandles(EntityDamageEvent.class);

        assertTrue(stages.fire(hit(stranger)).isCancelled());
        assertEquals(List.of(), calls);

        assertFalse(stages.fire(hit(owner)).isCancelled());
        assertEquals(List.of("ADJUST", "ACT", "OBSERVE"), calls);
    }

    @Test
    void givenEnvironmentProtection_whenAClaimedHorseFalls_thenNoLaterStageSeesTheDamageButTheVoidStillKills() {
        aFeaturePluginHandles(EntityDamageEvent.class);

        assertTrue(stages.fire(new EntityDamageEvent(horse, EntityDamageEvent.DamageCause.FALL, mock(DamageSource.class), 4)).isCancelled());
        assertEquals(List.of(), calls);

        assertFalse(stages.fire(new EntityDamageEvent(horse, EntityDamageEvent.DamageCause.VOID, mock(DamageSource.class), 4)).isCancelled());
        assertEquals(List.of("ADJUST", "ACT", "OBSERVE"), calls);
    }

    @Test
    void givenClaimedHorse_whenAStrangerUsesIt_thenNoLaterStageSeesTheClick() {
        aFeaturePluginHandles(PlayerInteractEntityEvent.class);

        assertTrue(stages.fire(new PlayerInteractEntityEvent(stranger, horse)).isCancelled());
        assertEquals(List.of(), calls);

        assertFalse(stages.fire(new PlayerInteractEntityEvent(owner, horse)).isCancelled());
        assertEquals(List.of("ADJUST", "ACT", "OBSERVE"), calls);
    }

    @Test
    void givenClaimedHorse_whenAStrangerMounts_thenItIsDeniedAndNothingIsRemembered() {
        aFeaturePluginHandles(EntityMountEvent.class);

        assertTrue(stages.fire(new EntityMountEvent(stranger, horse)).isCancelled());

        assertEquals(List.of(), calls);
        assertTrue(lastRidden.isEmpty());
    }

    @Test
    void givenClaimedHorse_whenTheOwnerMounts_thenItIsTheLastRiddenOnlyIfNoOtherPluginDeniedTheMount() {
        boolean[] deny = {true};
        paddock.handleSharedEvents(stages.stages());
        stages.handle(feature, EntityMountEvent.class, EventStage.ADJUST, event -> event.setCancelled(deny[0]));

        assertTrue(stages.fire(new EntityMountEvent(owner, horse)).isCancelled());
        assertTrue(lastRidden.isEmpty());

        deny[0] = false;
        assertFalse(stages.fire(new EntityMountEvent(owner, horse)).isCancelled());
        assertEquals(Map.of(OWNER, horse.getUniqueId()), lastRidden);
    }

    @Test
    void givenATeleport_whenAnotherPluginDeniesIt_thenTrainingAndRacesAreNotEnded() {
        boolean[] deny = {true};
        paddock.handleSharedEvents(stages.stages());
        stages.handle(feature, PlayerTeleportEvent.class, EventStage.PROTECT, event -> event.setCancelled(deny[0]));
        PlayerTeleportEvent denied = new PlayerTeleportEvent(owner, new Location(null, 0, 64, 0), new Location(null, 100, 64, 0));

        stages.fire(denied);
        verify(training, never()).teleported(any());
        verify(races, never()).onTeleport(any());

        deny[0] = false;
        PlayerTeleportEvent allowed = stages.fire(new PlayerTeleportEvent(owner, new Location(null, 0, 64, 0), new Location(null, 100, 64, 0)));
        verify(training).teleported(allowed);
        verify(races).onTeleport(allowed);
    }

    @Test
    void givenAnUnclaimedHorse_whenItDies_thenTheHerdBookForgetsItAfterEveryOtherStage() {
        when(claims.owner(horse)).thenReturn(null);
        stages.handle(feature, EntityDeathEvent.class, EventStage.FINISH, event -> calls.add("FINISH, forgotten: " + forgotten()));
        paddock.handleSharedEvents(stages.stages());

        stages.fire(new EntityDeathEvent(horse, mock(DamageSource.class), new ArrayList<>()));

        assertEquals(List.of("FINISH, forgotten: false"), calls);
        verify(herdBook, times(1)).forget(null, horse.getUniqueId());
    }

    private boolean forgotten() {
        return !org.mockito.Mockito.mockingDetails(herdBook).getInvocations().isEmpty();
    }
}
