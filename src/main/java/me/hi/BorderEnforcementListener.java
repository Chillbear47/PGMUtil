package me.hi;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public class BorderEnforcementListener extends PacketListenerAbstract {
    private final BorderManager borderManager;

    public BorderEnforcementListener(BorderManager borderManager) {
        super(PacketListenerPriority.NORMAL);
        this.borderManager = borderManager;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location loc = player.getLocation();
        BorderManager.BorderStatus status = borderManager.getPlayerBorderStatus(loc);

        if (status == BorderManager.BorderStatus.INSIDE) {
            // Do nothing
            return;
        }

        if (status == BorderManager.BorderStatus.GLITCHED_FAR) {
            // Teleport player 2 blocks inside border
            Location safeLoc = borderManager.getSafeLocationInsideBorder(loc, 2);
            player.teleport(safeLoc);
            player.sendMessage("§cYou were teleported back inside the border!");
        } else if (status == BorderManager.BorderStatus.GLITCHED_NEAR) {
            // Nudge player inside border
            Location nudgeLoc = borderManager.getSafeLocationInsideBorder(loc, 1);
            player.setVelocity(nudgeLoc.toVector().subtract(loc.toVector()).normalize().multiply(0.4));
            player.sendMessage("§cYou are outside the border, nudging you back in!");
        } else if (status == BorderManager.BorderStatus.IMMOBILIZE) {
            // Freeze player if outside and stuck (optional)
            event.setCancelled(true);
            player.sendMessage("§cYou cannot move outside the border!");
        }
    }
}