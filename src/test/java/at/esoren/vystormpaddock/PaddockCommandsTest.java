package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /paddock through Core's command tree with the real language files: names, routing, completion and help. */
class PaddockCommandsTest {
    private static final String[] PLAYER = {"vystormpaddock.use", "vystormpaddock.race"};
    private final List<String> chat = new ArrayList<>();

    private static String read(String name) throws Exception {
        try (InputStream in = PaddockCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CommandRegistration registration(PaddockPlugin handlers) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormPaddock");
        Lang lang = Lang.create("VystormPaddock", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        return CommandTesting.detached(plugin, lang, PaddockPlugin.commandSpec(handlers));
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(p).sendMessage(any(Component.class));
        return p;
    }

    @Test
    void givenBetConfig_whenCompletingAmount_thenMinAndMaxStake() throws Exception {
        assertEquals(List.of("10", "1000"), PaddockPlugin.betAmounts(10, 1000, ""));
        assertEquals(List.of("1000"), PaddockPlugin.betAmounts(10, 1000, "100"));
        assertEquals(List.of("0.01", "5.50"), PaddockPlugin.betAmounts(0, 5.5, null));
        assertEquals(List.of("50"), PaddockPlugin.betAmounts(50, 20, ""));
        PaddockPlugin handlers = mock(PaddockPlugin.class);
        YamlConfiguration config = new YamlConfiguration();
        config.set("betting.min", 25);
        config.set("betting.max", 500);
        when(handlers.getConfig()).thenReturn(config);
        assertEquals(List.of("25", "500"), registration(handlers).suggest(player(Locale.US, "vystormpaddock.bet"), "paddock", new String[]{"bet", "Rider", "beat", ""}));
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration(null);
        String[] none = {""};
        assertEquals(List.of("claim", "release", "trust", "untrust", "info", "compare", "rename", "store", "list", "stable", "summon", "call", "herd", "race", "help"),
                r.suggest(player(Locale.US, PLAYER), "paddock", none));
        assertEquals(List.of("beanspruchen", "freigeben", "vertrauen", "misstrauen", "info", "vergleichen", "umbenennen", "einstallen", "liste", "stall",
                "herbeirufen", "pfeifen", "herde", "rennen", "strecke", "neuladen", "hilfe"),
                r.suggest(player(Locale.GERMANY, "vystormpaddock.use", "vystormpaddock.course", "vystormpaddock.admin"), "pferdekoppel", none));
        assertEquals(List.of("创建", "检查点", "撤销", "半径", "重置", "删除"),
                r.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormpaddock.use", "vystormpaddock.course"), "马场", new String[]{"赛道", ""}));
        assertTrue(r.suggest(player(Locale.GERMANY, PLAYER), "paddock", new String[]{"herde", ""}).contains("stopp"));
    }

    @Test
    void stableSearchRoutesMultiwordQueriesInEveryLanguage() throws Exception {
        PaddockPlugin p = mock(PaddockPlugin.class);
        CommandRegistration r = registration(p);
        Player player = player(Locale.US, PLAYER);
        for (String code : List.of("en", "de", "zh_cn")) {
            String command = yaml(code).getString("commands.paddock.sub.stable.name");
            r.execute(player, "paddock", new String[]{command, "Silver", "Star"});
        }
        verify(p, times(3)).openStable(player, "Silver Star", 0);
        r.execute(player(Locale.US), "paddock", new String[]{"stable", "horse"});
        verifyNoMoreInteractions(p);
    }

    @Test
    void comparisonRoutesInEveryLanguageAndRequiresPlayerPermission() throws Exception {
        PaddockPlugin p = mock(PaddockPlugin.class);
        CommandRegistration r = registration(p);
        Player player = player(Locale.US, PLAYER);
        for (String command : List.of("compare", "vergleichen", "比较")) r.execute(player, "paddock", new String[]{command});
        verify(p, times(3)).compare(player);
        r.execute(player(Locale.US), "paddock", new String[]{"compare"});
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        r.execute(console, "paddock", new String[]{"compare"});
        verifyNoMoreInteractions(p);
    }

    @Test
    void givenOldEnglishCommands_whenRun_thenSameHandlersAndArguments() throws Exception {
        PaddockPlugin p = mock(PaddockPlugin.class);
        CommandRegistration r = registration(p);
        Player player = player(Locale.US, "vystormpaddock.use", "vystormpaddock.race", "vystormpaddock.course", "vystormpaddock.admin");
        r.execute(player, "paddock", new String[]{"claim"});
        verify(p).claim(player);
        r.execute(player, "stable", new String[]{"release"});
        verify(p).release(player);
        r.execute(player, "paddock", new String[]{"trust", "Bob"});
        verify(p).trust(eq(player), aryEq(new String[]{"trust", "Bob"}), eq(true));
        r.execute(player, "paddock", new String[]{"UNTRUST", "Bob"});
        verify(p).trust(eq(player), aryEq(new String[]{"untrust", "Bob"}), eq(false));
        r.execute(player, "paddock", new String[]{"info"});
        verify(p).info(player);
        r.execute(player, "paddock", new String[]{"rename", "Silver", "Star"});
        verify(p).rename(eq(player), aryEq(new String[]{"rename", "Silver Star"}));
        r.execute(player, "paddock", new String[]{"store"});
        verify(p).store(player);
        r.execute(player, "paddock", new String[]{"list"});
        verify(p).list(player);
        r.execute(player, "paddock", new String[]{"stable"});
        verify(p).openStable(player);
        r.execute(player, "paddock", new String[]{"summon"});
        verify(p).summon(eq(player), aryEq(new String[]{"summon"}));
        r.execute(player, "paddock", new String[]{"summon", "2"});
        verify(p).summon(eq(player), aryEq(new String[]{"summon", "2"}));
        r.execute(player, "paddock", new String[]{"call"});
        verify(p).call(player);
        r.execute(player, "paddock", new String[]{"herd"});
        r.execute(player, "paddock", new String[]{"herd", "stop"});
        verify(p).herd(player, false);
        verify(p).herd(player, true);
        r.execute(player, "paddock", new String[]{"race"});
        verify(p).race(player, null, false);
        r.execute(player, "paddock", new String[]{"race", "stop"});
        verify(p).race(player, "stop", true);
        r.execute(player, "paddock", new String[]{"race", "Derby"});
        verify(p).race(player, "Derby", false);
        r.execute(player, "paddock", new String[]{"course", "create", "Derby"});
        verify(p).course(player, "create", "Derby", null);
        for (String action : List.of("point", "undo", "reset", "delete")) {
            r.execute(player, "paddock", new String[]{"course", action, "Derby"});
            verify(p).course(player, action, "Derby", null);
        }
        r.execute(player, "paddock", new String[]{"course", "radius", "Derby", "6"});
        verify(p).course(player, "radius", "Derby", "6");
        r.execute(player, "paddock", new String[]{"reload"});
        verify(p).reload(player);
        assertEquals(List.of(), chat, "no error message on any old path");
    }

    @Test
    void givenLocalizedNames_whenRun_thenSameHandlers() throws Exception {
        PaddockPlugin p = mock(PaddockPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormpaddock.use", "vystormpaddock.course");
        r.execute(german, "pferdekoppel", new String[]{"herbeirufen", "1"});
        verify(p).summon(eq(german), aryEq(new String[]{"summon", "1"}));
        r.execute(german, "pferdekoppel", new String[]{"herde", "stopp"});
        verify(p).herd(german, true);
        r.execute(german, "pferdekoppel", new String[]{"strecke", "löschen", "Derby"});
        verify(p).course(german, "delete", "Derby", null);
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormpaddock.use");
        r.execute(chinese, "马场", new String[]{"认领"});
        verify(p).claim(chinese);
        // every installed language is accepted for everyone
        r.execute(chinese, "paddock", new String[]{"einstallen"});
        verify(p).store(chinese);
    }

    @Test
    void givenConsole_whenPlayerSubOrReload_thenRefusedOrRun() throws Exception {
        PaddockPlugin p = mock(PaddockPlugin.class);
        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(console).sendMessage(any(Component.class));
        CommandRegistration r = registration(p);
        r.execute(console, "paddock", new String[]{"claim"});
        assertEquals("Only players can do that.", chat.get(0));
        r.execute(console, "paddock", new String[]{"reload"});
        verify(p).reload(console);
        verify(p, never()).claim(any());
    }

    @Test
    void givenMissingPermission_whenRun_thenRefused() throws Exception {
        PaddockPlugin p = mock(PaddockPlugin.class);
        CommandRegistration r = registration(p);
        r.execute(player(Locale.US, "vystormpaddock.use"), "paddock", new String[]{"course", "create", "Derby"});
        r.execute(player(Locale.US, "vystormpaddock.use"), "paddock", new String[]{"reload"});
        verifyNoInteractions(p);
        assertEquals(2, chat.size());
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration(null).execute(player(Locale.GERMANY, PLAYER), "pferdekoppel", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/pferdekoppel beanspruchen – Das gezähmte Tier"), all);
        assertTrue(all.contains("/pferdekoppel vertrauen SPIELER – Einen Spieler"), all);
        assertFalse(all.contains("neuladen"), all);
    }

    @Test
    void givenRootAliases_whenRead_thenGermanAndChinese() throws Exception {
        CommandRegistration r = registration(null);
        assertEquals(List.of("stable"), r.fixedAliases());
        assertEquals("pferdekoppel", r.lang().raw("de", "commands.paddock.aliases"));
        assertEquals("马场", r.lang().raw("zh_cn", "commands.paddock.aliases"));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() throws Exception {
        assertEquals(List.of(), registration(null).conflicts());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndEveryNodeDescribed() {
        Set<String> english = commandKeys("en");
        for (String code : List.of("de", "zh_cn")) assertEquals(english, commandKeys(code), code);
        CommandSpec spec = PaddockPlugin.commandSpec(null);
        for (String code : List.of("en", "de", "zh_cn")) {
            YamlConfiguration yaml = yaml(code);
            List<CommandSpec> nodes = new ArrayList<>(List.of(spec));
            while (!nodes.isEmpty()) {
                CommandSpec node = nodes.removeFirst();
                assertFalse(yaml.getString(node.langPath() + ".description", "").isBlank(), code + " " + node.langPath());
                nodes.addAll(node.children().values());
            }
        }
    }

    private static YamlConfiguration yaml(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(PaddockCommandsTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
    }

    private static Set<String> commandKeys(String code) {
        YamlConfiguration yaml = yaml(code);
        return new TreeSet<>(yaml.getConfigurationSection("commands").getKeys(true));
    }
}
