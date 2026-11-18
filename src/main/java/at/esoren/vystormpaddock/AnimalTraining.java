package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.compat.CompatInterface;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.*;
import org.bukkit.attribute.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.player.*;
import org.bukkit.persistence.PersistentDataType;

/** Two opt-in exercises sampled by the existing race clock; no extra scheduler or entity scans. */
final class AnimalTraining implements Listener {
    private static final NamespacedKey DATA = new NamespacedKey("vystormpaddock", "training_v1");
    private static final UUID SPEED = UUID.nameUUIDFromBytes("vystormpaddock:training_speed".getBytes(StandardCharsets.UTF_8));
    private static final UUID JUMP = UUID.nameUUIDFromBytes("vystormpaddock:training_jump".getBytes(StandardCharsets.UTF_8));
    private final PaddockPlugin plugin;
    private final CompatInterface compat;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private boolean enabled;
    private int levels, jumpTarget;
    private long rideTarget, cooldown;
    private double speedStep, jumpStep;
    private static final class Session {
        final AbstractHorse animal;
        final boolean ride;
        final long started;
        Location last, takeoff;
        double peak;
        boolean grounded;
        Session(AbstractHorse animal, boolean ride, long now) {
            this.animal = animal;
            this.ride = ride;
            started = now;
            last = animal.getLocation();
            grounded = animal.isOnGround();
        }
    }
    AnimalTraining(PaddockPlugin plugin, CompatInterface compat) {
        this.plugin = plugin;
        this.compat = compat;
    }
    void configure() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("training.enabled", true) && PaddockPlugin.enabled(plugin, Feature.CLAIMS);
        levels = Math.clamp(config.getInt("training.max-level", 5), 1, 10);
        rideTarget = Math.clamp(config.getLong("training.ride-metres", 300), 4, 10000) * 1000;
        jumpTarget = Math.clamp(config.getInt("training.jumps", 12), 1, 1000);
        cooldown = Math.clamp(config.getLong("training.cooldown-seconds", 60), 0, 86400) * 1000;
        speedStep = bounded(config.getDouble("training.speed-per-level", 0.005), 0.05 / levels);
        jumpStep = bounded(config.getDouble("training.jump-per-level", 0.02), 0.15 / levels);
        sessions.clear();
    }
    private static double bounded(double value, double max) {
        return Double.isFinite(value) ? Math.clamp(value, 0, max) : 0;
    }
    void close() {
        sessions.clear();
    }
    private boolean may(Player player, AbstractHorse horse) {
        return enabled && player.hasPermission("vystormpaddock.train") && player.getGameMode() == GameMode.SURVIVAL
                && plugin.trainingWorld(player.getWorld()) && !Claims.pet(horse) && horse.isAdult()
                && player.getUniqueId().equals(plugin.claims().owner(horse));
    }
    void command(Player player, String action) {
        if (!enabled) { plugin.lang().send(player, "feature-off"); return; }
        if ("stop".equals(action)) {
            sessions.remove(player.getUniqueId());
            plugin.lang().send(player, "training-stopped");
            return;
        }
        if (!(player.getVehicle() instanceof AbstractHorse horse) || !may(player, horse)) {
            plugin.lang().send(player, "training-requirements");
            return;
        }
        try {
            TrainingProgress data = read(horse);
            apply(horse, data);
            if (action == null || action.equals("status")) {
                plugin.lang().send(player, "training-status", "speed", data.speedLevel(), "jump", data.jumpLevel(), "max", levels, "ride", data.riding() / 1000, "rideGoal", rideTarget / 1000, "jumps", data.jumps(), "jumpGoal", jumpTarget, "cooldown", Math.max(0, (data.cooldownUntil() - System.currentTimeMillis() + 999) / 1000));
                return;
            }
            if (!action.equals("ride") && !action.equals("jump")) {
                plugin.lang().send(player, "training-usage");
                return;
            }
            boolean ride = action.equals("ride");
            if ((ride ? data.speedLevel() : data.jumpLevel()) >= levels) {
                plugin.lang().send(player, "training-max");
                return;
            }
            if (attribute(horse, ride) == null) {
                plugin.lang().send(player, "training-unavailable");
                return;
            }
            sessions.put(player.getUniqueId(), new Session(horse, ride, System.currentTimeMillis()));
            plugin.lang().send(player, ride ? "training-ride" : "training-jump", "target", ride ? rideTarget / 1000 : jumpTarget);
        } catch (RuntimeException invalid) {
            plugin.lang().send(player, "training-unavailable");
            plugin.getLogger().warning("Training data retained: " + invalid.getMessage());
        }
    }
    void tick(long now) {
        for (var entry : List.copyOf(sessions.entrySet())) {
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();
            AbstractHorse horse = session.animal;
            if (player == null || !player.isOnline() || player.getVehicle() != horse || !horse.isValid() || horse.isDead() || !may(player, horse) || now - session.started > 600000) {
                sessions.remove(entry.getKey());
                continue;
            }
            try {
                sample(player, session, now);
            } catch (RuntimeException failure) {
                sessions.remove(entry.getKey());
                plugin.lang().send(player, "training-unavailable");
                plugin.getLogger().warning("Training stopped; animal data retained: " + failure.getMessage());
            }
        }
    }
    private void sample(Player player, Session session, long now) {
        AbstractHorse horse = session.animal;
        Location at = horse.getLocation(), last = session.last;
        session.last = at;
        // Portals, teleports and implausible movement cannot earn exercise progress.
        if (at.getWorld() != last.getWorld() || at.distanceSquared(last) > 64) {
            sessions.remove(player.getUniqueId());
            return;
        }
        TrainingProgress previous = read(horse);
        long units = 0;
        if (session.ride && horse.isOnGround()) units = Math.round(Math.hypot(at.getX() - last.getX(), at.getZ() - last.getZ()) * 1000);
        if (!session.ride) {
            boolean ground = horse.isOnGround();
            if (session.grounded && !ground) {
                session.takeoff = last;
                session.peak = at.getY();
            }
            if (session.takeoff != null) session.peak = Math.max(session.peak, at.getY());
            if (!session.grounded && ground && session.takeoff != null) {
                Location from = session.takeoff;
                if (session.peak - from.getY() >= 0.6 && Math.hypot(at.getX() - from.getX(), at.getZ() - from.getZ()) >= 2 && Math.abs(at.getY() - from.getY()) <= 1) units = 1;
                session.takeoff = null;
            }
            session.grounded = ground;
        }
        TrainingProgress next = previous.add(session.ride, units, session.ride ? rideTarget : jumpTarget, levels, now, cooldown);
        if (next.equals(previous)) return;
        horse.getPersistentDataContainer().set(DATA, PersistentDataType.STRING, next.encode());
        apply(horse, next);
        if (next.speedLevel() != previous.speedLevel() || next.jumpLevel() != previous.jumpLevel()) {
            plugin.lang().send(player, "training-level", "speed", next.speedLevel(), "jump", next.jumpLevel(), "max", levels);
            if ((session.ride ? next.speedLevel() : next.jumpLevel()) >= levels) sessions.remove(player.getUniqueId());
        }
    }
    static TrainingProgress read(AbstractHorse horse) {
        return TrainingProgress.decode(horse.getPersistentDataContainer().get(DATA, PersistentDataType.STRING));
    }
    private AttributeInstance attribute(AbstractHorse horse, boolean ride) {
        return horse.getAttribute(ride ? compat.server().attribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED") : compat.server().attribute("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH"));
    }
    @SuppressWarnings("removal")
    private void apply(AbstractHorse horse, TrainingProgress data) {
        modifier(attribute(horse, true), SPEED, "vystormpaddock_training_speed", Math.min(levels, data.speedLevel()) * speedStep);
        modifier(attribute(horse, false), JUMP, "vystormpaddock_training_jump", Math.min(levels, data.jumpLevel()) * jumpStep);
    }
    @SuppressWarnings("removal")
    private void modifier(AttributeInstance attribute, UUID id, String name, double amount) {
        if (attribute == null) return;
        AttributeModifier old = compat.gameplay().getModifier(attribute, id);
        if (old != null && Double.compare(old.getAmount(), amount) == 0) return;
        compat.gameplay().removeModifier(attribute, id);
        if (amount > 0) attribute.addModifier(new AttributeModifier(id, name, amount, AttributeModifier.Operation.ADD_NUMBER));
    }
    /** Stage OBSERVE of Vystorm Core, registered in PaddockPlugin.handleSharedEvents; not called for a cancelled teleport. */
    public void teleported(PlayerTeleportEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(EntityTeleportEvent e) {
        sessions.values().removeIf(s -> s.animal.equals(e.getEntity()));
    }
    @EventHandler
    public void quit(PlayerQuitEvent e) {
        sessions.remove(e.getPlayer().getUniqueId());
    }
}
