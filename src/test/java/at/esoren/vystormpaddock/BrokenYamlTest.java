package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml with a YAML error stays byte-identical; a reload keeps the last good settings and names the position. */
class BrokenYamlTest {
    private static final String BROKEN = "# my gallery\nmax-distance: 12\nthis line has no colon\nmode: fit\n";
    @TempDir Path dir;

    private PaddockPlugin plugin() throws Exception {
        PaddockPlugin plugin = mock(PaddockPlugin.class, CALLS_REAL_METHODS);
        File configFile = dir.resolve("config.yml").toFile();
        AtomicReference<FileConfiguration> config = new AtomicReference<>(new YamlConfiguration());
        doReturn(dir.toFile()).when(plugin).getDataFolder();
        doReturn(Logger.getLogger("BrokenYamlTest")).when(plugin).getLogger();
        doAnswer(call -> config.get()).when(plugin).getConfig();
        doAnswer(call -> { config.set(bukkitLoad(configFile)); return null; }).when(plugin).reloadConfig();
        doAnswer(call -> { config.get().save(configFile); return null; }).when(plugin).saveConfig();
        field(plugin, "lang", mock(Lang.class));
        return plugin;
    }

    /** Bukkit's YamlConfiguration.loadConfiguration without a server: a parse error only gives an empty config. */
    static YamlConfiguration bukkitLoad(File file) {
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file); } catch (Exception logged) { /* Bukkit logs it and returns what it has */ }
        return yaml;
    }

    @Test void givenABrokenConfig_whenTheStartReadsIt_thenTheFileIsByteIdentical() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        PaddockPlugin plugin = plugin();
        assertFalse(plugin.readConfig());
        verify(plugin, never()).saveConfig();
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
    }

    @Test void givenABrokenConfig_whenReloaded_thenTheLastGoodSettingsStayAndTheAnswerNamesLineAndColumn() throws Exception {
        Path config = Files.writeString(dir.resolve("config.yml"), BROKEN);
        PaddockPlugin plugin = plugin();
        field(plugin, "configLoaded", true);
        field(plugin, "reach", 12d);
        CommandSender admin = mock(CommandSender.class);
        when(admin.hasPermission(anyString())).thenReturn(true);
        plugin.reload(admin);
        assertArrayEquals(BROKEN.getBytes(), Files.readAllBytes(config));
        verify(plugin, never()).reloadConfig();
        Field distance = PaddockPlugin.class.getDeclaredField("reach");
        distance.setAccessible(true);
        assertEquals(12d, distance.get(plugin), "last good settings");
        Lang lang = (Lang) get(plugin, "lang");
        verify(lang).send(admin, "config-broken", "file", "config.yml", "problem", "line 4, column 1: could not find expected ':'");
        verify(lang, never()).send(eq(admin), eq("reloaded"));
    }

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = PaddockPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = PaddockPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
