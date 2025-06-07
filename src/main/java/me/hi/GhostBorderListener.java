package me.hi;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GhostBorderListener implements Listener {

    private final BorderManager borderManager;

    // Store for each player the set of glass locations currently faked
    private final Set<UUID> playersWithGlass = new HashSet<>();

    public GhostBorderListener(BorderManager borderManager) {
        this.borderManager = borderManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();

        // Check distance to border
        double dist = borderManager.distanceToBorder(loc);
        if (dist <= 7.0 && borderManager.isNearBorder(loc)) {
            if (!playersWithGlass.contains(player.getUniqueId())) {
                playersWithGlass.add(player.getUniqueId());
                showGhostGlass(player, loc.getWorld());
            }
        } else {
            if (playersWithGlass.contains(player.getUniqueId())) {
                playersWithGlass.remove(player.getUniqueId());
                removeGhostGlass(player, loc.getWorld());
            }
        }
    }

    private void showGhostGlass(Player player, World world) {
        Set<Location> glassBlocks = borderManager.getGlassBorderLocations(player.getLocation(), 7);
        for (Location loc : glassBlocks) {
            sendFakeBlock(player, loc, Material.RED_STAINED_GLASS);
        }
    }

    private void removeGhostGlass(Player player, World world) {
        Set<Location> glassBlocks = borderManager.getGlassBorderLocations(player.getLocation(), 7);
        for (Location loc : glassBlocks) {
            sendFakeBlock(player, loc, world.getBlockAt(loc).getType());
        }
    }

    // Use Bukkit's safe API for sending fake blocks
    private void sendFakeBlock(Player player, Location loc, Material material) {
        player.sendBlockChange(loc, material.createBlockData());
    }
}