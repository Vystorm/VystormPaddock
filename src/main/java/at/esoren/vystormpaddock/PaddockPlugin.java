package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityMountEvent;
import org.bukkit.event.entity.EntityTameEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Vystorm Paddock: claim horses, donkeys, mules, llamas, camels and undead horses. Claimed animals can only be ridden,
 * hurt, leashed or opened by their owner and trusted players. Owners store them in a virtual stable and summon them
 * anywhere; ownership travels with the animal.
 */
public final class PaddockPlugin extends JavaPlugin implements Listener {

    /** The Core hub; set first thing in onEnable. */
    private API core;
    private Lang lang;
    private Claims claims;
    private Stable stable;
    private HerdBook herdBook;
    private RaceTracker raceTracker;
    private PaddockPanels panels;
    private AnimalTraining training;
    private boolean autoClaim = true;
    private boolean protectEnvironment = false;
    private int defaultSlots = 3;
    private long summonCooldownMillis = 10_000;
    private long stableRestMillis = 600_000;
    private double reach = 6;
    private Set<String> disabledWorlds = Set.of();
    private final Map<UUID, Long> lastSummon = new HashMap<>();
    private final Map<UUID, Long> lastWarning = new HashMap<>();
    private final Map<UUID, UUID> lastRidden = new HashMap<>();
    private boolean mountOnSummon = true, callAcrossWorlds = false, claimFoals = true;
    /** Minimum Vystorm Core: placeholders through api.placeholders(). */
    static final String MIN_CORE = "0.65.0";
    /** Every feature switch as read in load(); null (= defaults) until then and in tests that build the plugin by hand. */
    private Feature.Switches switches;

    /** Whether a feature (and claims.enabled) is on; static so helpers and mocked plugins in tests read the real field. */
    static boolean enabled(PaddockPlugin plugin, Feature feature) {
        Feature.Switches current = plugin == null || plugin.switches == null ? Feature.Switches.without() : plugin.switches;
        return current.on(feature);
    }

    private boolean on(Feature feature) { return enabled(this, feature); }

    /** True (and the shared off message sent) when one of the features is switched off. */
    private boolean off(CommandSender sender, Feature... features) {
        for (Feature feature : features) {
            if (on(feature)) continue;
            lang.send(sender, "feature-off");
            return true;
        }
        return false;
    }

    /** Animal types Vystorm Pets takes over on taming; with Pets installed Paddock leaves them to Pets. */
    private static final Set<org.bukkit.entity.EntityType> PET_TYPES = Set.of(org.bukkit.entity.EntityType.HORSE, org.bukkit.entity.EntityType.DONKEY,
            org.bukkit.entity.EntityType.LLAMA, org.bukkit.entity.EntityType.CAMEL);

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast(MIN_CORE)) {
            getLogger().severe("Vystorm Core " + MIN_CORE + " or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();

        core.admin().updates().spigot(this, 140050);
        saveDefaultConfig();
        lang = core.lang().register(this);
        registerTutorial();
        claims = new Claims(this);
        stable = new Stable(getDataFolder().toPath().resolve("stables"), getLogger(), core.data().files());
        herdBook = new HerdBook(this, core, new Herd(getDataFolder().toPath().resolve("herd"), core.data().files()));
        training = new AnimalTraining(this, core.compat());
        raceTracker = new RaceTracker(this, core, new Races(getDataFolder().toPath().resolve("races.yml"), core.data().files()));
        load();
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new StableMenu.Clicks(this, core.compat().inventory()), this);
        getServer().getPluginManager().registerEvents(herdBook, this);
        getServer().getPluginManager().registerEvents(raceTracker, this);
        getServer().getPluginManager().registerEvents(training, this);
        handleSharedEvents(core.priority().stages());
        herdBook.start();
        raceTracker.start();
        registerMenus();
        core.settings().config().register(this, "config.yml", this::load);
        declareSwitches();
        try { registerPlaceholders(core.placeholders()); }
        catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Placeholders not registered", failure); }
        registerCommands();
    }

    /** The tutorial in /vmenu unless tutorial.enabled is false; read once, because Core lists tutorials when a plugin starts. */
    void registerTutorial() {
        if (!getConfig().getBoolean(Feature.TUTORIAL.path, true)) return;
        try { core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
    }

    /** Every switch in /vadmin features with its one-line description in the admin's language. */
    private void declareSwitches() {
        var config = core.settings().config();
        for (Feature feature : Feature.values()) {
            config.feature(this, feature.path, null, lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(this, feature.path, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        }
    }

    /** Registers /paddock once; the subcommands of switched-off features stay out of help and tab completion (asked live). */
    private void registerCommands() {
        core.command().register(this, lang, commandSpec(this));
    }

    /** The namespace {@code paddock}: stable, ridden animal and races (server thread; empty while switched off). */
    void registerPlaceholders(Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface registry) {
        PaddockPlaceholders.register(registry, this, new PaddockPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return PaddockPlugin.this.on(feature); }
            @Override public int slots(Player player) { return PaddockPlugin.this.slots(player); }
            @Override public int stored(UUID player) {
                try { return stable == null ? 0 : stable.list(player).size(); } catch (IOException unreadable) { return -1; }
            }
            @Override public Claims claims() { return claims; }
            @Override public Races races() { return raceTracker == null ? null : raceTracker.races(); }
        }, key -> lang == null ? key : lang.ref(key));
    }

    private void load() {
        reloadConfig();
        Feature.Switches before = switches;
        switches = Feature.Switches.read(getConfig());
        if (lang != null) lang.reload();
        autoClaim = on(Feature.AUTO_CLAIM);
        protectEnvironment = on(Feature.ENVIRONMENT);
        defaultSlots = Math.max(0, getConfig().getInt("stable-slots", 3));
        summonCooldownMillis = Math.max(0, getConfig().getInt("summon-cooldown-seconds", 10)) * 1000L;
        // stable-rest.enabled off: no healing, whatever stable-rest-minutes says
        stableRestMillis = restMillis(getConfig().getInt("stable-rest-minutes", 10), on(Feature.REST));
        reach = Math.clamp(getConfig().getDouble("reach", 6), 2, 32);
        mountOnSummon = on(Feature.MOUNT_ON_SUMMON);
        callAcrossWorlds = on(Feature.CALL_ACROSS_WORLDS);
        claimFoals = on(Feature.CLAIM_FOALS);
        claims.switches(on(Feature.TRUST), on(Feature.PETS));
        Set<String> worlds = new LinkedHashSet<>();
        for (String world : getConfig().getStringList("disabled-worlds")) worlds.add(world.toLowerCase(Locale.ROOT));
        disabledWorlds = Set.copyOf(worlds);
        training.configure();
        herdBook.configure(on(Feature.HERD), getConfig().getInt("herd-book.track-seconds", 120));
        raceTracker.configure(on(Feature.RACES), getConfig().getInt("races.max-minutes", 10),
                getConfig().getDouble("races.default-radius", 4), on(Feature.RECORD_ANNOUNCEMENTS), on(Feature.CHECKPOINTS));
        // help and tab completion ask the switches live; only send online players their command list again
        if (before != null && !before.equals(switches)) {
            try { core.command().refresh(); }
            catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Command list not sent again", failure); }
        }
    }

    @Override
    public void onDisable() {
        if (panels != null) { panels.close(); panels = null; }
        betsRefundAll();
        if (herdBook != null) {
            // where the animals stand right now, so the herd book is current after a restart
            for (org.bukkit.World world : Bukkit.getWorlds())
                for (AbstractHorse horse : world.getEntitiesByClass(AbstractHorse.class)) herdBook.seen(horse);
            herdBook.stop();
        }
        if (raceTracker != null) raceTracker.stop();
        if (training != null) training.close();
    }

    /** Herd book and races in the Vystorm Core main menu (/vmenu): panels, or chest menu entries if the panels cannot be registered. */
    private void registerMenus() {
        try {
            panels = new PaddockPanels(this, core.gui().panels(), stable, herdBook, raceTracker);
            return;
        } catch (RuntimeException failure) {
            getLogger().fine("Panels unavailable, using chest menus: " + failure);
        }
        try {
            var gui = core.gui();
            gui.registerMenu(this, "herd", lang.ref("vmenu-herd"), org.bukkit.Material.LEAD, p -> p.hasPermission("vystormpaddock.use") && herdBook.enabled(),
                    herdBook::open, Vystorm.vystorm_core._uiMethods.menu.MenuCategory.CHARACTER.group(), lang.ref("vmenu-herd-desc"));
            gui.registerMenu(this, "races", lang.ref("vmenu-races"), org.bukkit.Material.GOLDEN_HORSE_ARMOR, p -> p.hasPermission("vystormpaddock.race") && raceTracker.enabled(),
                    raceTracker::open, Vystorm.vystorm_core._uiMethods.menu.MenuCategory.COMMUNITY.group(), lang.ref("vmenu-races-desc"));
        } catch (RuntimeException failure) {
            getLogger().fine("Main menu entries not available: " + failure);
        }
    }

    Lang lang() { return lang; }

    boolean openPanel(Player player, String name) {
        return panels != null && panels.open(player, name);
    }

    @EventHandler
    public void forgetPanelState(org.bukkit.event.player.PlayerQuitEvent event) {
        if (panels != null) panels.forget(event.getPlayer().getUniqueId());
    }

    Claims claims() { return claims; }
    AnimalTraining training() { return training; }
    boolean trainingWorld(org.bukkit.World world) { return !disabledWorlds.contains(world.getName().toLowerCase(Locale.ROOT)); }

    // ------------------------------------------------------------------ protection

    /**
     * The events shared with other Vystorm plugins go through Core's stages, so Core decides when they run: a claim
     * denies first (PROTECT), so no feature of another plugin acts on an animal the player may not touch; what only
     * follows the outcome (last ridden animal, death notice, training sessions and races ended by a teleport) runs in
     * OBSERVE. All of them are skipped while the event is cancelled, except the death notice, which never was.
     */
    void handleSharedEvents(EventStages stages) {
        stages.handle(this, EntityDamageByEntityEvent.class, EventStage.PROTECT, this::onDamage);
        stages.handle(this, EntityDamageEvent.class, EventStage.PROTECT, this::onEnvironment);
        stages.handle(this, EntityMountEvent.class, EventStage.PROTECT, this::onMount);
        stages.handle(this, EntityMountEvent.class, EventStage.OBSERVE, this::onMounted);
        stages.handle(this, PlayerInteractEntityEvent.class, EventStage.PROTECT, this::onInteract);
        stages.handle(this, PlayerLeashEntityEvent.class, EventStage.PROTECT, this::onLeash);
        stages.handle(this, EntityDeathEvent.class, EventStage.OBSERVE, true, this::onDeath);
        stages.handle(this, PlayerTeleportEvent.class, EventStage.OBSERVE, training::teleported);
        stages.handle(this, PlayerTeleportEvent.class, EventStage.OBSERVE, raceTracker::onTeleport);
    }

    private static Player playerBehind(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) return shooter;
        return null;
    }

    private void deny(Player player, Entity animal) {
        long now = System.currentTimeMillis();
        Long last = lastWarning.get(player.getUniqueId());
        if (last != null && now - last < 2000) return;
        lastWarning.put(player.getUniqueId(), now);
        core.compat().platform().sendActionBar(player, lang.component(player, "not-yours", "owner", claims.ownerName(animal)));
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!on(Feature.CLAIMS)) return; // claims.enabled off: claimed animals are not protected
        if (!(event.getEntity() instanceof AbstractHorse horse) || !claims.claimed(horse)) return;
        Player attacker = playerBehind(event.getDamager());
        if (attacker != null && !claims.allowed(horse, attacker)) {
            event.setCancelled(true);
            deny(attacker, horse);
        }
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onEnvironment(EntityDamageEvent event) {
        if (!protectEnvironment || event instanceof EntityDamageByEntityEvent) return;
        if (!(event.getEntity() instanceof AbstractHorse horse) || !claims.claimed(horse)) return;
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.VOID || cause == EntityDamageEvent.DamageCause.KILL) return;
        event.setCancelled(true);
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onMount(EntityMountEvent event) {
        if (!on(Feature.CLAIMS)) return;
        if (!(event.getMount() instanceof AbstractHorse horse) || !(event.getEntity() instanceof Player player)) return;
        if (!claims.allowed(horse, player)) {
            event.setCancelled(true);
            deny(player, horse);
        }
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents}: the owner really sits on the animal, it is the last ridden one. */
    public void onMounted(EntityMountEvent event) {
        if (!(event.getMount() instanceof AbstractHorse horse) || !(event.getEntity() instanceof Player player)) return;
        if (player.getUniqueId().equals(claims.owner(horse))) lastRidden.put(player.getUniqueId(), horse.getUniqueId());
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onInteract(PlayerInteractEntityEvent event) {
        if (!on(Feature.CLAIMS)) return;
        if (!(event.getRightClicked() instanceof AbstractHorse horse) || claims.allowed(horse, event.getPlayer())) return;
        event.setCancelled(true);
        deny(event.getPlayer(), horse);
    }

    /** Stage PROTECT, registered in {@link #handleSharedEvents}. */
    public void onLeash(PlayerLeashEntityEvent event) {
        if (!on(Feature.CLAIMS)) return;
        if (!(event.getEntity() instanceof AbstractHorse horse) || claims.allowed(horse, event.getPlayer())) return;
        event.setCancelled(true);
        deny(event.getPlayer(), horse);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTame(EntityTameEvent event) {
        if (!autoClaim || !(event.getEntity() instanceof AbstractHorse horse) || !(event.getOwner() instanceof Player player)) return;
        if (!player.hasPermission("vystormpaddock.use") || claims.claimed(horse) || petsHandle(horse)) return;
        claims.claim(horse, player);
        core.compat().platform().sendMessage(player, lang.component(player, "claimed", "name", name(horse)));
        herdBook.seen(horse);
    }

    /** Stage OBSERVE, registered in {@link #handleSharedEvents} for cancelled deaths too (as before). */
    public void onDeath(EntityDeathEvent event) {
        if (!(event.getEntity() instanceof AbstractHorse horse) || Claims.pet(horse)) return;
        UUID owner = claims.owner(horse);
        herdBook.forget(owner, horse.getUniqueId());
        if (!on(Feature.DEATH_NOTICES)) return; // death-notices.enabled off: no message
        Player player = owner == null ? null : Bukkit.getPlayer(owner);
        if (player == null) return;
        Location at = horse.getLocation();
        Player killer = horse.getKiller();
        if (killer != null && !killer.equals(player))
            lang.send(player, "died-killed", "name", name(horse), "player", killer.getName(), "place", coords(at), "world", at.getWorld().getName());
        else lang.send(player, "died-at", "name", name(horse), "place", coords(at), "world", at.getWorld().getName());
    }

    /** Healing time in the stable: stable-rest-minutes, or none while stable-rest.enabled is off. */
    static long restMillis(int minutes, boolean on) { return on ? Math.max(0, minutes) * 60_000L : 0; }

    /** Block coordinates "x y z" for death notices. */
    static String coords(Location at) { return at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ(); }

    // ------------------------------------------------------------------ helpers

    static String type(Entity entity) {
        String raw = entity.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(raw.charAt(0)) + raw.substring(1);
    }

    static String name(Entity entity) {
        String custom = entity.getCustomName() == null ? null : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Vystorm.vystorm_core.compat.PlatformCompat.customName(entity));
        return custom != null && !custom.isBlank() ? custom : type(entity);
    }

    /** Jump strength attribute → jump height in blocks (well-known fit of vanilla horse physics). */
    static double jumpHeight(double strength) {
        return -0.1817584952 * Math.pow(strength, 3) + 3.689713992 * strength * strength + 2.128599134 * strength - 0.343930367;
    }

    /** Movement speed attribute → blocks per second while ridden. */
    static double blocksPerSecond(double speed) { return speed * 42.16; }

    /**
     * Health after resting in the stable: the animal heals evenly while stabled, fully after {@code restMillis}
     * (0 = no healing). Never above {@code max}, never below the stored health.
     */
    static double restedHealth(double health, double max, long storedMillis, long restMillis) {
        if (restMillis <= 0 || max <= 0 || storedMillis <= 0 || health >= max) return health;
        double share = Math.min(1.0, storedMillis / (double) restMillis);
        return Math.min(max, health + (max - health) * share);
    }

    private static double attribute(LivingEntity entity, Attribute attribute) {
        AttributeInstance instance = entity.getAttribute(attribute);
        return instance == null ? 0 : instance.getValue();
    }

    private AbstractHorse target(Player player) {
        if (player.getVehicle() instanceof AbstractHorse riding) return riding;
        Entity looked = core.compat().gameplay().getTargetEntity(player, (int) Math.ceil(reach));
        return looked instanceof AbstractHorse horse ? horse : null;
    }

    int slots(Player player) {
        List<String> granted = new ArrayList<>();
        for (PermissionAttachmentInfo info : player.getEffectivePermissions())
            if (info.getValue() && info.getPermission().startsWith("vystormpaddock.slots.")) granted.add(info.getPermission());
        return Stable.slots(granted, defaultSlots);
    }

    private String stats(AbstractHorse horse) {
        return String.format(Locale.ROOT, "%.0f/%.0f ❤, %.1f blocks/s, jump %.1f", horse.getHealth() / 2, attribute(horse, core.compat().server().attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH")) / 2,
                blocksPerSecond(attribute(horse, core.compat().server().attribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"))), jumpHeight(attribute(horse, core.compat().server().attribute("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH"))));
    }

    /** Vystorm Pets is installed and takes over this animal type on taming. */
    private boolean petsHandle(Entity entity) {
        return PET_TYPES.contains(entity.getType()) && Bukkit.getPluginManager().isPluginEnabled("VystormPets");
    }

    /**
     * Breeding score 0..100: speed, jump and health relative to the vanilla maximum (0.3375, 1.0, 30), weighted
     * equally. A wild average horse lands around 50.
     */
    static int rating(double speed, double jump, double health) {
        double s = Math.clamp((speed - 0.1125) / (0.3375 - 0.1125), 0, 1);
        double j = Math.clamp((jump - 0.4) / (1.0 - 0.4), 0, 1);
        double h = Math.clamp((health - 15) / (30 - 15), 0, 1);
        return (int) Math.round((s + j + h) / 3 * 100);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(org.bukkit.event.entity.EntityBreedEvent event) {
        if (!(event.getEntity() instanceof AbstractHorse foal) || !(event.getFather() instanceof AbstractHorse sire) || !(event.getMother() instanceof AbstractHorse dam)) return;
        if (on(Feature.BREEDING)) claims.pedigree(foal, name(sire), name(dam), Math.max(claims.generation(sire), claims.generation(dam)));
        if (!claimFoals || !(event.getBreeder() instanceof Player breeder) || petsHandle(foal)) return;
        if (breeder.getUniqueId().equals(claims.owner(sire)) && breeder.getUniqueId().equals(claims.owner(dam))) {
            claims.claim(foal, breeder);
            herdBook.seen(foal);
            int score = rating(attribute(foal, core.compat().server().attribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED")), attribute(foal, core.compat().server().attribute("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH")), attribute(foal, core.compat().server().attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH")));
            lang.send(breeder, "foal", "sire", name(sire), "dam", name(dam), "rating", Integer.toString(score));
        }
    }

    void rename(Player player, String[] args) {
        if (off(player, Feature.RENAME)) return;
        if (args.length < 2) { lang.send(player, "usage-rename"); return; }
        AbstractHorse horse = ownTarget(player);
        if (horse == null) return;
        if (Claims.pet(horse)) { lang.send(player, "pet-managed"); return; }
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)).trim();
        if (name.length() > 32) name = name.substring(0, 32);
        core.compat().platform().customName(horse, net.kyori.adventure.text.Component.text(name));
        horse.setCustomNameVisible(false);
        herdBook.seen(horse);
        lang.send(player, "renamed", "name", name);
    }

    /** Whistle: the animal you last rode comes to you if it is loaded (same world unless configured). */
    void call(Player player) {
        if (off(player, Feature.CALL)) return;
        UUID id = lastRidden.get(player.getUniqueId());
        Entity entity = id == null ? null : Bukkit.getEntity(id);
        if (!(entity instanceof AbstractHorse horse) || !horse.isValid() || !player.getUniqueId().equals(claims.owner(horse))) { lang.send(player, "call-not-found"); return; }
        callAnimal(player, horse);
    }

    /** Brings an owned, loaded animal to the player (/paddock call and the herd book). */
    void callAnimal(Player player, AbstractHorse horse) {
        if (off(player, Feature.CALL)) return;
        if (Claims.pet(horse)) { lang.send(player, "pet-managed"); return; }
        if (!callAcrossWorlds && !horse.getWorld().equals(player.getWorld())) { lang.send(player, "call-other-world"); return; }
        if (disabledWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) { lang.send(player, "world-disabled"); return; }
        long now = System.currentTimeMillis();
        Long last = lastSummon.get(player.getUniqueId());
        if (last != null && now - last < summonCooldownMillis && !player.hasPermission("vystormpaddock.bypass")) {
            lang.send(player, "cooldown", "seconds", Long.toString((summonCooldownMillis - (now - last) + 999) / 1000));
            return;
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_GOAT_HORN_SOUND_0, 0.8f, 1.4f);
        Location at = player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(1.5));
        at.setY(player.getLocation().getY());
        horse.eject();
        horse.setLeashHolder(null);
        horse.teleport(at);
        lastSummon.put(player.getUniqueId(), now);
        herdBook.seen(horse);
        lang.send(player, "called", "name", name(horse));
    }

    void openStable(Player player) {
        openStable(player, "", 0);
    }

    void openStable(Player player, String search, int page) {
        openStable(player, search, page, StablePage.Sort.OLDEST);
    }

    void openStable(Player player, String search, int page, StablePage.Sort sort) {
        if (off(player, Feature.STABLE, Feature.STABLE_MENU)) return;
        if (!player.hasPermission("vystormpaddock.use")) { lang.send(player, "denied"); return; }
        if (panels != null && panels.openStable(player, search, page, sort)) return;
        openStableChest(player, search, page, sort);
    }

    void openStableChest(Player player, String search, int page, StablePage.Sort sort) {
        if (!player.hasPermission("vystormpaddock.use")) { lang.send(player, "denied"); return; }
        try {
            player.openInventory(StableMenu.build(core.compat().platform(), lang, player, stable.list(player.getUniqueId()), slots(player), search, page, sort));
        } catch (IOException failure) {
            getLogger().log(Level.WARNING, "Could not read stable of " + player.getName(), failure);
            lang.send(player, "failed");
        }
    }

    /** Called by the stable menu. */
    void summonNumber(Player player, int number) {
        player.closeInventory();
        summon(player, new String[]{"summon", Integer.toString(number)});
    }

    void summonId(Player player, String id) {
        player.closeInventory();
        summon(player, new String[]{"summon"}, id);
    }

    /** /paddock herd: the herd book menu; /paddock herd stop ends the way-finder. */
    void herd(Player player, String[] args) {
        if (off(player, Feature.HERD)) return;
        if (args.length >= 2 && args[1].equalsIgnoreCase("stop")) {
            lang.send(player, herdBook.stopTracking(player) ? "herd-track-stopped" : "herd-not-tracking");
            return;
        }
        herdBook.open(player);
    }

    // ------------------------------------------------------------------ commands

    /**
     * {@code /paddock} as a Vystorm Core command tree: names, aliases, usage and help come from
     * {@code commands.paddock.*} in the language files ({@code /pferdekoppel rufen}, {@code /马场 召唤}); English always
     * works. {@code p} may be {@code null} in tests that only inspect names and help.
     */
    static CommandSpec commandSpec(PaddockPlugin p) {
        return CommandSpec.command("paddock")
                .aliases("stable")
                .executes(ctx -> { if (ctx.sender() instanceof org.bukkit.entity.Player player && player.hasPermission("vystormpaddock.use")) p.openStable(player); else ctx.sender().sendMessage("/paddock help"); })
                .sub("claim", s -> gate(s, p, Feature.CLAIMS).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.claim(ctx.player())))
                .sub("release", s -> gate(s, p, Feature.CLAIMS).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.release(ctx.player())))
                .sub("offer", s -> gate(s, p, Feature.TRADE).permission("vystormpaddock.trade").playerOnly().arg(Arg.playerName("player")).arg(Arg.word("price").optional())
                        .executes(ctx -> p.offer(ctx.player(), ctx.get("player"), ctx.get("price"))))
                .sub("accept", s -> gate(s, p, Feature.TRADE).permission("vystormpaddock.trade").playerOnly().executes(ctx -> p.accept(ctx.player())))
                .sub("decline", s -> gate(s, p, Feature.TRADE).permission("vystormpaddock.trade").playerOnly().executes(ctx -> p.decline(ctx.player())))
                .sub("trust", s -> gate(s, p, Feature.TRUST).permission("vystormpaddock.use").playerOnly().arg(Arg.playerName("player"))
                        .executes(ctx -> p.trust(ctx.player(), new String[]{"trust", ctx.get("player")}, true)))
                .sub("untrust", s -> gate(s, p, Feature.TRUST).permission("vystormpaddock.use").playerOnly().arg(Arg.playerName("player"))
                        .executes(ctx -> p.trust(ctx.player(), new String[]{"untrust", ctx.get("player")}, false)))
                .sub("info", s -> gate(s, p, Feature.INFO).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.info(ctx.player())))
                .sub("train",s->gate(s, p, Feature.TRAINING).permission("vystormpaddock.train").playerOnly().arg(Arg.word("exercise").optional().suggests((ctx,prefix)->List.of(ctx.valueName("ride"),ctx.valueName("jump"),ctx.valueName("status"),ctx.valueName("stop"))))
                        .executes(ctx->{String value=ctx.get("exercise");String translated=value==null?null:ctx.keyword(value,"ride","jump","status","stop");p.training.command(ctx.player(),translated==null?value:translated);}))
                .sub("compare", s -> gate(s, p, Feature.INFO).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.compare(ctx.player())))
                .sub("rename", s -> gate(s, p, Feature.RENAME).permission("vystormpaddock.use").playerOnly().arg(Arg.greedy("name"))
                        .executes(ctx -> p.rename(ctx.player(), new String[]{"rename", ctx.get("name")})))
                .sub("store", s -> gate(s, p, Feature.STABLE).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.store(ctx.player())))
                .sub("list", s -> gate(s, p, Feature.STABLE).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.list(ctx.player())))
                .sub("stable", s -> gate(gate(s, p, Feature.STABLE), p, Feature.STABLE_MENU).permission("vystormpaddock.use").playerOnly().arg(Arg.greedy("search").optional())
                        .executes(ctx -> {
                            if (ctx.get("search") == null) p.openStable(ctx.player());
                            else p.openStable(ctx.player(), ctx.get("search"), 0);
                        }))
                .sub("summon", s -> gate(s, p, Feature.STABLE).permission("vystormpaddock.use").playerOnly()
                        .arg(Arg.greedy("animal").optional().suggests((ctx, prefix) -> p.stableNames(ctx.player(), prefix)))
                        .executes(ctx -> {
                            String animal = ctx.get("animal");
                            p.summon(ctx.player(), animal == null ? new String[]{"summon"} : new String[]{"summon", animal});
                        }))
                .sub("call", s -> gate(s, p, Feature.CALL).permission("vystormpaddock.use").playerOnly().executes(ctx -> p.call(ctx.player())))
                .sub("herd", s -> gate(s, p, Feature.HERD).permission("vystormpaddock.use").playerOnly()
                        .arg(Arg.greedy("stop").optional().suggests((ctx, prefix) -> List.of(ctx.valueName("stop"))))
                        .executes(ctx -> p.herd(ctx.player(), ctx.get("stop") != null && ctx.keyword(ctx.get("stop"), "stop") != null)))
                .sub("bet", s -> gate(s, p, Feature.BETTING).permission("vystormpaddock.bet").playerOnly()
                        .arg(Arg.playerName("rider").optional()).arg(Arg.word("side").optional().suggests(List.of("beat", "miss"))).arg(Arg.word("amount").optional().suggests((ctx, prefix) -> betAmounts(p.getConfig().getDouble("betting.min", 10), p.getConfig().getDouble("betting.max", 1000), prefix)))
                        .executes(ctx -> p.bet(ctx.player(), ctx.get("rider"), ctx.get("side"), ctx.get("amount"))))
                .sub("race", s -> gate(s, p, Feature.RACES).permission("vystormpaddock.use").playerOnly()
                        .arg(Arg.greedy("course").optional().suggests((ctx, prefix) -> {
                            List<String> out = new ArrayList<>(p.courseNames(prefix, true));
                            out.add(ctx.valueName("stop"));
                            return out;
                        }))
                        .executes(ctx -> p.race(ctx.player(), ctx.get("course"), ctx.keyword(ctx.get("course"), "stop") != null)))
                .sub("course", s -> gate(gate(s, p, Feature.RACES), p, Feature.COURSES).permission("vystormpaddock.course").playerOnly()
                        .sub("create", c -> c.arg(Arg.word("course")).executes(ctx -> p.course(ctx.player(), "create", ctx.get("course"), null)))
                        .sub("point", c -> c.arg(courseArg(p)).executes(ctx -> p.course(ctx.player(), "point", ctx.get("course"), null)))
                        .sub("undo", c -> c.arg(courseArg(p)).executes(ctx -> p.course(ctx.player(), "undo", ctx.get("course"), null)))
                        .sub("radius", c -> c.arg(courseArg(p)).arg(Arg.word("blocks").optional().suggests(List.of("2", "4", "6", "8")))
                                .executes(ctx -> p.course(ctx.player(), "radius", ctx.get("course"), ctx.get("blocks"))))
                        .sub("reset", c -> c.arg(courseArg(p)).executes(ctx -> p.course(ctx.player(), "reset", ctx.get("course"), null)))
                        .sub("delete", c -> c.arg(courseArg(p)).executes(ctx -> p.course(ctx.player(), "delete", ctx.get("course"), null))))
                .sub("reload", s -> s.permission("vystormpaddock.admin").executes(ctx -> p.reload(ctx.sender())))
                .build();
    }

    /**
     * Keeps a subcommand out of help and tab completion while its switch (or claims.enabled) is off, asked at every
     * completion so a switch applies at once; it still answers the off message.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder sub, PaddockPlugin p, Feature feature) {
        return p == null ? sub : sub.visibleWhen(() -> enabled(p, feature));
    }

    /** Tab completion for the stake of {@code /paddock bet}: the configured minimum and maximum, as the bet check applies them. */
    static List<String> betAmounts(double configMin, double configMax, String prefix) {
        double min = Math.max(0.01, configMin), max = Math.max(min, configMax);
        List<String> out = new ArrayList<>();
        for (double value : new double[]{min, max}) {
            String text = value == Math.rint(value) && Math.abs(value) < 1e12 ? Long.toString((long) value) : String.format(java.util.Locale.ROOT, "%.2f", value);
            if (!out.contains(text) && text.startsWith(prefix == null ? "" : prefix)) out.add(text);
        }
        return out;
    }

    private static Arg<String> courseArg(PaddockPlugin p) {
        return Arg.word("course").suggests((ctx, prefix) -> p.courseNames(prefix, false));
    }

    void reload(CommandSender sender) {
        load();
        lang.send(sender, "reloaded");
    }

    /** Stable animals for tab completion. */
    List<String> stableNames(Player player, String prefix) {
        try { return Stable.completions(stable.list(player.getUniqueId()), prefix); }
        catch (IOException failure) { return List.of(); }
    }

    List<String> courseNames(String prefix, boolean onlyReady) { return raceTracker.courseNames(prefix, onlyReady); }

    /** {@code /paddock herd [stop]}: anything but the stop keyword opens the herd book, as before. */
    void herd(Player player, boolean stop) { herd(player, stop ? new String[]{"herd", "stop"} : new String[]{"herd"}); }

    /**
     * {@code /paddock race [COURSE|stop]}. English {@code stop} always stops; a localized stop word ({@code stopp}) stops
     * unless a course has exactly that name.
     */
    void race(Player player, String course, boolean stopKeyword) {
        if (course == null) { raceTracker.race(player, new String[]{"race"}); return; }
        boolean stop = stopKeyword && (course.equalsIgnoreCase("stop") || raceTracker.courseNames(course, false).stream().noneMatch(course::equalsIgnoreCase));
        raceTracker.race(player, new String[]{"race", stop ? "stop" : course});
    }

    /** {@code /paddock course <action> <course> [blocks]}; the use permission is still required, as before. */
    void course(Player player, String action, String course, String blocks) {
        if (!player.hasPermission("vystormpaddock.use")) { lang.send(player, "denied"); return; }
        raceTracker.course(player, blocks == null ? new String[]{"course", action, course} : new String[]{"course", action, course, blocks});
    }

    // ---- race betting ----

    private final RaceBets bets = new RaceBets();

    boolean betsEnabled() { return on(Feature.BETTING) && getConfig().getBoolean(Feature.BETTING.path, true) && economy() != null; }
    private double houseCut() { return Math.clamp(getConfig().getDouble("betting.house-cut", 0.05), 0, 0.5); }
    private static long best(Races.Course course, UUID rider) { Races.Time time = course.times().get(rider); return time == null ? -1 : time.millis(); }
    private String target(Player viewer, long best) { return best < 0 ? lang.plain(viewer, "bet.target-finish") : Races.time(best); }

    /** Rider entered a start circle: open the book and tell players nearby. */
    void betsOpened(Player rider, Races.Course course) {
        if (!betsEnabled()) return;
        bets.open(rider.getUniqueId(), rider.getName(), course.name());
        double radius = Math.clamp(getConfig().getDouble("betting.announce-radius", 48), 4, 512);
        long best = best(course, rider.getUniqueId());
        for (Player other : rider.getWorld().getPlayers())
            if (!other.equals(rider) && other.hasPermission("vystormpaddock.bet") && other.getLocation().distanceSquared(rider.getLocation()) <= radius * radius)
                core.compat().platform().sendMessage(other, lang.component(other, "bet.open", "rider", rider.getName(), "course", course.name(), "target", target(other, best)));
    }

    /** The run started: no more bets. */
    void betsLocked(Player rider, Races.Course course) {
        RaceBets.Book book = bets.lock(rider.getUniqueId(), best(course, rider.getUniqueId()));
        if (book == null) return;
        var economy = economy();
        String pool = economy == null ? String.valueOf(book.pool()) : economy.format(book.pool());
        lang.send(rider, "bet.closed-rider", "count", book.bets.size(), "pool", pool);
        for (RaceBets.Bet bet : book.bets) { Player p = Bukkit.getPlayer(bet.bettor()); if (p != null) lang.send(p, "bet.closed", "rider", book.riderName, "pool", pool); }
    }

    /** The run ended: {@code millis} &lt; 0 = cancelled or timed out. */
    void betsSettled(UUID rider, long millis) {
        RaceBets.Book book = bets.remove(rider);
        if (book == null) return;
        if (!book.locked) { refund(book, "bet.refund"); return; }
        RaceBets.Side winner = RaceBets.outcome(book.target, millis);
        Map<UUID, Double> pay = RaceBets.payouts(book.bets, winner, houseCut());
        var economy = economy();
        logBets(book, millis, winner, pay);
        for (RaceBets.Bet bet : book.bets) {
            Double amount = pay.get(bet.bettor());
            Player p = Bukkit.getPlayer(bet.bettor());
            if (amount != null && amount > 0) {
                boolean paid = economy != null && economy.deposit(Bukkit.getOfflinePlayer(bet.bettor()), amount).successful();
                if (!paid) getLogger().warning("Race bet payout of " + amount + " to " + bet.name() + " failed");
                if (p != null) lang.send(p, bet.side() == winner ? "bet.won" : "bet.returned", "rider", book.riderName, "amount", economy == null ? String.valueOf(amount) : economy.format(amount));
                pay.remove(bet.bettor());
            } else if (p != null) lang.send(p, "bet.lost", "rider", book.riderName);
        }
    }

    /** Writes every settled bet to data/bet-log.csv and tells admins online about bets that look arranged. Nothing is blocked. */
    private void logBets(RaceBets.Book book, long millis, RaceBets.Side winner, Map<UUID, Double> pay) {
        if (!getConfig().getBoolean(Feature.BET_LOG.path, true)) return;
        long now = System.currentTimeMillis();
        StringBuilder lines = new StringBuilder();
        for (RaceBets.Bet bet : book.bets) {
            boolean party;
            try { party = core.parties().sameParty(book.rider, bet.bettor()); } catch (RuntimeException none) { party = false; }
            String flag = RaceBets.suspicion(bet, winner, millis, party);
            double payout = pay.getOrDefault(bet.bettor(), 0.0);
            lines.append(RaceBets.logLine(now, book, millis, winner, bet, payout, flag)).append(System.lineSeparator());
            if (flag.isEmpty()) continue;
            String amount = String.format(java.util.Locale.ROOT, "%.2f", bet.amount());
            getLogger().warning("Race bet looks arranged (" + flag + "): " + bet.name() + " bet " + amount + " that " + book.riderName + " misses on " + book.course);
            for (Player admin : Bukkit.getOnlinePlayers()) if (admin.hasPermission("vystormpaddock.admin"))
                lang.send(admin, "bet.suspicious-" + flag, "player", bet.name(), "rider", book.riderName, "course", book.course, "amount", amount);
        }
        try {
            java.nio.file.Path file = getDataFolder().toPath().resolve("data").resolve("bet-log.csv");
            java.nio.file.Files.createDirectories(file.getParent());
            if (!java.nio.file.Files.exists(file)) lines.insert(0, "time,course,rider,target_ms,run_ms,winner,bettor,side,amount,payout,flag" + System.lineSeparator());
            java.nio.file.Files.writeString(file, lines, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (java.io.IOException failure) { getLogger().warning("bet-log.csv not written: " + failure.getMessage()); }
    }

    /** Rider left the start without starting (got off the horse, logged out). */
    void betsCancelled(UUID rider) {
        RaceBets.Book book = bets.book(rider);
        if (book != null && !book.locked) refund(bets.remove(rider), "bet.refund");
    }

    void betsRefundAll() { for (RaceBets.Book book : bets.books()) refund(bets.remove(book.rider), "bet.refund"); }

    private void refund(RaceBets.Book book, String key) {
        if (book == null) return;
        var economy = economy();
        RaceBets.refunds(book.bets).forEach((id, amount) -> {
            boolean paid = economy != null && economy.deposit(Bukkit.getOfflinePlayer(id), amount).successful();
            if (!paid) getLogger().warning("Race bet refund of " + amount + " to " + id + " failed");
            Player p = Bukkit.getPlayer(id);
            if (p != null && lang != null) lang.send(p, key, "rider", book.riderName, "amount", economy == null ? String.valueOf(amount) : economy.format(amount));
        });
    }

    /** {@code /paddock bet [rider beat|miss amount]}: without arguments lists open books. */
    void bet(Player player, String riderName, String sideText, String amountText) {
        if (!on(Feature.BETTING) || !getConfig().getBoolean(Feature.BETTING.path, true)) { lang.send(player, "feature-off"); return; }
        var economy = economy();
        if (economy == null) { lang.send(player, "bet.no-economy"); return; }
        double cut = houseCut();
        if (riderName == null) {
            List<RaceBets.Book> open = bets.books().stream().filter(b -> !b.locked).toList();
            if (open.isEmpty()) { lang.send(player, "bet.none-open"); return; }
            for (RaceBets.Book b : open) lang.send(player, "bet.row", "rider", b.riderName, "course", b.course, "pool", economy.format(b.pool()),
                    "beat", String.format(java.util.Locale.ROOT, "%.2f", b.odds(RaceBets.Side.BEAT, cut)), "miss", String.format(java.util.Locale.ROOT, "%.2f", b.odds(RaceBets.Side.MISS, cut)));
            return;
        }
        Player rider = Bukkit.getPlayerExact(riderName);
        RaceBets.Side side = sideText == null ? null : switch (sideText.toLowerCase(java.util.Locale.ROOT)) {
            case "beat", "win", "schlagen", "sieg" -> RaceBets.Side.BEAT;
            case "miss", "lose", "verfehlen", "niederlage" -> RaceBets.Side.MISS;
            default -> sideText.equalsIgnoreCase(lang.plain(player, "bet.word-beat")) ? RaceBets.Side.BEAT
                    : sideText.equalsIgnoreCase(lang.plain(player, "bet.word-miss")) ? RaceBets.Side.MISS : null;
        };
        double amount;
        try { amount = amountText == null ? Double.NaN : Double.parseDouble(amountText.replace(',', '.')); } catch (NumberFormatException bad) { amount = Double.NaN; }
        if (rider == null || side == null || Double.isNaN(amount)) { lang.send(player, "bet.usage"); return; }
        double min = Math.max(0.01, getConfig().getDouble("betting.min", 10)), max = Math.max(min, getConfig().getDouble("betting.max", 1000));
        String code = bets.check(rider.getUniqueId(), player.getUniqueId(), amount, min, max, Math.clamp(getConfig().getInt("betting.max-bets", 20), 1, 200));
        if (!code.equals("ok")) { lang.send(player, "bet.error-" + code, "rider", rider.getName(), "min", economy.format(min), "max", economy.format(max)); return; }
        if (!economy.has(player, amount) || !economy.withdraw(player, amount).successful()) { lang.send(player, "bet.error-money"); return; }
        bets.place(rider.getUniqueId(), new RaceBets.Bet(player.getUniqueId(), player.getName(), side, amount));
        RaceBets.Book book = bets.book(rider.getUniqueId());
        lang.send(player, "bet.placed", "amount", economy.format(amount), "rider", rider.getName(), "side", lang.plain(player, side == RaceBets.Side.BEAT ? "bet.side-beat" : "bet.side-miss"),
                "odds", String.format(java.util.Locale.ROOT, "%.2f", book.odds(side, cut)));
        lang.send(rider, "bet.on-you", "player", player.getName(), "pool", economy.format(book.pool()));
    }

    // ---- animal trade ----

    private final TradeOffers offers = new TradeOffers();

    boolean tradeEnabled() { return on(Feature.TRADE) && getConfig().getBoolean(Feature.TRADE.path, true); }

    private Vystorm.vystorm_core._integrations.vault.VaultInterface economy() {
        try { var vault = core.vault(); return vault != null && vault.isEconomyAvailable() ? vault : null; }
        catch (RuntimeException | LinkageError none) { return null; }
    }

    /** {@code /paddock offer <player> [price]}: offers the animal you look at or ride to another player. */
    void offer(Player seller, String buyerName, String priceText) {
        if (!tradeEnabled()) { lang.send(seller, "feature-off"); return; }
        AbstractHorse horse = ownTarget(seller);
        if (horse == null) return;
        if (!seller.getUniqueId().equals(claims.owner(horse))) { lang.send(seller, "trade.not-owner"); return; }
        Player buyer = buyerName == null ? null : Bukkit.getPlayerExact(buyerName);
        if (buyer == null || !seller.canSee(buyer)) { lang.send(seller, "trade.buyer-offline", "player", buyerName == null ? "?" : buyerName); return; }
        if (buyer.equals(seller)) { lang.send(seller, "trade.self"); return; }
        if (!buyer.hasPermission("vystormpaddock.use")) { lang.send(seller, "trade.buyer-no-permission", "player", buyer.getName()); return; }
        Double price = TradeOffers.price(priceText);
        if (price == null) { lang.send(seller, "trade.bad-price"); return; }
        var economy = economy();
        if (price > 0 && economy == null) { lang.send(seller, "trade.no-economy"); return; }
        int seconds = Math.clamp(getConfig().getInt("trade.offer-seconds", 120), 15, 3600);
        offers.put(new TradeOffers.Offer(seller.getUniqueId(), seller.getName(), buyer.getUniqueId(), horse.getUniqueId(), name(horse), price,
                System.currentTimeMillis() + seconds * 1000L));
        String shown = price > 0 ? economy.format(price) : lang.plain(buyer, "trade.free");
        lang.send(seller, "trade.offered", "name", name(horse), "player", buyer.getName(), "price", price > 0 ? economy.format(price) : lang.plain(seller, "trade.free"));
        core.compat().platform().sendMessage(buyer, lang.component(buyer, "trade.incoming", "player", seller.getName(), "name", name(horse),
                "type", horse.getType().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '), "price", shown, "seconds", seconds)
                .append(net.kyori.adventure.text.Component.space())
                .append(lang.component(buyer, "trade.accept-button").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/paddock accept")))
                .append(net.kyori.adventure.text.Component.space())
                .append(lang.component(buyer, "trade.decline-button").clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/paddock decline"))));
    }

    /** {@code /paddock accept}: takes the open offer, pays the price through Vault and moves the claim. */
    void accept(Player buyer) {
        if (!tradeEnabled()) { lang.send(buyer, "feature-off"); return; }
        long now = System.currentTimeMillis();
        var open = offers.take(buyer.getUniqueId(), now);
        if (open.isEmpty()) { lang.send(buyer, "trade.none"); return; }
        TradeOffers.Offer offer = open.get();
        org.bukkit.entity.Entity entity = Bukkit.getEntity(offer.animal());
        if (!(entity instanceof AbstractHorse horse) || !horse.isValid() || !offer.seller().equals(claims.owner(horse))) { lang.send(buyer, "trade.gone", "name", offer.animalName()); return; }
        if (offer.price() > 0) {
            var economy = economy();
            if (economy == null) { lang.send(buyer, "trade.no-economy"); return; }
            var bound = economy.economySnapshot();
            if (!bound.has(buyer, offer.price())) { lang.send(buyer, "trade.too-poor", "price", bound.format(offer.price())); return; }
            var paid = bound.withdraw(buyer, offer.price());
            if (!paid.successful()) { lang.send(buyer, "trade.payment-failed"); return; }
            var received = bound.deposit(Bukkit.getOfflinePlayer(offer.seller()), offer.price());
            if (!received.successful()) {
                var refund = bound.deposit(buyer, offer.price());
                if (!refund.successful()) getLogger().severe("Animal trade: refund of " + offer.price() + " to " + buyer.getName() + " failed; manual review required.");
                lang.send(buyer, "trade.payment-failed");
                return;
            }
        }
        claims.claim(horse, buyer);
        offers.forgetAnimal(horse.getUniqueId());
        String price = offer.price() > 0 && economy() != null ? economy().format(offer.price()) : lang.plain(buyer, "trade.free");
        lang.send(buyer, "trade.bought", "name", offer.animalName(), "player", offer.sellerName(), "price", price);
        Player seller = Bukkit.getPlayer(offer.seller());
        if (seller != null) lang.send(seller, "trade.sold", "name", offer.animalName(), "player", buyer.getName(), "price", price);
        else core.message().inbox().send(this, offer.seller(), "trade.sold-inbox", "name", offer.animalName(), "player", buyer.getName(), "price", price);
    }

    void decline(Player buyer) {
        if (!tradeEnabled()) { lang.send(buyer, "feature-off"); return; }
        var open = offers.take(buyer.getUniqueId(), System.currentTimeMillis());
        if (open.isEmpty()) { lang.send(buyer, "trade.none"); return; }
        lang.send(buyer, "trade.declined", "name", open.get().animalName());
        Player seller = Bukkit.getPlayer(open.get().seller());
        if (seller != null) lang.send(seller, "trade.declined-seller", "name", open.get().animalName(), "player", buyer.getName());
    }

    @org.bukkit.event.EventHandler
    public void forgetOffers(org.bukkit.event.player.PlayerQuitEvent event) { offers.forget(event.getPlayer().getUniqueId()); }

    private AbstractHorse ownTarget(Player player) {
        AbstractHorse horse = target(player);
        if (horse == null) { lang.send(player, "no-target"); return null; }
        UUID owner = claims.owner(horse);
        if (owner == null) { lang.send(player, "not-claimed"); return null; }
        if (!owner.equals(player.getUniqueId()) && !player.hasPermission("vystormpaddock.bypass")) {
            lang.send(player, "not-yours", "owner", claims.ownerName(horse));
            return null;
        }
        return horse;
    }

    void claim(Player player) {
        if (off(player, Feature.CLAIMS)) return;
        AbstractHorse horse = target(player);
        if (horse == null) { lang.send(player, "no-target"); return; }
        if (Claims.pet(horse)) { lang.send(player, "pet-managed"); return; }
        if (claims.claimed(horse)) {
            if (claims.owner(horse).equals(player.getUniqueId())) lang.send(player, "already-yours");
            else lang.send(player, "not-yours", "owner", claims.ownerName(horse));
            return;
        }
        if (!horse.isTamed() || core.compat().platform().getOwnerUniqueId(horse) != null && !core.compat().platform().getOwnerUniqueId(horse).equals(player.getUniqueId())) {
            lang.send(player, "tame-first");
            return;
        }
        claims.claim(horse, player);
        lang.send(player, "claimed", "name", name(horse));
        herdBook.seen(horse);
    }

    void release(Player player) {
        if (off(player, Feature.CLAIMS)) return;
        AbstractHorse horse = ownTarget(player);
        if (horse == null) return;
        herdBook.forget(claims.owner(horse), horse.getUniqueId());
        claims.release(horse);
        lang.send(player, "released", "name", name(horse));
    }

    void trust(Player player, String[] args, boolean add) {
        if (off(player, Feature.TRUST)) return;
        if (args.length < 2) { lang.send(player, "usage-trust"); return; }
        AbstractHorse horse = ownTarget(player);
        if (horse == null) return;
        OfflinePlayer other = core.compat().platform().getOfflinePlayerIfCached(args[1]);
        if (other == null) { lang.send(player, "unknown-player", "player", args[1]); return; }
        Set<UUID> trusted = claims.trusted(horse);
        if (add) trusted.add(other.getUniqueId());
        else trusted.remove(other.getUniqueId());
        claims.trusted(horse, trusted);
        lang.send(player, add ? "trusted" : "untrusted", "player", other.getName() == null ? args[1] : other.getName(), "name", name(horse));
    }

    void compare(Player player) { if (off(player, Feature.INFO)) return; HorseComparison.show(core.compat(), player, lang, (int) Math.ceil(reach)); }

    void info(Player player) {
        if (off(player, Feature.INFO)) return;
        AbstractHorse horse = target(player);
        if (horse == null) { lang.send(player, "no-target"); return; }
        String owner = claims.claimed(horse) ? claims.ownerName(horse) : "-";
        List<String> names = new ArrayList<>();
        for (UUID id : claims.trusted(horse)) {
            String trustedName = Bukkit.getOfflinePlayer(id).getName();
            names.add(trustedName == null ? id.toString().substring(0, 8) : trustedName);
        }
        lang.send(player, "info", "name", name(horse), "owner", owner, "trusted", names.isEmpty() ? "-" : String.join(", ", names), "stats", stats(horse));
        String[] parents = claims.parents(horse);
        if (on(Feature.BREEDING)) lang.send(player, "info-breeding", "rating", Integer.toString(rating(attribute(horse, core.compat().server().attribute("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED")), attribute(horse, core.compat().server().attribute("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH")), attribute(horse, core.compat().server().attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH")))),
                "sire", parents == null ? "-" : parents[0], "dam", parents == null ? "-" : parents[1], "generation", Integer.toString(claims.generation(horse)));
    }

    void store(Player player) {
        if (off(player, Feature.STABLE)) return;
        AbstractHorse horse = ownTarget(player);
        if (horse == null) return;
        if (Claims.pet(horse)) { lang.send(player, "pet-managed"); return; }
        UUID owner = claims.owner(horse);
        try {
            List<Stable.Stored> animals = stable.list(owner);
            Player ownerOnline = Bukkit.getPlayer(owner);
            int limit = slots(ownerOnline != null ? ownerOnline : player);
            if (animals.size() >= limit) { lang.send(player, "stable-full", "max", Integer.toString(limit)); return; }
            horse.eject();
            horse.setLeashHolder(null);
            byte[] data = core.compat().storage().serializeEntity(horse);
            animals.add(new Stable.Stored(UUID.randomUUID().toString().substring(0, 8), name(horse), horse.getType().name(), System.currentTimeMillis(), data));
            stable.save(owner, animals);
            Location at = horse.getLocation();
            horse.remove();
            herdBook.forget(owner, horse.getUniqueId());
            at.getWorld().playSound(at, Sound.ENTITY_HORSE_GALLOP, 0.8f, 1.1f);
            lang.send(player, "stored", "name", name(horse), "count", Integer.toString(animals.size()), "max", Integer.toString(limit));
        } catch (IOException | RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not store animal for " + player.getName(), failure);
            lang.send(player, "failed");
        }
    }

    void list(Player player) {
        if (off(player, Feature.STABLE)) return;
        try {
            List<Stable.Stored> animals = stable.list(player.getUniqueId());
            lang.send(player, "list-header", "count", Integer.toString(animals.size()), "max", Integer.toString(slots(player)));
            for (int i = 0; i < animals.size(); i++) {
                Stable.Stored animal = animals.get(i);
                core.compat().platform().sendMessage(player, lang.component(player, "list-entry", "number", Integer.toString(i + 1), "name", animal.name(),
                                "type", animal.type().toLowerCase(Locale.ROOT).replace('_', ' '))
                        .clickEvent(net.kyori.adventure.text.event.ClickEvent.runCommand("/paddock summon " + (i + 1)))
                        .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(lang.component(player, "list-hover", "name", animal.name()))));
            }
            if (animals.isEmpty()) lang.send(player, "list-empty");
        } catch (IOException failure) {
            getLogger().log(Level.WARNING, "Could not read stable of " + player.getName(), failure);
            lang.send(player, "failed");
        }
    }

    void summon(Player player, String[] args) {
        summon(player, args, null);
    }

    private void summon(Player player, String[] args, String id) {
        if (off(player, Feature.STABLE)) return;
        if (!player.hasPermission("vystormpaddock.use")) { lang.send(player, "denied"); return; }
        if (id == null && args.length < 2) {
            // no argument: a single stabled animal comes right away, several open the stable menu
            try {
                List<Stable.Stored> animals = stable.list(player.getUniqueId());
                if (animals.isEmpty()) { lang.send(player, "list-empty"); return; }
                if (animals.size() > 1) { if (on(Feature.STABLE_MENU)) openStable(player); else list(player); return; }
                args = new String[]{"summon", "1"};
            } catch (IOException failure) {
                getLogger().log(Level.WARNING, "Could not read stable of " + player.getName(), failure);
                lang.send(player, "failed");
                return;
            }
        }
        if (disabledWorlds.contains(player.getWorld().getName().toLowerCase(Locale.ROOT))) { lang.send(player, "world-disabled"); return; }
        long now = System.currentTimeMillis();
        Long last = lastSummon.get(player.getUniqueId());
        if (last != null && now - last < summonCooldownMillis && !player.hasPermission("vystormpaddock.bypass")) {
            lang.send(player, "cooldown", "seconds", Long.toString((summonCooldownMillis - (now - last) + 999) / 1000));
            return;
        }
        try {
            List<Stable.Stored> animals = stable.list(player.getUniqueId());
            Stable.Stored chosen = id == null ? Stable.find(animals, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)))
                    : animals.stream().filter(animal -> animal.id().equals(id)).findFirst().orElse(null);
            if (chosen == null) { lang.send(player, "not-in-stable"); return; }
            // Reject incompatible entity data before removing the stored animal (e.g. after a server downgrade).
            Entity entity = core.compat().storage().deserializeEntity(chosen.data(), player.getWorld());
            Location at = player.getLocation().add(player.getLocation().getDirection().setY(0).normalize().multiply(1.5));
            at.setY(player.getLocation().getY());
            if (!stable.release(player.getUniqueId(), animals, chosen,
                    () -> core.compat().storage().spawnAt(entity, at), entity::isInWorld)) {
                lang.send(player, "failed");
                return;
            }
            lastSummon.put(player.getUniqueId(), now);
            at.getWorld().playSound(at, Sound.ENTITY_HORSE_AMBIENT, 1f, 1f);
            if (entity instanceof AbstractHorse back) herdBook.seen(back);
            if (mountOnSummon && entity instanceof AbstractHorse summoned && summoned.getInventory().getSaddle() != null) summoned.addPassenger(player);
            at.getWorld().spawnParticle(org.bukkit.Particle.CLOUD, at.clone().add(0, 0.8, 0), 12, 0.4, 0.4, 0.4, 0.02);
            double healed = 0;
            if (entity instanceof org.bukkit.entity.LivingEntity living && !living.isDead()) {
                double max = attribute(living, core.compat().server().attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH")), before = living.getHealth();
                double after = restedHealth(before, max, now - chosen.storedAt(), stableRestMillis);
                if (after > before) { living.setHealth(after); healed = after - before; }
            }
            if (healed >= 1) lang.send(player, "summoned-healed", "name", chosen.name(), "hearts", String.format(Locale.ROOT, "%.1f", healed / 2));
            else lang.send(player, "summoned", "name", chosen.name());
        } catch (IOException | RuntimeException failure) {
            getLogger().log(Level.WARNING, "Could not summon animal for " + player.getName(), failure);
            lang.send(player, "failed");
        }
    }
}
