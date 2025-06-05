package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import tc.oc.pgm.api.PGM; // Make sure PGM is a dependency in your build

public final class PGMUtil extends JavaPlugin {

    @Override
    public void onEnable() {
        // Plugin startup logic

        // Get the PGM MatchManager instance
        var matchManager = PGM.get().getMatchManager();

        // Pass matchManager to MonumentTracker's constructor
        Bukkit.getPluginManager().registerEvents(new MonumentTracker(matchManager), this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }
}