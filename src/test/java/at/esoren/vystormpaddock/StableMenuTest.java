package at.esoren.vystormpaddock;

import static org.mockito.Mockito.*;

import java.util.List;
import java.util.stream.IntStream;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;

class StableMenuTest {
    @Test
    void clicksUseSnapshotIdsAndNavigationKeepsSearchAndSort() throws Exception {
        PaddockPlugin plugin = mock(PaddockPlugin.class);
        Player player = mock(Player.class);
        when(player.isOnline()).thenReturn(true);
        Inventory inventory = mock(Inventory.class);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        when(player.getOpenInventory()).thenReturn(view);
        StableMenu menu = new StableMenu();
        List<Stable.Stored> all = IntStream.range(0, 100)
                .mapToObj(i -> new Stable.Stored("id-" + i, "Horse " + i, "HORSE", i, new byte[0])).toList();
        set(menu, "inventory", inventory);
        set(menu, "page", StablePage.of(all, "horse", 1, StablePage.Sort.NEWEST));
        when(inventory.getHolder()).thenReturn(menu);
        InventoryClickEvent event = mock(InventoryClickEvent.class);
        when(event.getView()).thenReturn(view);
        when(event.getClickedInventory()).thenReturn(inventory);
        when(event.getWhoClicked()).thenReturn(player);
        when(event.getCurrentItem()).thenReturn(mock(ItemStack.class));
        when(event.isLeftClick()).thenReturn(true);
        BukkitScheduler scheduler = mock(BukkitScheduler.class);
        try (var bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getScheduler).thenReturn(scheduler);
            when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(call -> {
                ((Runnable) call.getArgument(1)).run();
                return null;
            });
            StableMenu.Clicks clicks = new StableMenu.Clicks(plugin, Vystorm.vystorm_core.compat.InventoryCompatInterface.DEFAULT);
            when(event.getSlot()).thenReturn(0);
            clicks.onClick(event);
            verify(plugin).summonId(player, "id-54");
            when(event.getSlot()).thenReturn(45);
            clicks.onClick(event);
            verify(plugin).openStable(player, "horse", 0, StablePage.Sort.NEWEST);
            when(event.getSlot()).thenReturn(53);
            clicks.onClick(event);
            verify(plugin).openStable(player, "horse", 2, StablePage.Sort.NEWEST);
            when(event.getSlot()).thenReturn(48);
            clicks.onClick(event);
            verify(plugin).openStable(player, "", 0, StablePage.Sort.NEWEST);
            when(event.getSlot()).thenReturn(50);
            clicks.onClick(event);
            verify(plugin).openStable(player, "horse", 0, StablePage.Sort.NAME);
            when(event.getSlot()).thenReturn(49);
            clicks.onClick(event);
            verifyNoMoreInteractions(plugin);
            verify(event, times(6)).setCancelled(true);

            // Bottom inventory and a closed menu cannot trigger delayed actions.
            when(event.getClickedInventory()).thenReturn(mock(Inventory.class));
            clicks.onClick(event);
            when(event.getClickedInventory()).thenReturn(inventory);
            when(event.getSlot()).thenReturn(0);
            when(player.getOpenInventory()).thenReturn(mock(InventoryView.class));
            clicks.onClick(event);
            verifyNoMoreInteractions(plugin);
        }
    }

    @Test
    void draggingIntoMenuIsCancelled() {
        Inventory inventory = mock(Inventory.class);
        when(inventory.getHolder()).thenReturn(new StableMenu());
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(inventory);
        InventoryDragEvent event = mock(InventoryDragEvent.class);
        when(event.getView()).thenReturn(view);
        new StableMenu.Clicks(mock(PaddockPlugin.class), Vystorm.vystorm_core.compat.InventoryCompatInterface.DEFAULT).onDrag(event);
        verify(event).setCancelled(true);
    }

    private static void set(StableMenu menu, String name, Object value) throws Exception {
        var field = StableMenu.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(menu, value);
    }
}
