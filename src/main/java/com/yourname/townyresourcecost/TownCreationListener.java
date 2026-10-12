package com.yourname.townyresourcecost;

import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.PreNewTownEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TownCreationListener implements Listener {

    private final TownyResourceCost plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TownCreationListener(TownyResourceCost plugin) {
        this.plugin = plugin;
    }

    /**
     * Только проверка. Если чего-то не хватает — отменяем событие и
     * выставляем сообщение. Никаких списаний здесь быть не должно:
     * Towny может отменить создание уже после нас.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreNewTown(PreNewTownEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        if (isBypassing(player)) {
            player.sendMessage(plugin.parse(plugin.getConfig().getString(
                    "messages.creative-bypass",
                    "<green>Вы в креативе, ресурсы не списаны")));
            return;
        }

        Map<Material, Integer> required = plugin.getRequiredItems();
        if (required.isEmpty()) return;

        if (!hasItems(player, required)) {
            event.setCancelled(true);
            String raw = plugin.getConfig().getString(
                            "messages.not-enough",
                            "<red>Для создания города вам нужно: %cost%")
                    .replace("%cost%", formatCostWithStatus(player, required));
            // Towny сам отобразит cancelMessage игроку и транслирует &-коды.
            event.setCancelMessage(miniToLegacy(raw));
        }
    }

    /**
     * Фактическое списание — только после успешного создания города.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNewTown(NewTownEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;
        if (isBypassing(player)) return;

        Map<Material, Integer> required = plugin.getRequiredItems();
        if (required.isEmpty()) return;

        // Подстраховка: между PreNewTownEvent и NewTownEvent предметы могли пропасть.
        if (!hasItems(player, required)) {
            plugin.getLogger().warning("Игрок " + player.getName()
                    + " создал город '" + event.getTown().getName()
                    + "', но требуемых ресурсов уже нет. Списание пропущено.");
            return;
        }

        removeItems(player, required);
        player.sendMessage(plugin.parse(plugin.getConfig().getString(
                "messages.success",
                "<green>Ресурсы за город списаны!")));
    }

    private boolean isBypassing(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean hasItems(Player player, Map<Material, Integer> required) {
        Map<Material, Integer> have = countItems(player);
        for (Map.Entry<Material, Integer> e : required.entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** Один проход по инвентарю (только storage, без брони/оффхенда). */
    private Map<Material, Integer> countItems(Player player) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        return counts;
    }

    /** Аккуратное удаление ровно нужного количества из storage-слотов. */
    private void removeItems(Player player, Map<Material, Integer> required) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getStorageContents();
        for (Map.Entry<Material, Integer> entry : required.entrySet()) {
            int toRemove = entry.getValue();
            for (int i = 0; i < contents.length && toRemove > 0; i++) {
                ItemStack stack = contents[i];
                if (stack == null || stack.getType() != entry.getKey()) continue;
                int take = Math.min(stack.getAmount(), toRemove);
                int left = stack.getAmount() - take;
                toRemove -= take;
                if (left <= 0) {
                    contents[i] = null;
                } else {
                    stack.setAmount(left);
                }
            }
        }
        inv.setStorageContents(contents);
    }

    private String formatCostWithStatus(Player player, Map<Material, Integer> required) {
        Map<Material, Integer> have = countItems(player);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : required.entrySet()) {
            Material type = e.getKey();
            int needed = e.getValue();
            int has = have.getOrDefault(type, 0);
            boolean enough = has >= needed;
            String color = enough ? "<green>" : "<red>";
            parts.add(color + needed + " " + type.name()
                    + " <gray>(у вас: " + color + has + "<gray>)");
        }
        return String.join("<gray>, ", parts);
    }

    /** MiniMessage -> legacy &-codes (для Towny API setCancelMessage(String)). */
    private String miniToLegacy(String s) {
        if (s == null) return "";
        return LegacyComponentSerializer.legacyAmpersand()
                .serialize(miniMessage.deserialize(s));
    }
}