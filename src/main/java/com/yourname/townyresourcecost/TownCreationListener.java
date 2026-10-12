package com.yourname.townyresourcecost;

import com.palmergames.bukkit.towny.event.PreNewTownEvent;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

public class TownCreationListener implements Listener {

    private final TownyResourceCost plugin;

    public TownCreationListener(TownyResourceCost plugin) {
        this.plugin = plugin;
    }

    /**
     * Срабатывает ДО фактического создания города.
     * PreNewTownEvent — отменяемое событие, поэтому если его отменить,
     * Towny не создаст город и не спишет деньги.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreNewTown(PreNewTownEvent event) {
        Player player = event.getPlayer();
        if (player == null) {
            return;
        }

        // Креатив/спектатор — не списываем ресурсы.
        if (player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR) {
            player.sendMessage(color(plugin.getConfig().getString("messages.creative-bypass",
                    "&aВы в креативе, ресурсы не списаны.")));
            return;
        }

        List<ItemStack> requiredItems = plugin.getRequiredItems();

        if (!hasItems(player, requiredItems)) {
            event.setCancelled(true);
            event.setCancelMessage(color(plugin.getConfig().getString("messages.not-enough",
                            "&cДля создания города вам нужно: &e%cost%")
                    .replace("%cost%", formatCostWithStatus(player, requiredItems))));
            return;
        }

        // Создание одобрено — можно списывать.
        removeItems(player, requiredItems);
        player.sendMessage(color(plugin.getConfig().getString("messages.success",
                "&aРесурсы за город списаны!")));
    }

    private boolean hasItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            if (!player.getInventory().containsAtLeast(item, item.getAmount())) {
                return false;
            }
        }
        return true;
    }

    private void removeItems(Player player, List<ItemStack> items) {
        for (ItemStack item : items) {
            player.getInventory().removeItem(item);
        }
    }

    private String formatCostWithStatus(Player player, List<ItemStack> items) {
        List<String> parts = new ArrayList<>();
        for (ItemStack required : items) {
            Material type = required.getType();
            int needed = required.getAmount();
            int has = 0;
            for (ItemStack content : player.getInventory().getContents()) {
                if (content != null && content.getType() == type) {
                    has += content.getAmount();
                }
            }
            boolean enough = has >= needed;
            String color = enough ? "&a" : "&c";
            parts.add(color + needed + " " + type.name()
                    + " &7(у вас: " + color + has + "&7)");
        }
        return String.join("&7, ", parts);
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}