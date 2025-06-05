package me.hi;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.util.Vector;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.MatchManager;
import tc.oc.pgm.api.player.MatchPlayer;
import tc.oc.pgm.destroyable.Destroyable;
import tc.oc.pgm.destroyable.DestroyableMatchModule;
import tc.oc.pgm.teams.Team;
import tc.oc.pgm.regions.FiniteBlockRegion;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MonumentTracker - Tracks enemy monuments for a player and updates their compass to point to the nearest one.
 *
 * Make sure to provide a MatchManager instance to the constructor when registering this listener.
 */
public class MonumentTracker implements Listener {

    private final MatchManager matchManager;

    public MonumentTracker(MatchManager matchManager) {
        this.matchManager = matchManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Match match = matchManager.getMatch(event.getPlayer().getWorld());
        if (match == null) return;
        MatchPlayer matchPlayer = match.getPlayer(event.getPlayer());
        if (matchPlayer != null) {
            updateCompass(matchPlayer);
        }
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Match match = matchManager.getMatch(event.getPlayer().getWorld());
        if (match == null) return;
        MatchPlayer matchPlayer = match.getPlayer(event.getPlayer());
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
        // Correct observer check: use isObserving() from Party interface, implemented by Team
        if (playerTeam == null || playerTeam.isObserving()) return;

        // Get all enemy monuments not yet destroyed
        List<Destroyable> enemyMonuments = dtm.getDestroyables().stream()
                .filter(obj -> obj.getOwner() != null && obj.getOwner() != playerTeam)
                .filter(obj -> !obj.isDestroyed())
                .collect(Collectors.toList());

        if (enemyMonuments.isEmpty()) return;

        // Find the closest monument
        Location playerLoc = player.getBukkit().getLocation();
        World world = playerLoc.getWorld();
        Destroyable closest = enemyMonuments.stream()
                .min(Comparator.comparingDouble(obj -> getRegionCenter(obj.getBlockRegion(), world).distance(playerLoc)))
                .orElse(null);

        if (closest != null) {
            player.getBukkit().setCompassTarget(getRegionCenter(closest.getBlockRegion(), world));
        }
    }

    // Utility to calculate the center Location of a FiniteBlockRegion
    private Location getRegionCenter(FiniteBlockRegion region, World world) {
        Vector min = region.getBounds().getMin();
        Vector max = region.getBounds().getMax();
        double centerX = (min.getBlockX() + max.getBlockX()) / 2.0 + 0.5;
        double centerY = (min.getBlockY() + max.getBlockY()) / 2.0 + 0.5;
        double centerZ = (min.getBlockZ() + max.getBlockZ()) / 2.0 + 0.5;
        return new Location(world, centerX, centerY, centerZ);
    }
}