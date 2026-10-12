package com.yourname.townyresourcecost;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TownyResourceCost extends JavaPlugin {

    private static TownyResourceCost instance;
    private final List<ItemStack> requiredItems = new ArrayList<>();

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

    /** Возвращает неизменяемый список, чтобы внешний код не мог мутировать требования. */
    public List<ItemStack> getRequiredItems() {
        return Collections.unmodifiableList(requiredItems);
    }

    public void loadRequirements() {
        requiredItems.clear();
        ConfigurationSection costSection = getConfig().getConfigurationSection("cost");
        if (costSection == null) {
            getLogger().warning("No 'cost' section found in config.yml");
            return;
        }
        for (String key : costSection.getKeys(false)) {
            try {
                Material material = Material.valueOf(key.toUpperCase());
                int amount = costSection.getInt(key);
                if (amount > 0) {
                    requiredItems.add(new ItemStack(material, amount));
                }
            } catch (IllegalArgumentException e) {
                getLogger().warning("Неизвестный материал в config.yml: " + key);
            }
        }
        getLogger().info("Loaded " + requiredItems.size() + " resource requirements.");
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
                                sender.sendMessage(color(getConfig().getString("messages.reloaded",
                                        "&aКонфигурация TownyResourceCost успешно перезагружена.")));
                                return Command.SINGLE_SUCCESS;
                            }))
                    // если ввели без аргументов / или с неизвестным — подсказка
                    .executes(ctx -> {
                        CommandSender sender = ctx.getSource().getSender();
                        sender.sendMessage(color(getConfig().getString("messages.unknown-subcommand",
                                        "&eНеизвестная подкоманда. Используйте /%label% reload")
                                .replace("%label%", "townyresourcecost")));
                        return Command.SINGLE_SUCCESS;
                    })
                    .build();

            commands.register(root, "Reload TownyResourceCost configuration", List.of("trc"));
        });
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}