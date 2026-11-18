package at.esoren.vystormpaddock;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Every feature of the plugin with its on/off key in config.yml. A missing key keeps the feature's default (on, except
 * the two features that shipped off), so a config written before a key existed behaves as before. The description in
 * {@code /vadmin features} is the language key {@code switches.<id>}. Keys that existed before 1.18.0 keep their names.
 */
enum Feature {
    /** Master switch: no claim protection, no menus, no races or training, commands except reload are off. */
    CLAIMS("claims.enabled", true),
    /** Older key that keeps its name; declared as a feature. */
    AUTO_CLAIM("auto-claim-on-tame", true),
    /** Older key that keeps its name; declared as a feature. Off by default. */
    ENVIRONMENT("protect-from-environment", false),
    TRUST("trust.enabled", true),
    STABLE("stable.enabled", true),
    STABLE_MENU("stable-menu.enabled", true),
    REST("stable-rest.enabled", true),
    /** Older key that keeps its name; declared as a feature. */
    MOUNT_ON_SUMMON("mount-on-summon", true),
    INFO("info.enabled", true),
    DEATH_NOTICES("death-notices.enabled", true),
    CALL("call.enabled", true),
    /** Older key that keeps its name; declared as a feature. Off by default. */
    CALL_ACROSS_WORLDS("call-across-worlds", false),
    RENAME("rename.enabled", true),
    BREEDING("breeding-records.enabled", true),
    /** Older key that keeps its name; declared as a feature. */
    CLAIM_FOALS("claim-foals", true),
    PETS("pets-protection.enabled", true),
    HERD("herd-book.enabled", true),
    RACES("races.enabled", true),
    COURSES("races.course-editor.enabled", true),
    /** Older key that keeps its name; declared as a feature. */
    RECORD_ANNOUNCEMENTS("races.announce-records", true),
    /** Older key that keeps its name; declared as a feature. */
    CHECKPOINTS("races.show-checkpoints", true),
    BETTING("betting.enabled", true),
    /** Older key that keeps its name; declared as a feature. */
    BET_LOG("betting.log", true),
    TRAINING("training.enabled", true),
    TRADE("trade.enabled", true),
    /** Read once when the plugin starts. */
    TUTORIAL("tutorial.enabled", true);

    final String path;
    final boolean defaultOn;

    Feature(String path, boolean defaultOn) { this.path = path; this.defaultOn = defaultOn; }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /** The switches as read from a config; a missing key keeps the feature's default. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            for (Feature feature : values()) {
                boolean on = config == null ? feature.defaultOn : config.getBoolean(feature.path, feature.defaultOn);
                if (!on) switches.off.add(feature);
            }
            return switches;
        }

        /** The defaults, with the given features switched off as well. */
        static Switches without(Feature... off) {
            Switches switches = read(null);
            switches.off.addAll(List.of(off));
            return switches;
        }

        /** On when its own switch and the master switch are on. */
        boolean on(Feature feature) { return !off.contains(feature) && (feature == CLAIMS || !off.contains(CLAIMS)); }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}
