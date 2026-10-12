package com.yourname.townyresourcecost;

import com.palmergames.bukkit.towny.event.NewTownEvent;
import com.palmergames.bukkit.towny.event.PreNewTownEvent;
import com.palmergames.bukkit.towny.exceptions.TownyException;
import com.palmergames.bukkit.towny.object.Town;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TownCreationListener implements Listener {

    private final TownyResourceCost plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public TownCreationListener(TownyResourceCost plugin) {
        this.plugin = plugin;
    }

    // ============================================================
    // 1. Проверка (без списания!)
    // ============================================================
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPreNewTown(PreNewTownEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        if (isBypassing(player)) {
            player.sendMessage(plugin.parse(plugin.getConfig().getString(
                    "messages.creative-bypass",
                    "<green>Вы в креативе, ресурсы не списаны")));
            plugin.rememberTownCreator(event.getTownName(), player);
            return;
        }

        Map<Material, Integer> required = plugin.getRequiredItems();
        if (required.isEmpty()) {
            plugin.rememberTownCreator(event.getTownName(), player);
            return;
        }

        if (!hasItems(player, required)) {
            event.setCancelled(true);
            String raw = plugin.getConfig().getString(
                            "messages.not-enough",
                            "<red>Для создания города вам нужно: %cost%")
                    .replace("%cost%", formatCostWithStatus(player, required));
            event.setCancelMessage(miniToLegacy(raw));
            return;
        }

        plugin.rememberTownCreator(event.getTownName(), player);
    }

    // ============================================================
    // 2. После успешного создания: списать → сундук → эффекты
    // ============================================================
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNewTown(NewTownEvent event) {
        Town town = event.getTown();
        Player player = plugin.consumeTownCreator(town.getName());

        if (player != null && !isBypassing(player)) {
            Map<Material, Integer> required = plugin.getRequiredItems();
            if (!required.isEmpty()) {
                if (hasItems(player, required)) {
                    removeItems(player, required);
                    player.sendMessage(plugin.parse(plugin.getConfig().getString(
                            "messages.success",
                            "<green>Ресурсы за город списаны!")));
                } else {
                    plugin.getLogger().warning("Игрок " + player.getName()
                            + " создал город '" + town.getName()
                            + "', но требуемых ресурсов уже нет. Списание пропущено.");
                }
            }
        } else if (player == null) {
            plugin.getLogger().warning("Не удалось определить создателя города '"
                    + town.getName() + "' — списание и эффекты пропущены.");
        }

        if (plugin.getConfig().getBoolean("starting-chest.enabled", false)) {
            placeStartingChest(town);
        }

        if (player != null
                && plugin.getConfig().getBoolean("effects.enabled", true)) {
            playEffects(player);
        }
    }

    // ============================================================
    // Сундук
    // ============================================================
    private void placeStartingChest(Town town) {
        Location spawn;
        try {
            spawn = town.getSpawn();
        } catch (TownyException e) {
            plugin.getLogger().warning("Не удалось получить spawn города "
                    + town.getName() + ": " + e.getMessage());
            return;
        }
        if (spawn == null || spawn.getWorld() == null) {
            plugin.getLogger().warning("Spawn города " + town.getName()
                    + " не установлен или мир недоступен — сундук не поставлен.");
            return;
        }

        int radius = plugin.getConfig().getInt("starting-chest.search-radius", 4);
        String placement = plugin.getConfig().getString("starting-chest.placement", "SPAWN_GROUND");
        boolean surface = !"SPAWN_LEVEL".equalsIgnoreCase(placement);

        Location chestLoc = findChestLocation(spawn, radius, surface);
        if (chestLoc == null) {
            plugin.getLogger().warning("Не нашёл место для стартового сундука в городе "
                    + town.getName() + " (spawn=" + spawn + ")");
            return;
        }

        Block block = chestLoc.getBlock();
        block.setType(Material.CHEST, false);

        if (!(block.getState() instanceof Chest chest)) return;

        Inventory inv = chest.getInventory();
        List<ItemStack> items = plugin.getStartingChestItems();
        int limit = Math.min(items.size(), inv.getSize());
        for (int i = 0; i < limit; i++) {
            inv.setItem(i, items.get(i));
        }
        if (items.size() > inv.getSize()) {
            plugin.getLogger().warning("Стартовый сундук города " + town.getName()
                    + ": часть ресурсов не поместилась.");
        }
    }

    private Location findChestLocation(Location spawn, int radius, boolean surface) {
        World world = spawn.getWorld();
        if (world == null) return null;

        int cx = spawn.getBlockX();
        int cy = spawn.getBlockY();
        int cz = spawn.getBlockZ();

        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = cx + dx;
                    int z = cz + dz;

                    Location candidate = surface
                            ? findSurfaceSpot(world, x, z)
                            : new Location(world, x, cy, z);

                    if (candidate != null && isValidChestSpot(candidate)) {
                        return candidate;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Возвращает координату над САМЫМ ВЕРХНИМ твёрдым блоком в колонке.
     * Никогда не спускается ниже первого твёрдого блока — поэтому пещеры
     * больше не подходят. Если над поверхностью что-то мешает (дерево,
     * высокий куст и т.п.) — возвращает null, вызывающий код перейдёт
     * к соседней колонке.
     */
    private Location findSurfaceSpot(World world, int x, int z) {
        int maxY = world.getMaxHeight() - 1;
        int minY = world.getMinHeight();

        int surfaceY = Integer.MIN_VALUE;
        for (int y = maxY; y > minY; y--) {
            Material type = world.getBlockAt(x, y, z).getType();
            if (type == Material.WATER || type == Material.LAVA) return null;
            if (type.isSolid()) {
                surfaceY = y;
                break; // СТОП. Ниже не идём — там пещеры.
            }
        }
        if (surfaceY == Integer.MIN_VALUE) return null;

        Block above1 = world.getBlockAt(x, surfaceY + 1, z);
        Block above2 = world.getBlockAt(x, surfaceY + 2, z);

        if (!isReplaceable(above1.getType())) return null;
        if (!isReplaceable(above2.getType())) return null;

        // Убираем траву/цветы, чтобы сундук не торчал в кустах.
        if (!above1.getType().isAir()) above1.setType(Material.AIR, false);
        if (!above2.getType().isAir()) above2.setType(Material.AIR, false);

        return above1.getLocation();
    }

    /** Можно ли поставить сундук вместо этого блока (воздух / трава / цветы / снег). */
    private boolean isReplaceable(Material m) {
        if (m.isAir()) return true;
        if (m == Material.WATER || m == Material.LAVA) return false;
        // всё, что не solid и не жидкость — трава, цветы, факелы, рельсы и т.п.
        return !m.isSolid();
    }

    private boolean isValidChestSpot(Location loc) {
        Block block = loc.getBlock();
        if (!isReplaceable(block.getType())) return false;
        Block below = block.getRelative(BlockFace.DOWN);
        Material belowType = below.getType();
        if (belowType == Material.WATER || belowType == Material.LAVA) return false;
        return belowType.isSolid();
    }

    // ============================================================
    // Эффекты
    // ============================================================
    private void playEffects(Player player) {
        Location loc = player.getLocation();

        if (plugin.getConfig().getBoolean("effects.sound.enabled", true)) {
            String name = plugin.getConfig().getString("effects.sound.name", "entity.player.levelup");
            float volume = (float) plugin.getConfig().getDouble("effects.sound.volume", 1.0);
            float pitch  = (float) plugin.getConfig().getDouble("effects.sound.pitch", 1.0);
            try {
                player.playSound(Sound.sound(Key.key(name), Sound.Source.MASTER, volume, pitch));
            } catch (Exception e) {
                plugin.getLogger().warning("Неверный звук в config: " + name);
            }
        }

        if (plugin.getConfig().getBoolean("effects.particle.enabled", true)) {
            String typeName = plugin.getConfig().getString("effects.particle.type", "HAPPY_VILLAGER");
            int count = plugin.getConfig().getInt("effects.particle.count", 60);
            double radius = plugin.getConfig().getDouble("effects.particle.radius", 2.5);

            Particle particle = Registry.PARTICLE_TYPE.get(
                    NamespacedKey.minecraft(typeName.toLowerCase(Locale.ROOT)));
            if (particle == null) {
                plugin.getLogger().warning("Неизвестная частица: " + typeName);
            } else {
                for (int i = 0; i < count; i++) {
                    double angle = 2 * Math.PI * i / count;
                    double x = Math.cos(angle) * radius;
                    double z = Math.sin(angle) * radius;
                    Location pl = loc.clone().add(x, 0.3, z);
                    player.spawnParticle(particle, pl, 1, 0, 0, 0, 0);
                }
            }
        }

        if (plugin.getConfig().getBoolean("effects.firework.enabled", true)) {
            spawnFireworks(loc);
        }
    }

    private void spawnFireworks(Location loc) {
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("effects.firework");
        if (sec == null) return;

        int count = sec.getInt("count", 3);
        int power = Math.max(0, Math.min(3, sec.getInt("power", 1)));
        boolean flicker = sec.getBoolean("flicker", true);
        boolean trail = sec.getBoolean("trail", true);

        List<Color> colors = new ArrayList<>();
        for (String hex : sec.getStringList("colors")) {
            try {
                colors.add(Color.fromRGB(Integer.parseInt(hex.replace("#", ""), 16)));
            } catch (NumberFormatException e) {
                plugin.getLogger().warning("Некорректный цвет фейерверка: " + hex);
            }
        }
        if (colors.isEmpty()) colors.add(Color.YELLOW);

        FireworkEffect.Type effectType;
        try {
            effectType = FireworkEffect.Type.valueOf(
                    sec.getString("type", "BALL_LARGE").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            effectType = FireworkEffect.Type.BALL_LARGE;
        }

        World world = loc.getWorld();
        if (world == null) return;

        for (int i = 0; i < count; i++) {
            double dx = (Math.random() - 0.5) * 3;
            double dz = (Math.random() - 0.5) * 3;
            Location fwLoc = loc.clone().add(dx, 0.5, dz);

            Firework fw = world.spawn(fwLoc, Firework.class);
            FireworkMeta meta = fw.getFireworkMeta();
            meta.addEffect(FireworkEffect.builder()
                    .withColor(colors)
                    .with(effectType)
                    .flicker(flicker)
                    .trail(trail)
                    .build());
            meta.setPower(power);
            fw.setFireworkMeta(meta);
        }
    }

    // ============================================================
    // Инвентарь
    // ============================================================
    private boolean isBypassing(Player player) {
        return player.getGameMode() == GameMode.CREATIVE
                || player.getGameMode() == GameMode.SPECTATOR;
    }

    private boolean hasItems(Player player, Map<Material, Integer> required) {
        Map<Material, Integer> have = countItems(player);
        for (Map.Entry<Material, Integer> e : required.entrySet()) {
            if (have.getOrDefault(e.getKey(), 0) < e.getValue()) return false;
        }
        return true;
    }

    private Map<Material, Integer> countItems(Player player) {
        Map<Material, Integer> counts = new HashMap<>();
        for (ItemStack stack : player.getInventory().getStorageContents()) {
            if (stack == null || stack.isEmpty()) continue;
            counts.merge(stack.getType(), stack.getAmount(), Integer::sum);
        }
        return counts;
    }

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
                if (left <= 0) contents[i] = null;
                else stack.setAmount(left);
            }
        }
        inv.setStorageContents(contents);
    }

    // ============================================================
    // Форматирование
    // ============================================================
    private String formatCostWithStatus(Player player, Map<Material, Integer> required) {
        Map<Material, Integer> have = countItems(player);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : required.entrySet()) {
            Material type = e.getKey();
            int needed = e.getValue();
            int has = have.getOrDefault(type, 0);
            int deficit = Math.max(0, needed - has);
            String color = deficit == 0 ? "<green>" : "<red>";
            parts.add(color + deficit + " " + plugin.getMaterialName(type));
        }
        return String.join("<gray>, ", parts);
    }

    private String miniToLegacy(String s) {
        if (s == null) return "";
        return LegacyComponentSerializer.legacyAmpersand()
                .serialize(miniMessage.deserialize(s));
    }
}