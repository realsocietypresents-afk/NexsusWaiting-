package com.nexuscraft.nexuswaiting;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;

/** Joins check the list; clicks in the menus run their buttons; nothing in them can be picked up. */
final class WaitingListener implements Listener {

    private final WaitingService service;

    WaitingListener(WaitingService service) {
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.quit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView() == null ? null : event.getView().getTopInventory();
        if (top == null || !(top.getHolder() instanceof WaitingMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (event.getClickedInventory() == top) {
            service.click(p, menu, event.getRawSlot());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView() == null ? null : event.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof WaitingMenu) {
            event.setCancelled(true);
        }
    }
}
