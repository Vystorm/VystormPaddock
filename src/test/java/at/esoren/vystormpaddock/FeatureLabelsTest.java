package at.esoren.vystormpaddock;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * {@code /vadmin features} lists every boolean {@code enabled} key and every top-level boolean of config.yml, plus the
 * declared ones. Each of them must have a readable name and a description in all seven languages, so the screen never
 * falls back to a key path such as "Races › Announce records".
 */
class FeatureLabelsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    /** Core's housekeeping names that are never listed as features (ConfigEditorService.NOT_FEATURES). */
    private static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");

    private static boolean listedByCore(String path) {
        if (path.equals("enabled") || path.endsWith(".enabled")) return true;
        return path.indexOf('.') < 0 && !NOT_FEATURES.contains(path);
    }

    @Test void givenEveryBooleanCoreListsAsAFeature_whenChecked_thenItIsADeclaredSwitch() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        Set<String> declared = new HashSet<>();
        for (Feature feature : Feature.values()) declared.add(feature.path);
        for (String path : config.getKeys(true))
            if (config.isBoolean(path) && listedByCore(path)) assertTrue(declared.contains(path), "feature without a name: " + path);
    }

    @Test void givenTheRealDeclaration_whenCoreRecordsIt_thenEverySwitchCoreListsGetsANamedLabelAndDescription() {
        RecordingConfigEditor core = new RecordingConfigEditor();
        PaddockPlugin.declareSwitches(core, org.mockito.Mockito.mock(org.bukkit.plugin.Plugin.class), RecordingConfigEditor.lang("vystormpaddock"));
        core.assertEverySwitchNamed(new File("src/main/resources/config.yml"), new File("src/main/resources/lang"));
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchHasANameAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (Feature feature : Feature.values()) {
                assertFalse(lang.getString(feature.label(), "").isBlank(), code + " " + feature.label());
                assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            }
        }
    }
}
