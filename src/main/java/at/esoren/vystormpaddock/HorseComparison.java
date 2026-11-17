package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.CompatInterface;
import Vystorm.vystorm_core.compat.ServerCompatInterface;
import java.util.Locale;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Horse;
import org.bukkit.entity.Player;

/** Compare ordinary horses using the same attribute estimates as /paddock info. */
final class HorseComparison {
    static void show(CompatInterface compat, Player player, Lang lang, int reach) {
        if (!(player.getVehicle() instanceof Horse ridden)) { lang.send(player, "compare-ride"); return; }
        if (!(compat.gameplay().getTargetEntity(player, reach) instanceof Horse other) || other.equals(ridden)) {
            lang.send(player, "compare-target");
            return;
        }
        lang.send(player, "compare-header", "ridden", PaddockPlugin.name(ridden), "target", PaddockPlugin.name(other));
        Stats a = Stats.read(compat.server(), ridden), b = Stats.read(compat.server(), other);
        row(player, lang, "compare-speed", PaddockPlugin.blocksPerSecond(a.speed()), PaddockPlugin.blocksPerSecond(b.speed()));
        row(player, lang, "compare-jump", PaddockPlugin.jumpHeight(a.jump()), PaddockPlugin.jumpHeight(b.jump()));
        row(player, lang, "compare-health", a.maxHealth() / 2, b.maxHealth() / 2);
    }

    record Stats(double speed, double jump, double maxHealth) {
        static Stats read(ServerCompatInterface server, Horse horse) {
            return new Stats(value(horse, server.attribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED")), value(horse, server.attribute("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH")), value(horse, server.attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH")));
        }

        private static double value(Horse horse, Attribute attribute) {
            var instance = horse.getAttribute(attribute);
            return instance == null ? 0 : instance.getValue();
        }
    }

    private static void row(Player player, Lang lang, String key, double ridden, double target) {
        // Round before formatting to avoid a misleading negative zero for effectively equal stats.
        double difference = Math.round((target - ridden) * 10) / 10.0;
        lang.send(player, key, "ridden", String.format(Locale.ROOT, "%.1f", ridden),
                "target", String.format(Locale.ROOT, "%.1f", target), "delta", String.format(Locale.ROOT, "%+.1f", difference));
    }
}
