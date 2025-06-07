package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitRunnable;

public class BorderShrinkTask extends BukkitRunnable {
    private final BorderManager borderManager;
    private final World world;
    private final int[][] shrinkPhases = {
            {2000, 750},  // size, duration seconds
            {1500, 750},
            {1000, 750},
            {500, 750},
            {100, 300},
            {50, 300},
            {25, 180}
    };
    private int phase = 0;

    public BorderShrinkTask(BorderManager borderManager, World world) {
        this.borderManager = borderManager;
        this.world = world;
    }

    @Override
    public void run() {
        if (phase < shrinkPhases.length) {
            int size = shrinkPhases[phase][0];
            int duration = shrinkPhases[phase][1];
            borderManager.setBorderSize(size); // Update your border ranges
            world.getWorldBorder().setSize(size, duration);
            Bukkit.broadcastMessage("§eBorder is now " + size + "x" + size + ", shrinking over " + (duration/60) + " min!");
            phase++;
        } else {
            this.cancel();
            Bukkit.broadcastMessage("§aBorder shrinking complete!");
        }
    }
}