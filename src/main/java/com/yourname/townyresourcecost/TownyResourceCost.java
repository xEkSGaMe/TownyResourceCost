package com.yourname.townyresourcecost;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TownyResourceCost extends JavaPlugin {

    private static TownyResourceCost instance;
    private final Map<Material, Integer> requiredItems = new LinkedHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadRequirements();
        getServer().getPluginManager().registerEvents(new TownCreationListener(this), this);
        registerCommands();
        getLogger().info("TownyResourceCost enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("TownyResourceCost disabled.");
    }

    public static TownyResourceCost getInstance() {
        return instance;
    }

    /** Неизменяемая карта требований: материал -> количество. */
    public Map<Material, Integer> getRequiredItems() {
        return Collections.unmodifiableMap(requiredItems);
    }

    public void loadRequirements() {
        requiredItems.clear();
        ConfigurationSection costSection = getConfig().getConfigurationSection("cost");
        if (costSection == null) {
            getLogger().warning("Секция 'cost' не найдена в config.yml");
            return;
        }
        for (String key : costSection.getKeys(false)) {
            Material material;
            try {
                material = Material.valueOf(key.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                getLogger().warning("Неизвестный материал в config.yml: " + key);
                continue;
            }
            if (!material.isItem()) {
                getLogger().warning("Материал " + key + " не является предметом, пропускаем.");
                continue;
            }
            int amount = costSection.getInt(key);
            if (amount <= 0) {
                getLogger().warning("Некорректное количество для " + key + ": " + amount);
                continue;
            }
            // Суммируем возможные дубликаты (разный регистр ключей и т.п.)
            requiredItems.merge(material, amount, Integer::sum);
        }
        if (requiredItems.isEmpty()) {
            getLogger().warning("Список требований пуст — стоимость создания города не будет взиматься.");
        } else {
            getLogger().info("Loaded " + requiredItems.size() + " resource requirements.");
        }
    }

    /** Парсит строку в Component. null-safe. */
    public Component parse(String miniMessageString) {
        if (miniMessageString == null) return Component.empty();
        return miniMessage.deserialize(miniMessageString);
    }

    /** Регистрация команды через Brigadier (современный способ Paper). */
    private void registerCommands() {
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            final Commands commands = event.registrar();

            LiteralCommandNode<CommandSourceStack> root = Commands.literal("townyresourcecost")
                    .requires(src -> src.getSender().hasPermission("townyresourcecost.admin"))
                    .then(Commands.literal("reload")
                            .executes(ctx -> {
                                CommandSender sender = ctx.getSource().getSender();
                                reloadConfig();
                                loadRequirements();
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