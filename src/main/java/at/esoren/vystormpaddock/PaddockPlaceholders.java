package at.esoren.vystormpaddock;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderContext;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderValue;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The values of Vystorm Paddock in Core's placeholder registry, namespace {@code paddock}. Stables are files, races
 * plain maps and animals live entities, so every value is answered on the server thread only; the stable count reads
 * a file and is kept for five seconds. A value of a switched-off feature is empty. Animal and player names are plain
 * text and never marked as formatted.
 *
 * <pre>
 * %vystorm_paddock_stable_slots%   {paddock:stable_slots}   stable slots of the player
 * %vystorm_paddock_stable_stored%  {paddock:stable_stored}  animals in the stable
 * %vystorm_paddock_mount_speed_level%, _mount_jump_level, _mount_owner   the animal the player rides
 * %vystorm_paddock_race_best_<course>% {paddock:race_best:<course>}   the player's best time on a course
 * race_rank_<course>, race_record_<course>, race_courses
 * </pre>
 */
final class PaddockPlaceholders {
    static final String NAMESPACE = "paddock";

    /** What the values read, at call time. */
    interface Source {
        boolean on(Feature feature);
        int slots(Player player);
        /** Animals in the stable, -1 when the file cannot be read. */
        int stored(UUID player);
        Claims claims();
        Races races();
    }

    private PaddockPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.player("stable_slots", describe.apply("placeholder.stable_slots"), player -> source.on(Feature.STABLE) ? source.slots(player) : "");
        ns.add(PlaceholderValue.viewer("stable_stored", viewer -> {
            if (!source.on(Feature.STABLE)) return "";
            int stored = source.stored(viewer.getUniqueId());
            return stored < 0 ? "" : stored;
        }).cacheTicks(100).description(describe.apply("placeholder.stable_stored")));
        ns.player("mount_speed_level", describe.apply("placeholder.mount_speed_level"), player -> {
            if (!source.on(Feature.TRAINING) || !(player.getVehicle() instanceof AbstractHorse horse)) return "";
            return AnimalTraining.read(horse).speedLevel();
        });
        ns.player("mount_jump_level", describe.apply("placeholder.mount_jump_level"), player -> {
            if (!source.on(Feature.TRAINING) || !(player.getVehicle() instanceof AbstractHorse horse)) return "";
            return AnimalTraining.read(horse).jumpLevel();
        });
        ns.player("mount_owner", describe.apply("placeholder.mount_owner"), player -> {
            if (!source.on(Feature.CLAIMS) || !(player.getVehicle() instanceof AbstractHorse horse) || source.claims() == null) return "";
            String name = source.claims().ownerName(horse);
            return name == null ? "" : name;
        });
        ns.resolver("race_best", describe.apply("placeholder.race_best"), "<course>", c -> {
            Races.Course course = course(source, c);
            if (course == null) return races(source) ? null : "";
            Races.Time time = c.viewerId() == null ? null : course.times().get(c.viewerId());
            return time == null ? "" : Races.time(time.millis());
        });
        ns.resolver("race_rank", describe.apply("placeholder.race_rank"), "<course>", c -> {
            Races.Course course = course(source, c);
            if (course == null) return races(source) ? null : "";
            return c.viewerId() == null ? 0 : Races.rank(course, c.viewerId());
        });
        ns.resolver("race_record", describe.apply("placeholder.race_record"), "<course>", c -> {
            Races.Course course = course(source, c);
            if (course == null) return races(source) ? null : "";
            List<Races.Time> top = Races.top(course, 1);
            return top.isEmpty() ? "" : Races.time(top.getFirst().millis());
        });
        ns.global("race_courses", describe.apply("placeholder.race_courses"), () -> races(source)
                ? source.races().courses().stream().filter(Races.Course::ready).count() : "");
        return ns;
    }

    private static boolean races(Source source) { return source.on(Feature.RACES) && source.races() != null; }

    /** The course named by the arguments (a name with underscores arrives in pieces), or null. */
    private static Races.Course course(Source source, PlaceholderContext c) {
        if (!races(source)) return null;
        String name = c.rest(0);
        return name == null || name.isBlank() ? null : source.races().get(name);
    }
}
