package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every feature has a switch (1.18.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys (the two features that shipped off stay off), and the placeholders resolve and
 * are empty while their feature is off.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("stable_slots", "stable_stored", "mount_speed_level", "mount_jump_level",
            "mount_owner", "race_best", "race_rank", "race_record", "race_courses");
    @TempDir Path dir;

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchHasItsDefaultAndIsExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertEquals(feature.defaultOn, config.getBoolean(feature.path), feature.path);
            String parent = feature.path.contains(".") ? feature.path.substring(0, feature.path.lastIndexOf('.')) : feature.path;
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenTheOffMessageEverySwitchAndPlaceholderHaveTexts() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("<red>This feature is switched off on this server.", YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/en.yml")).getString("feature-off"));
    }

    @Test void givenAConfigFrom1_17_whenRead_thenEveryFeatureKeepsItsState() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File("src/test/resources/config-1.17.yml"));
        assertEquals(Set.of(Feature.ENVIRONMENT, Feature.CALL_ACROSS_WORLDS), Feature.Switches.read(old).off());
        assertEquals(Set.of(Feature.ENVIRONMENT, Feature.CALL_ACROSS_WORLDS), Feature.Switches.read(new YamlConfiguration()).off(), "missing keys keep their defaults");
    }

    @Test void givenAConfigFrom1_17_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        Path data = Files.createDirectories(dir.resolve("VystormPaddock"));
        Files.writeString(data.resolve("config.yml"), Files.readString(Path.of("src/test/resources/config-1.17.yml")).replace("protect-from-environment: false", "protect-from-environment: true"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormPaddock");
        when(plugin.namespace()).thenReturn("vystormpaddock");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(Path.of("src/main/resources/config.yml")));
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile()));

        assertTrue(ConfigEditor.mergeDefaults(plugin) >= 12);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        for (Feature feature : Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        assertEquals(Set.of(Feature.CALL_ACROSS_WORLDS), Feature.Switches.read(merged).off(), "an existing value is never changed");
        assertTrue(Files.readString(data.resolve("config.yml")).contains("The whole plugin"), "comments come along");
    }

    @Test void givenStableRestOff_whenTheHealingTimeIsRead_thenThereIsNone() {
        assertEquals(600_000L, PaddockPlugin.restMillis(10, true));
        assertEquals(0L, PaddockPlugin.restMillis(10, false));
        assertEquals(10.0, PaddockPlugin.restedHealth(10, 20, 3_600_000, PaddockPlugin.restMillis(10, false)));
    }

    // ------------------------------------------------------------------ fixture

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = PaddockPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Lang lang(PaddockPlugin plugin) throws Exception {
        Field field = PaddockPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    private final Claims claims = mock(Claims.class);
    private final HerdBook herdBook = mock(HerdBook.class);

    private PaddockPlugin plugin(Feature... off) throws Exception {
        PaddockPlugin paddock = mock(PaddockPlugin.class, CALLS_REAL_METHODS);
        Map<String, Object> fields = new HashMap<>();
        fields.put("core", mock(API.class, RETURNS_DEEP_STUBS));
        fields.put("lang", mock(Lang.class));
        fields.put("claims", claims);
        fields.put("herdBook", herdBook);
        fields.put("training", mock(AnimalTraining.class));
        fields.put("raceTracker", mock(RaceTracker.class));
        fields.put("lastRidden", new HashMap<UUID, UUID>());
        fields.put("lastWarning", new HashMap<UUID, Long>());
        fields.put("switches", Feature.Switches.without(off));
        for (var entry : fields.entrySet()) field(paddock, entry.getKey(), entry.getValue());
        doReturn("VystormPaddock").when(paddock).getName();
        doReturn(true).when(paddock).isEnabled();
        doReturn(new YamlConfiguration()).when(paddock).getConfig();
        return paddock;
    }

    // ------------------------------------------------------------------ protection, notices, breeding

    @Test void givenClaimsOff_whenAStrangerMountsAClaimedHorse_thenNothingStopsIt() throws Exception {
        AbstractHorse horse = mock(Horse.class);
        Player stranger = mock(Player.class);
        when(stranger.getUniqueId()).thenReturn(UUID.randomUUID());
        when(claims.allowed(horse, stranger)).thenReturn(false);
        for (boolean on : List.of(true, false)) {
            PaddockPlugin paddock = on ? plugin() : plugin(Feature.CLAIMS);
            EventStageTesting stages = EventStageTesting.detached();
            paddock.handleSharedEvents(stages.stages());
            assertEquals(on, stages.fire(new EntityMountEvent(stranger, horse)).isCancelled(), "claims " + on);
        }
    }

    @Test void givenDeathNoticesOff_whenAClaimedHorseDies_thenTheOwnerIsNotTold() throws Exception {
        AbstractHorse horse = mock(Horse.class);
        when(horse.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        UUID owner = UUID.randomUUID();
        when(claims.owner(horse)).thenReturn(owner);
        PaddockPlugin off = plugin(Feature.DEATH_NOTICES);
        off.onDeath(new EntityDeathEvent(horse, mock(DamageSource.class), new ArrayList<>()));
        verify(herdBook).forget(owner, horse.getUniqueId());
        verifyNoInteractions(lang(off));
    }

    @Test void givenBreedingRecordsOff_whenAFoalIsBorn_thenNoPedigreeIsWritten() throws Exception {
        for (boolean on : List.of(true, false)) {
            Claims recorder = mock(Claims.class);
            PaddockPlugin paddock = on ? plugin() : plugin(Feature.BREEDING);
            field(paddock, "claims", recorder);
            field(paddock, "claimFoals", false);
            EntityBreedEvent event = mock(EntityBreedEvent.class);
            AbstractHorse foal = mock(Horse.class), sire = mock(Horse.class), dam = mock(Horse.class);
            for (AbstractHorse horse : List.of(foal, sire, dam)) when(horse.getType()).thenReturn(org.bukkit.entity.EntityType.HORSE);
            when(event.getEntity()).thenReturn(foal);
            when(event.getFather()).thenReturn(sire);
            when(event.getMother()).thenReturn(dam);
            paddock.onBreed(event);
            verify(recorder, times(on ? 1 : 0)).pedigree(any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
        }
    }

    // ------------------------------------------------------------------ commands

    @Test void givenAFeatureOff_whenItsCommandRuns_thenItAnswersTheOffMessage() throws Exception {
        Map<Feature, BiConsumer<PaddockPlugin, Player>> actions = new LinkedHashMap<>();
        actions.put(Feature.CLAIMS, PaddockPlugin::claim);
        actions.put(Feature.TRUST, (p, player) -> p.trust(player, new String[]{"trust", "Bob"}, true));
        actions.put(Feature.INFO, PaddockPlugin::info);
        actions.put(Feature.RENAME, (p, player) -> p.rename(player, new String[]{"rename", "Star"}));
        actions.put(Feature.STABLE, PaddockPlugin::store);
        actions.put(Feature.STABLE_MENU, PaddockPlugin::openStable);
        actions.put(Feature.CALL, PaddockPlugin::call);
        actions.put(Feature.HERD, (p, player) -> p.herd(player, false));
        actions.put(Feature.TRADE, PaddockPlugin::accept);
        actions.put(Feature.BETTING, (p, player) -> p.bet(player, null, null, null));
        for (var entry : actions.entrySet()) {
            PaddockPlugin paddock = plugin(entry.getKey());
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            entry.getValue().accept(paddock, player);
            verify(lang(paddock)).send(player, "feature-off");
        }
        PaddockPlugin master = plugin(Feature.CLAIMS);
        Player player = mock(Player.class);
        master.info(player);
        verify(lang(master)).send(player, "feature-off");
    }

    @Test void givenAllOn_whenInfoRuns_thenItLooksForTheAnimal() throws Exception {
        PaddockPlugin paddock = plugin();
        Player player = mock(Player.class);
        paddock.info(player);
        verify(lang(paddock)).send(player, "no-target");
    }

    @Test void givenSwitchesOff_whenTheCommandTreeIsBuilt_thenTheirSubcommandsAreHidden() throws Exception {
        PaddockPlugin paddock = plugin();
        CommandSpec tree = PaddockPlugin.commandSpec(paddock);
        assertTrue(tree.children().values().stream().noneMatch(CommandSpec::hidden));
        assertTrue(tree.children().values().stream().allMatch(sub -> sub.visibleTo(null)));
        // the same built tree follows the switch: no second registration needed
        field(paddock, "switches", Feature.Switches.without(Feature.COURSES, Feature.TRUST));
        assertFalse(tree.children().get("course").visibleTo(null));
        assertFalse(tree.children().get("trust").visibleTo(null) || tree.children().get("untrust").visibleTo(null));
        assertTrue(tree.children().get("race").visibleTo(null));
        field(paddock, "switches", Feature.Switches.without(Feature.CLAIMS));
        for (var sub : tree.children().entrySet()) assertEquals(sub.getKey().equals("reload"), sub.getValue().visibleTo(null), sub.getKey());
        field(paddock, "switches", Feature.Switches.without());
        assertTrue(tree.children().values().stream().allMatch(sub -> sub.visibleTo(null)));
    }

    @Test void givenASwitchChanged_whenReloaded_thenTheCommandListIsSentAgainAndNothingIsRegistered() throws Exception {
        PaddockPlugin paddock = plugin();
        API core = mock(API.class, RETURNS_DEEP_STUBS);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        field(paddock, "core", core);
        field(paddock, "switches", null);
        YamlConfiguration config = new YamlConfiguration();
        doReturn(config).when(paddock).getConfig();
        doNothing().when(paddock).reloadConfig();
        doReturn(true).when(paddock).readConfig(); // config.yml parsed
        var load = PaddockPlugin.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(paddock);
        verify(commands, never()).refresh();
        config.set(Feature.COURSES.path, false);
        load.invoke(paddock);
        verify(commands).refresh();
        load.invoke(paddock);
        verify(commands).refresh();
        verify(commands, never()).register(any(), any(), any());
    }

    @Test void givenCourseEditorOff_whenACourseIsBuilt_thenOffMessage() throws Exception {
        PaddockPlugin paddock = plugin(Feature.COURSES);
        RaceTracker tracker = new RaceTracker(paddock, mock(API.class), new Races(dir.resolve("races.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        when(paddock.lang()).thenReturn(lang(paddock));
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);
        tracker.course(player, new String[]{"course", "create", "track"});
        verify(lang(paddock)).send(player, "feature-off");
    }

    @Test void givenRacesOff_whenACourseIsBuilt_thenTheAnswerNamesTheSwitchItNeeds() throws Exception {
        PaddockPlugin paddock = plugin(Feature.RACES);
        RaceTracker tracker = new RaceTracker(paddock, mock(API.class), new Races(dir.resolve("races.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        Field enabled = RaceTracker.class.getDeclaredField("enabled"); // as configure(on(RACES), ...) leaves it
        enabled.setAccessible(true);
        enabled.set(tracker, false);
        when(paddock.lang()).thenReturn(lang(paddock));
        Player player = mock(Player.class);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(lang(paddock).plain(player, Feature.RACES.label())).thenReturn("Races");
        tracker.course(player, new String[]{"course", "create", "track"});
        verify(lang(paddock)).send(player, "feature-needs", "switch", "races.enabled", "name", "Races");
    }

    @Test void givenADependencyOff_whenTheCommandRuns_thenTheAnswerNamesTheSwitchItNeeds() throws Exception {
        Map<Feature, BiConsumer<PaddockPlugin, Player>> actions = new LinkedHashMap<>();
        actions.put(Feature.STABLE, PaddockPlugin::openStable);              // the stable menu needs the stable
        actions.put(Feature.RACES, (p, player) -> p.bet(player, null, null, null)); // bets are placed on races
        for (var entry : actions.entrySet()) {
            PaddockPlugin paddock = plugin(entry.getKey());
            Player player = mock(Player.class);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(lang(paddock).plain(player, entry.getKey().label())).thenReturn("Name of " + entry.getKey().path);
            entry.getValue().accept(paddock, player);
            verify(lang(paddock)).send(player, "feature-needs", "switch", entry.getKey().path, "name", "Name of " + entry.getKey().path);
        }
    }

    @Test void givenOnlyTheNeededSwitchOff_whenTheCommandTreeIsBuilt_thenCourseAndStableStayListedToNameIt() throws Exception {
        PaddockPlugin paddock = plugin(Feature.RACES, Feature.STABLE);
        CommandSpec tree = PaddockPlugin.commandSpec(paddock);
        assertTrue(tree.children().get("course").visibleTo(null));
        assertTrue(tree.children().get("stable").visibleTo(null));
        assertFalse(tree.children().get("race").visibleTo(null));
        assertFalse(tree.children().get("store").visibleTo(null));
    }

    @Test void givenEveryLanguage_whenRead_thenTheNeedsMessageNamesTheSwitch() {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/lang/" + code + ".yml")).getString("feature-needs", "").matches(".*\\{name}.*\\{switch}.*"), code);
    }

    // ------------------------------------------------------------------ trust and pets on the claims

    @Test void givenTrustOrPetsProtectionOff_whenAccessIsChecked_thenTrustedPlayersAndPetOwnersCountForNothing() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormPaddock");
        when(plugin.namespace()).thenReturn("vystormpaddock");
        Claims real = new Claims(plugin);
        UUID owner = UUID.randomUUID(), friend = UUID.randomUUID();
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(call -> {
            NamespacedKey key = call.getArgument(0);
            return switch (key.getKey()) {
                case "owner" -> key.getNamespace().equals("vystormpets") ? null : owner.toString();
                case "trusted" -> friend.toString();
                default -> null;
            };
        });
        AbstractHorse horse = mock(Horse.class);
        when(horse.getPersistentDataContainer()).thenReturn(data);
        Player trusted = mock(Player.class);
        when(trusted.getUniqueId()).thenReturn(friend);
        assertTrue(real.allowed(horse, trusted));
        real.switches(false, true);
        assertFalse(real.allowed(horse, trusted));

        PersistentDataContainer petData = mock(PersistentDataContainer.class);
        when(petData.get(Claims.PET_OWNER, PersistentDataType.STRING)).thenReturn(owner.toString());
        AbstractHorse pet = mock(Horse.class);
        when(pet.getPersistentDataContainer()).thenReturn(petData);
        real.switches(true, true);
        assertEquals(owner, real.owner(pet));
        real.switches(true, false);
        assertNull(real.owner(pet), "pets-protection off: the pet owner counts for nothing");
    }

    @Test void givenTutorialOff_whenThePluginStarts_thenNoTutorialIsRegistered() throws Exception {
        for (boolean enabled : List.of(true, false)) {
            PaddockPlugin plugin = mock(PaddockPlugin.class);
            API core = mock(API.class);
            field(plugin, "core", core);
            YamlConfiguration config = new YamlConfiguration();
            config.set("tutorial.enabled", enabled);
            when(plugin.getConfig()).thenReturn(config);
            doCallRealMethod().when(plugin).registerTutorial();
            plugin.registerTutorial();
            verify(core, times(enabled ? 1 : 0)).registerTutorial(plugin);
        }
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenRegisteredValues_whenResolved_thenTheyAnswerAndAreEmptyWhenTheirFeatureIsOff() {
        UUID alex = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(alex);
        when(player.isOnline()).thenReturn(true);
        Races races = new Races(dir.resolve("r.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        Races spy = spy(races);
        Races.Course course = new Races.Course("Meadow", "world", 4);
        course.points().add(new double[]{0, 64, 0});
        course.points().add(new double[]{10, 64, 0});
        course.times().put(alex, new Races.Time(alex, "Alex", 62_350, 1));
        course.times().put(UUID.randomUUID(), new Races.Time(UUID.randomUUID(), "Bob", 60_000, 2));
        doReturn(course).when(spy).get("Meadow");
        doReturn(List.of(course)).when(spy).courses();
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        PlaceholderTesting testing = PlaceholderTesting.detached();
        PaddockPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormPaddock"), new PaddockPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return !off.contains(feature) && !off.contains(Feature.CLAIMS); }
            @Override public int slots(Player who) { return 3; }
            @Override public int stored(UUID who) { return 2; }
            @Override public Claims claims() { return claims; }
            @Override public Races races() { return spy; }
        }, key -> key);
        String text = "{paddock:stable_slots} {paddock:stable_stored} {paddock:race_best:Meadow} {paddock:race_rank:Meadow} {paddock:race_record:Meadow} {paddock:race_courses}";
        assertEquals("3 2 1:02.35 2 1:00.00 1", testing.resolve(player, text));
        off.add(Feature.RACES);
        assertEquals("3 2    ", testing.resolve(player, text));
        off.add(Feature.CLAIMS);
        testing.advanceTicks(200); // the stable count is kept for five seconds
        assertEquals("     ", testing.resolve(player, text));
    }
}
