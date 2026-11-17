package at.esoren.vystormpaddock;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Searchable stable with page controls and one icon per stored animal; click to summon it. */
final class StableMenu implements InventoryHolder {
    private Inventory inventory;
    private StablePage page;

    @Override
    public Inventory getInventory() { return inventory; }

    /** Spawn egg of the stored type ("HORSE" → HORSE_SPAWN_EGG), saddle as fallback. */
    static Material icon(String type) {
        Material egg = Material.matchMaterial(type + "_SPAWN_EGG");
        return egg == null ? Material.SADDLE : egg;
    }

    static Inventory build(PlatformCompatInterface platform, Lang lang, Player player, List<Stable.Stored> animals, int slots) {
        return build(platform, lang, player, animals, slots, "", 0);
    }

    static Inventory build(PlatformCompatInterface platform, Lang lang, Player player, List<Stable.Stored> animals, int slots, String search, int requested) {
        return build(platform, lang, player, animals, slots, search, requested, StablePage.Sort.OLDEST);
    }

    static Inventory build(PlatformCompatInterface platform, Lang lang, Player player, List<Stable.Stored> animals, int slots, String search, int requested, StablePage.Sort sort) {
        StableMenu holder = new StableMenu();
        holder.page = StablePage.of(animals, search, requested, sort);
        StablePage page = holder.page;
        Inventory inventory = platform.createInventory(holder, 54, lang.component(player, "menu-page-title", "page", Integer.toString(page.number() + 1), "pages", Integer.toString(page.pages())));
        holder.inventory = inventory;
        DateTimeFormatter date = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(ZoneId.systemDefault());
        for (int i = 0; i < page.animals().size(); i++) {
            Stable.Stored animal = page.animals().get(i);
            ItemStack item = new ItemStack(icon(animal.type()));
            String type = animal.type().toLowerCase(Locale.ROOT).replace('_', ' ');
            platform.editMeta(item, meta -> {
                platform.displayName(meta, lang.component(player, "menu-name", "name", animal.name()).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE));
                platform.lore(meta, lang.lines(player, "menu-lore", "type", type, "date", date.format(Instant.ofEpochMilli(animal.storedAt()))).stream()
                        .map(line -> line.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)).toList());
            });
            inventory.setItem(i, item);
        }
        if (page.number() > 0) inventory.setItem(45, button(platform, lang, player, Material.ARROW, "menu-previous"));
        if (page.number() + 1 < page.pages()) inventory.setItem(53, button(platform, lang, player, Material.ARROW, "menu-next"));
        inventory.setItem(49, button(platform, lang, player, Material.BOOK, "menu-summary", "count", Integer.toString(animals.size()),
                "max", Integer.toString(slots), "matches", Integer.toString(page.matches())));
        if (!page.search().isEmpty()) inventory.setItem(48, button(platform, lang, player, Material.MILK_BUCKET, "menu-clear", "search", page.search()));
        inventory.setItem(50, button(platform, lang, player, Material.HOPPER, page.sort().key()));
        if (page.animals().isEmpty()) inventory.setItem(22, button(platform, lang, player, Material.BARRIER, "menu-no-matches"));
        return inventory;
    }

    private static ItemStack button(PlatformCompatInterface platform, Lang lang, Player player, Material material, String key, Object... values) {
        ItemStack item = new ItemStack(material);
        platform.editMeta(item, meta -> platform.displayName(meta, lang.component(player, key, values).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)));
        return item;
    }

    /** Cancels all moves in the menu and summons on click. */
    static final class Clicks implements Listener {
        private final PaddockPlugin plugin;
        private final InventoryCompatInterface inventories;

        Clicks(PaddockPlugin plugin, InventoryCompatInterface inventories) { this.plugin = plugin; this.inventories = inventories; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(inventories.top(event.getView()).getHolder() instanceof StableMenu menu)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != inventories.top(event.getView()) || event.getCurrentItem() == null) return;
            if (!(event.getWhoClicked() instanceof Player player) || !event.isLeftClick() && !event.isRightClick()) return;
            int slot = event.getSlot();
            String id = menu.page.animalId(slot);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || player.getOpenInventory().getTopInventory() != menu.inventory) return;
                if (id != null) plugin.summonId(player, id);
                else if (slot == 45 && menu.page.number() > 0) plugin.openStable(player, menu.page.search(), menu.page.number() - 1, menu.page.sort());
                else if (slot == 53 && menu.page.number() + 1 < menu.page.pages()) plugin.openStable(player, menu.page.search(), menu.page.number() + 1, menu.page.sort());
                else if (slot == 48 && !menu.page.search().isEmpty()) plugin.openStable(player, "", 0, menu.page.sort());
                else if (slot == 50) plugin.openStable(player, menu.page.search(), 0, menu.page.sort().next());
            });
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof StableMenu) event.setCancelled(true);
        }
    }
}
