package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class PGMUtil extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic
        Bukkit.getPluginManager().registerEvents(new MonumentTracker(), this);




    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}
