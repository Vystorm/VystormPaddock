package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CompatibilityTest {
    private URLClassLoader legacy() throws Exception {
        return legacy(false);
    }

    private URLClassLoader legacy(boolean withoutPanels) throws Exception {
        var urls = new ArrayList<URL>();
        try (var jars = Files.walk(Path.of(System.getProperty("paper1206Libraries")))) {
            for (Path jar : jars.filter(p -> p.toString().endsWith(".jar")).toList()) urls.add(jar.toUri().toURL());
        }
        urls.add(Path.of(System.getProperty("vystormCoreJar")).toUri().toURL());
        urls.add(PaddockPlugin.class.getProtectionDomain().getCodeSource().getLocation());
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (withoutPanels && name.startsWith("Vystorm.vystorm_core.panel.")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test
    void givenCoreWithoutPanelClasses_whenPluginAndChestListenersLoad_thenOptionalPanelsDoNotBreakLinkage() throws Exception {
        try (var loader = legacy(true)) {
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("Vystorm.vystorm_core.panel.Panels"));
            for (String name : new String[]{"PaddockPlugin", "StableMenu", "StableMenu$Clicks", "HerdBook", "RaceTracker"}) {
                Class<?> type = loader.loadClass("at.esoren.vystormpaddock." + name);
                assertDoesNotThrow(() -> type.getDeclaredMethods());
                assertDoesNotThrow(() -> type.getDeclaredFields());
            }
        }
    }

    @Test
    void legacyAttributesSupportTheShippedHorseComparisonBytecode() throws Exception {
        try (var loader = legacy()) {
            Class<?> compat = loader.loadClass("Vystorm.vystorm_core.compat.ServerCompat");
            Class<?> attribute = loader.loadClass("org.bukkit.attribute.Attribute");
            assertTrue(attribute.isEnum());
            var lookup = compat.getMethod("attribute", String.class, String.class);
            for (String name : new String[]{"MAX_HEALTH", "MOVEMENT_SPEED", "JUMP_STRENGTH"})
                assertSame(attribute.getField("GENERIC_" + name).get(null), lookup.invoke(null, name, "GENERIC_" + name));
            assertNull(lookup.invoke(null, "MISSING", "ALSO_MISSING"));
            assertEquals(false, compat.getMethod("hasDialogs").invoke(null));

            Class<?> horse = loader.loadClass("org.bukkit.entity.Horse");
            Class<?> instance = loader.loadClass("org.bukkit.attribute.AttributeInstance");
            Object animal = Proxy.newProxyInstance(loader, new Class<?>[]{horse}, (proxy, method, args) -> {
                assertEquals("getAttribute", method.getName());
                String name = ((Enum<?>) args[0]).name();
                double value = switch (name) {
                    case "GENERIC_MOVEMENT_SPEED" -> 0.25;
                    case "GENERIC_JUMP_STRENGTH" -> 0.7;
                    case "GENERIC_MAX_HEALTH" -> 30;
                    default -> throw new AssertionError(name);
                };
                return Proxy.newProxyInstance(loader, new Class<?>[]{instance}, (p, m, a) -> value);
            });
            Class<?> stats = loader.loadClass("at.esoren.vystormpaddock.HorseComparison$Stats");
            Class<?> part = loader.loadClass("Vystorm.vystorm_core.compat.ServerCompatInterface");
            var read = stats.getDeclaredMethod("read", part, horse);
            read.setAccessible(true);
            Object result = read.invoke(null, part.getField("DEFAULT").get(null), animal);
            for (var entry : java.util.Map.of("speed", 0.25, "jump", 0.7, "maxHealth", 30.0).entrySet()) {
                var getter = stats.getDeclaredMethod(entry.getKey());
                getter.setAccessible(true);
                assertEquals(entry.getValue(), getter.invoke(result));
            }
        }
    }

    @Test
    void legacyInventoryAccessSelectsTheClassBasedFallback() throws Exception {
        try (var loader = legacy()) {
            Class<?> view = loader.loadClass("org.bukkit.inventory.InventoryView");
            assertFalse(view.isInterface());
            Object inventory = mock(loader.loadClass("org.bukkit.inventory.Inventory"));
            Object open = mock(view, invocation -> invocation.getMethod().getName().equals("getTopInventory") ? inventory : null);
            var top = loader.loadClass("Vystorm.vystorm_core.compat.InventoryCompat").getMethod("top", view);
            assertSame(inventory, top.invoke(null, open));
        }
    }

    @Test
    void unavailableAnimalsKeepASaddleIconOnLegacyPaper() throws Exception {
        try (var loader = legacy()) {
            var icon = loader.loadClass("at.esoren.vystormpaddock.StableMenu").getDeclaredMethod("icon", String.class);
            icon.setAccessible(true);
            assertEquals("SADDLE", ((Enum<?>) icon.invoke(null, "HAPPY_GHAST")).name());
            assertEquals("HORSE_SPAWN_EGG", ((Enum<?>) icon.invoke(null, "HORSE")).name());
        }
    }
}
