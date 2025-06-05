package me.hi;

import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.destroyable.DestroyableMatchModule;
import tc.oc.pgm.destroyable.Destroyable;
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
        MatchPlayer matchPlayer = MatchPlayer.get(event.getPlayer());
        if (matchPlayer != null) {
            updateCompass(matchPlayer);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        MatchPlayer matchPlayer = MatchPlayer.get(event.getPlayer());
        if (matchPlayer != null) {
            updateCompass(matchPlayer);
        }
    }

    public void updateCompass(MatchPlayer player) {
        Match match = player.getMatch();
        if (match == null) return;

        // Only proceed if this match is DTM
        DestroyableMatchModule dtm = match.getModule(DestroyableMatchModule.class);
        if (dtm == null) return;

        Team playerTeam = (Team) player.getParty();
        if (playerTeam == null || playerTeam.isObserving()) return;

        // Get all enemy monuments not yet destroyed
        List<Destroyable> enemyMonuments = dtm.getDestroyables().stream()
                .filter(obj -> obj.getOwner() != null && obj.getOwner() != playerTeam)
                .filter(obj -> !obj.isDestroyed())
                .collect(Collectors.toList());

        if (enemyMonuments.isEmpty()) return;

        // Find the closest monument
        Location playerLoc = player.getBukkit().getLocation();
        Destroyable closest = enemyMonuments.stream()
                .min(Comparator.comparingDouble(obj -> obj.getBlockRegion().getCenter().distance(playerLoc)))
                .orElse(null);

        if (closest != null) {
            player.getBukkit().setCompassTarget(closest.getBlockRegion().getCenter());
        }
    }
}