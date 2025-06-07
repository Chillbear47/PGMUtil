package me.hi;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.player.PlayerMoveEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class GhostBorderListener extends PacketListenerAbstract {

    private final BorderManager borderManager;

    // Store for each player the set of glass locations currently faked
    private final Set<UUID> playersWithGlass = new HashSet<>();

    public GhostBorderListener(BorderManager borderManager) {
        super(PacketListenerPriority.NORMAL);
        this.borderManager = borderManager;
    }

    @Override
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
        // For each border direction, if player is within 7 blocks, send 7x4 "wall" of red glass
        Set<Location> glassBlocks = borderManager.getGlassBorderLocations(player.getLocation(), 7);
        for (Location loc : glassBlocks) {
            PacketEvents.get().getServerUtils().sendBlockChange(
                    player,
                    loc,
                    Material.RED_STAINED_GLASS,
                    (byte) 14 // Red glass data value in 1.8–1.12, not needed for 1.13+
            );
        }
    }

    private void removeGhostGlass(Player player, World world) {
        // Replace glass with actual world blocks
        Set<Location> glassBlocks = borderManager.getGlassBorderLocations(player.getLocation(), 7);
        for (Location loc : glassBlocks) {
            Material realMat = world.getBlockAt(loc).getType();
            byte realData = world.getBlockAt(loc).getData();
            PacketEvents.get().getServerUtils().sendBlockChange(
                    player,
                    loc,
                    realMat,
                    realData
            );
        }
    }
}