package me.hi;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.destroyable.DestroyableMatchModule;
import tc.oc.pgm.teams.Team;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

public class MonumentTracker implements Listener {

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        updateCompass((MatchPlayer) event.getPlayer());
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        updateCompass((MatchPlayer) event.getPlayer());
    }

    public void updateCompass(MatchPlayer player) {
        Match match = player.getMatch();
        if (match == null) return;

        // Only proceed if this match is DTM
        DestroyableMatchModule dtm = match.getModule(DestroyableMatchModule.class);
        if (dtm == null) return;

        Team playerTeam = (Team) player.getParty();
        if (playerTeam == null || playerTeam.isObserver()) return;

        // Get all enemy monuments not yet destroyed
        List<DestroyableMatchModule> enemyMonuments = dtm.getObjectives().stream()
                .filter(obj -> obj.getOwner() != null && obj.getOwner() != playerTeam)
                .filter(obj -> !obj.isDestroyed())
                .collect(Collectors.toList());

        if (enemyMonuments.isEmpty()) return;

        // Find the closest monument
        Location playerLoc = player.getBukkit().getLocation();
        DestroyableMatchModule closest = enemyMonuments.stream()
                .min(Comparator.comparingDouble(obj -> obj.getLocation().distance(playerLoc)))
                .orElse(null);

        if (closest != null) {
            player.getBukkit().setCompassTarget(closest.getLocation());
        }
    }
}