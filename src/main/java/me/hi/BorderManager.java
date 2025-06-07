package me.hi;

import org.bukkit.Location;
import org.bukkit.World;

import java.util.HashSet;
import java.util.Set;

public class BorderManager {
    private int minX, maxX, minZ, maxZ;

    public BorderManager(int minX, int maxX, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    public enum BorderStatus {
        INSIDE,
        GLITCHED_NEAR,     // <5 blocks outside
        GLITCHED_FAR,      // >5 blocks outside
        IMMOBILIZE         // (Optional: stuck outside, can't move)
    }

    public BorderStatus getPlayerBorderStatus(Location loc) {
        double x = loc.getX(), z = loc.getZ();
        double distOut = 0;
        if (x < minX) distOut = minX - x;
        if (x > maxX) distOut = x - maxX;
        if (z < minZ) distOut = minZ - z;
        if (z > maxZ) distOut = z - maxZ;

        if (distOut == 0) return BorderStatus.INSIDE;
        if (distOut > 5) return BorderStatus.GLITCHED_FAR;
        if (distOut > 0) return BorderStatus.GLITCHED_NEAR;
        return BorderStatus.INSIDE;
    }

    public Location getSafeLocationInsideBorder(Location from, int buffer) {
        double x = from.getX();
        double z = from.getZ();

        if (x < minX) x = minX + buffer;
        if (x > maxX) x = maxX - buffer;
        if (z < minZ) z = minZ + buffer;
        if (z > maxZ) z = maxZ - buffer;

        return new Location(from.getWorld(), x, from.getY(), z, from.getYaw(), from.getPitch());
    }

    public double distanceToBorder(Location loc) {
        double dx = Math.min(Math.abs(loc.getX() - minX), Math.abs(loc.getX() - maxX));
        double dz = Math.min(Math.abs(loc.getZ() - minZ), Math.abs(loc.getZ() - maxZ));
        return Math.min(dx, dz);
    }

    public boolean isNearBorder(Location loc) {
        double x = loc.getX();
        double z = loc.getZ();
        return x <= minX + 7 || x >= maxX - 7 || z <= minZ + 7 || z >= maxZ - 7;
    }

    // Generate all glass locations for the player
    public Set<Location> getGlassBorderLocations(Location playerLoc, int radius) {
        Set<Location> locs = new HashSet<>();
        World world = playerLoc.getWorld();
        double x = playerLoc.getX(), z = playerLoc.getZ();
        double y = playerLoc.getY();

        // For each border, check if player is close enough, add vertical wall
        for (int dy = 0; dy < 7; dy++) {
            for (int i = -3; i <= 3; i++) {
                if (Math.abs(x - minX) < radius)
                    locs.add(new Location(world, minX, y + dy, z + i));
                if (Math.abs(x - maxX) < radius)
                    locs.add(new Location(world, maxX, y + dy, z + i));
                if (Math.abs(z - minZ) < radius)
                    locs.add(new Location(world, x + i, y + dy, minZ));
                if (Math.abs(z - maxZ) < radius)
                    locs.add(new Location(world, x + i, y + dy, maxZ));
            }
        }
        return locs;
    }
}