package com.yourname.townyresourcecost;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TownyResourceCost extends JavaPlugin {

    private static TownyResourceCost instance;

    /** Требования на создание города: материал -> количество. */
    private final Map<Material, Integer> requiredItems = new LinkedHashMap<>();

    /** Русские имена материалов для сообщений. */
    private final Map<Material, String> materialNames = new HashMap<>();

    /** Содержимое стартового сундука (готовые ItemStack с NBT). */
    private final List<ItemStack> startingChestItems = new ArrayList<>();

    /** Игроки, инициировавшие создание города: имя города (lower) -> Player. */
    private final Map<String, Player> pendingTownCreators = new ConcurrentHashMap<>();

    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        reloadAll();
        getServer().getPluginManager().registerEvents(new TownCreationListener(this), this);
        registerCommands();
        getLogger().info("TownyResourceCost enabled.");
    }

    @Override
    public void onDisable() {
        pendingTownCreators.clear();
        getLogger().info("TownyResourceCost disabled.");
    }

    public static TownyResourceCost getInstance() {
        return instance;
    }

    /** Полная перезагрузка всех данных из конфига. */
    public void reloadAll() {
        loadRequirements();
        loadMaterialNames();
        loadStartingChest();
    }

    // ============================================================
    // Геттеры
    // ============================================================

    public Map<Material, Integer> getRequiredItems() {
        return Collections.unmodifiableMap(requiredItems);
    }

    /** Возвращает копии, чтобы внешний код случайно не мутировал шаблон. */
    public List<ItemStack> getStartingChestItems() {
        List<ItemStack> copy = new ArrayList<>(startingChestItems.size());
        for (ItemStack s : startingChestItems) copy.add(s.clone());
        return copy;
    }

    /** Русское имя материала или Material.name() как фолбэк. */
    public String getMaterialName(Material material) {
        return materialNames.getOrDefault(material, material.name());
    }

    /** Парсит строку MiniMessage в Component. null-safe. */
    public Component parse(String miniMessageString) {
        if (miniMessageString == null) return Component.empty();
        return miniMessage.deserialize(miniMessageString);
    }

    // ============================================================
    // Хранилище «кто создаёт город»
    // ============================================================

    /** Запомнить игрока, который инициировал создание города (из PreNewTownEvent). */
    public void rememberTownCreator(String townName, Player player) {
        if (townName == null || player == null) return;
        pendingTownCreators.put(townName.toLowerCase(Locale.ROOT), player);
    }

    /** Достать и удалить запомненного игрока (в NewTownEvent). */
    public Player consumeTownCreator(String townName) {
        if (townName == null) return null;
        return pendingTownCreators.remove(townName.toLowerCase(Locale.ROOT));
    }

    // ============================================================
    // Загрузка: требования
    // ============================================================

    public void loadRequirements() {
        requiredItems.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("cost");
        if (sec == null) {
            getLogger().warning("Секция 'cost' не найдена в config.yml");
            return;
        }
        for (String key : sec.getKeys(false)) {
            Material mat = parseMaterial(key);
            if (mat == null) continue;
            int amount = sec.getInt(key);
            if (amount <= 0) {
                getLogger().warning("Некорректное количество для " + key + ": " + amount);
                continue;
            }
            requiredItems.merge(mat, amount, Integer::sum);
        }
        if (requiredItems.isEmpty()) {
            getLogger().warning("Список требований пуст — стоимость не будет взиматься.");
        } else {
            getLogger().info("Loaded " + requiredItems.size() + " resource requirements.");
        }
    }

    // ============================================================
    // Загрузка: русские имена материалов
    // ============================================================

    private void loadMaterialNames() {
        materialNames.clear();
        ConfigurationSection sec = getConfig().getConfigurationSection("material-names");
        if (sec == null) return;
        for (String key : sec.getKeys(false)) {
            Material mat = parseMaterial(key);
            if (mat == null) continue;
            String name = sec.getString(key);
            if (name == null || name.isBlank()) continue;
            materialNames.put(mat, name);
        }
    }

    // ============================================================
    // Загрузка: стартовый сундук (map + list форматы)
    // ============================================================

    private void loadStartingChest() {
        startingChestItems.clear();
        if (!getConfig().getBoolean("starting-chest.enabled", false)) return;

        Object raw = getConfig().get("starting-chest.contents");
        if (raw instanceof ConfigurationSection sec) {
            loadChestMap(sec);          // старый формат: MATERIAL: amount
        } else if (raw instanceof List<?> list) {
            loadChestList(list);        // новый формат: список с enchantments
        } else if (raw != null) {
            getLogger().warning("Неверный формат starting-chest.contents "
                    + "(ожидается список или map): " + raw.getClass().getSimpleName());
        }

        if (startingChestItems.size() > 27) {
            getLogger().warning("Стартовый сундук: " + startingChestItems.size()
                    + " стаков > 27, лишнее обрежется при выдаче.");
        }
    }

    /** Старый формат: cost-подобная карта MATERIAL: amount. */
    private void loadChestMap(ConfigurationSection sec) {
        for (String key : sec.getKeys(false)) {
            Material mat = parseMaterial(key);
            if (mat == null) continue;
            int total = sec.getInt(key);
            if (total <= 0) continue;
            addStacks(mat, total, null);
        }
    }

    /** Новый формат: список элементов с material / amount / enchantments. */
    private void loadChestList(List<?> list) {
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> map)) {
                getLogger().warning("Элемент starting-chest.contents не является map: " + obj);
                continue;
            }

            Object matRaw = map.get("material");
            if (matRaw == null) {
                getLogger().warning("Элемент starting-chest.contents без поля 'material'");
                continue;
            }
            Material mat = parseMaterial(matRaw.toString());
            if (mat == null) continue;

            int amount = 1;
            Object amtRaw = map.get("amount");
            if (amtRaw instanceof Number n) amount = n.intValue();
            if (amount <= 0) continue;

            Map<Enchantment, Integer> enchants = new LinkedHashMap<>();
            Object enchRaw = map.get("enchantments");
            if (enchRaw instanceof Map<?, ?> enchMap) {
                for (Map.Entry<?, ?> e : enchMap.entrySet()) {
                    Enchantment ench = parseEnchantment(e.getKey().toString());
                    if (ench == null) continue;
                    int lvl = (e.getValue() instanceof Number n) ? n.intValue() : 1;
                    if (lvl > 0) enchants.put(ench, lvl);
                }
            }

            addStacks(mat, amount, enchants);
        }
    }

    /**
     * Раскладывает total в стаки с учётом maxStackSize и накладывает зачарования.
     * Зачарованные предметы кладутся отдельными стаками (maxStackSize у инструментов = 1).
     */
    private void addStacks(Material mat, int total, Map<Enchantment, Integer> enchants) {
        int maxStack = mat.getMaxStackSize();
        if (maxStack <= 0) maxStack = 1;

        while (total > 0) {
            int amount = Math.min(total, maxStack);
            ItemStack stack = new ItemStack(mat, amount);

            if (enchants != null && !enchants.isEmpty()) {
                ItemMeta meta = stack.getItemMeta();
                if (meta != null) {
                    for (Map.Entry<Enchantment, Integer> e : enchants.entrySet()) {
                        // true = unsafe, позволяет уровни выше ванильного максимума
                        meta.addEnchant(e.getKey(), e.getValue(), true);
                    }
                    stack.setItemMeta(meta);
                }
            }

            startingChestItems.add(stack);
            total -= amount;
        }
    }

    // ============================================================
    // Парсеры
    // ============================================================

    private Material parseMaterial(String key) {
        if (key == null) return null;
        try {
            Material mat = Material.valueOf(key.toUpperCase(Locale.ROOT));
            if (!mat.isItem()) {
                getLogger().warning("Материал " + key + " не является предметом, пропускаем.");
                return null;
            }
            return mat;
        } catch (IllegalArgumentException e) {
            getLogger().warning("Неизвестный материал в config.yml: " + key);
            return null;
        }
    }

    private Enchantment parseEnchantment(String name) {
        if (name == null) return null;
        try {
            NamespacedKey key = NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT));
            Enchantment ench = Registry.ENCHANTMENT.get(key);
            if (ench == null) {
                getLogger().warning("Неизвестное зачарование: " + name);
            }
            return ench;
        } catch (Exception e) {
            getLogger().warning("Ошибка разбора зачарования '" + name + "': " + e.getMessage());
            return null;
        }
    }

    // ============================================================
    // Команды
    // ============================================================

    private void registerCommands() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            LiteralCommandNode<CommandSourceStack> root = Commands.literal("townyresourcecost")
                    .requires(src -> src.getSender().hasPermission("townyresourcecost.admin"))
                    .then(Commands.literal("reload")
                            .executes(ctx -> {
                                CommandSender sender = ctx.getSource().getSender();
                                reloadConfig();
                                reloadAll();
                                sender.sendMessage(parse(getConfig().getString(
                                        "messages.reloaded",
                                        "<green>Конфигурация TownyResourceCost успешно перезагружена")));
                                return Command.SINGLE_SUCCESS;
                            }))
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        sender.sendMessage(parse(getConfig().getString(
                                        "messages.unknown-subcommand",
                                        "<yellow>Неизвестная подкоманда. Используйте /%label% reload")
                                .replace("%label%", "townyresourcecost")));
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();

            commands.register(root, "Reload TownyResourceCost configuration", List.of("trc"));
        });
    }
}