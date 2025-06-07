package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import tc.oc.pgm.api.match.event.MatchStartEvent;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.blitz.BlitzMatchModule;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * This class merges BlitzUHC border logic and auto-enables it for PGM matches with gamemode "blitz".
 * Register this as a listener from your plugin's onEnable.
 */
public class BlitzUHC implements Listener {

    private BorderManager borderManager;
    private BorderShrinkTask borderShrinkTask;
    private final Set<UUID> playersWithGlass = new HashSet<>();

    private JavaPlugin plugin; // Needed for scheduling/running tasks

    // Call this in your plugin's onEnable, passing your plugin instance
    public BlitzUHC(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /** Listen for the start of a PGM match and only initialize for Blitz. */
    @EventHandler
    public void onMatchStart(MatchStartEvent event) {
        Match match = event.getMatch();

        // Only setup border if this is a blitz match
        BlitzMatchModule blitz = match.getModule(BlitzMatchModule.class);
        if (blitz == null) return;

        World world = Bukkit.getWorld(match.getWorld().getName());
        if (world == null) {
            Bukkit.getLogger().warning("[PGMUtil] Could not find world: " + match.getWorld().getName());
            return;
        }

        int minX = -1100, minZ = 857;
        int maxX = 900, maxZ = 2857;

        borderManager = new BorderManager(minX, maxX, minZ, maxZ);
        BorderUtil.generateBedrockBorder(world, minX, minZ, maxX, maxZ);

        // Setup Bukkit WorldBorder as well (make it huge and out of the way to hide vanilla border visual)
        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(30000);

        // Schedule border shrinking logic (now using correct time-based phase changes)
        this.borderShrinkTask = new BorderShrinkTask(borderManager, world, plugin);
        borderShrinkTask.startShrinkPhase(0); // custom, see new class below

        // Register this for PlayerMoveEvent, if not already registered
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // PlayerMoveEvent: handles both enforcement and ghost glass
    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (borderManager == null) return; // Only if blitz border is active

        Player player = event.getPlayer();
        Location loc = player.getLocation();

        // Border enforcement (kick back or nudge if outside)
        BorderManager.BorderStatus status = borderManager.getPlayerBorderStatus(loc);

        if (status == BorderManager.BorderStatus.INSIDE) {
            // Continue to ghost glass logic below
        } else if (status == BorderManager.BorderStatus.GLITCHED_FAR) {
            // Teleport player 2 blocks inside border
            Location safeLoc = borderManager.getSafeLocationInsideBorder(loc, 2);
            player.teleport(safeLoc);
            player.sendMessage("§cYou were teleported back inside the border!");
            return;
        } else if (status == BorderManager.BorderStatus.GLITCHED_NEAR) {
            // Nudge player inside border
            Location nudgeLoc = borderManager.getSafeLocationInsideBorder(loc, 1);
            player.setVelocity(nudgeLoc.toVector().subtract(loc.toVector()).normalize().multiply(0.4));
            //player.sendMessage("§cYou are outside the border, nudging you back in!");
            return;
        } else if (status == BorderManager.BorderStatus.IMMOBILIZE) {
            // Freeze player if outside and stuck (optional)
            event.setCancelled(true);
            player.sendMessage("§cYou cannot move outside the border!");
            return;
        }

        // Ghost border logic
        double dist = borderManager.distanceToBorder(loc);
        if (dist <= 7.0 && borderManager.isNearBorder(loc)) {
            if (!playersWithGlass.contains(player.getUniqueId())) {
                playersWithGlass.add(player.getUniqueId());
                showGhostGlass(player);
            }
        } else {
            if (!playersWithGlass.contains(player.getUniqueId())) {
                playersWithGlass.add(player.getUniqueId());
                removeGhostGlass(player);
            }
        }
    }

    // --- BorderManager LOGIC ---
    private static class BorderManager {
        private int minX, maxX, minZ, maxZ;

        public BorderManager(int minX, int maxX, int minZ, int maxZ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
        }

        enum BorderStatus {
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
        // Generate all glass locations for the player, full vertical wall from y=0 to y=surface+5
        public Set<Location> getGlassBorderLocations(Location playerLoc, int radius) {
            Set<Location> locs = new HashSet<>();
            World world = playerLoc.getWorld();
            int playerX = playerLoc.getBlockX();
            int playerZ = playerLoc.getBlockZ();

            // North wall (z = minZ)
            if (Math.abs(playerZ - minZ) <= radius) {
                for (int x = minX; x <= maxX; x++) {
                    int surfaceY = world.getHighestBlockYAt(x, minZ);
                    for (int y = surfaceY + 1; y <= surfaceY + 2; y++) {
                        locs.add(new Location(world, x, y, minZ));
                    }
                }
            }
            // South wall (z = maxZ)
            if (Math.abs(playerZ - maxZ) <= radius) {
                for (int x = minX; x <= maxX; x++) {
                    int surfaceY = world.getHighestBlockYAt(x, maxZ);
                    for (int y = surfaceY + 1; y <= surfaceY + 2; y++) {
                        locs.add(new Location(world, x, y, maxZ));
                    }
                }
            }
            // West wall (x = minX)
            if (Math.abs(playerX - minX) <= radius) {
                for (int z = minZ + 1; z <= maxZ - 1; z++) {
                    int surfaceY = world.getHighestBlockYAt(minX, z);
                    for (int y = surfaceY + 1; y <= surfaceY + 2; y++) {
                        locs.add(new Location(world, minX, y, z));
                    }
                }
            }
            // East wall (x = maxX)
            if (Math.abs(playerX - maxX) <= radius) {
                for (int z = minZ + 1; z <= maxZ - 1; z++) {
                    int surfaceY = world.getHighestBlockYAt(maxX, z);
                    for (int y = surfaceY + 1; y <= surfaceY + 2; y++) {
                        locs.add(new Location(world, maxX, y, z));
                    }
                }
            }
            return locs;
        }

        public void setBorderSize(int size) {
            // Center of the border, adjust if needed
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            int half = size / 2;
            this.minX = centerX - half;
            this.maxX = centerX + half;
            this.minZ = centerZ - half;
            this.maxZ = centerZ + half;
        }
    }

    // --- BorderShrinkTask LOGIC ---
    private static class BorderShrinkTask {
        private final BorderManager borderManager;
        private final World world;
        private final JavaPlugin plugin;
        private final int[][] shrinkPhases = {
                {2000, 750},  // size, duration seconds (12.5min)
                {1500, 750},
                {1000, 750},
                {500, 750},
                {100, 300},   // 5 min
                {50, 300},
                {25, 180}     // 3 min
        };

        public BorderShrinkTask(BorderManager borderManager, World world, JavaPlugin plugin) {
            this.borderManager = borderManager;
            this.world = world;
            this.plugin = plugin;
        }

        // Recursively schedules each shrink phase after the previous one is done
        public void startShrinkPhase(int phase) {
            if (phase >= shrinkPhases.length) {
                Bukkit.broadcastMessage("§aBorder shrinking complete!");
                return;
            }
            int size = shrinkPhases[phase][0];
            int duration = shrinkPhases[phase][1];
            borderManager.setBorderSize(size);
            BorderUtil.generateBedrockBorder(world, borderManager.minX, borderManager.minZ, borderManager.maxX, borderManager.maxZ);
            Bukkit.broadcastMessage("§eBorder is now " + size + "x" + size + ", shrinking over " + (duration/60) + " min!");

            // For ghost border only: do NOT animate vanilla border, keep it invisible/huge
            // If you want vanilla border visual to animate as well, uncomment below:
            // world.getWorldBorder().setCenter((borderManager.minX + borderManager.maxX) / 2.0, (borderManager.minZ + borderManager.maxZ) / 2.0);
            // world.getWorldBorder().setSize(size, duration);

            // Schedule next phase after 'duration' seconds
            new BukkitRunnable() {
                @Override
                public void run() {
                    startShrinkPhase(phase + 1);
                }
            }.runTaskLater(plugin, duration * 20L); // duration in seconds -> ticks
        }
    }

    // --- BorderUtil (BEDROCK border generation) ---
    private static class BorderUtil {
        /**
         * Lays a 1 block thick, 5 block tall bedrock border above the topmost natural block,
         * and all the way down to y=0, overwriting all blocks on its way.
         */
        public static void generateBedrockBorder(World world, int x1, int z1, int x2, int z2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);

            int worldMinY = 0;
            int worldMaxY = world.getMaxHeight(); // usually 256

            // North and South walls
            for (int x = minX; x <= maxX; x++) {
                setBedrockWall(world, x, minZ, worldMinY, worldMaxY);
                setBedrockWall(world, x, maxZ, worldMinY, worldMaxY);
            }

            // West and East walls
            for (int z = minZ + 1; z <= maxZ - 1; z++) { // avoid corners being set twice
                setBedrockWall(world, minX, z, worldMinY, worldMaxY);
                setBedrockWall(world, maxX, z, worldMinY, worldMaxY);
            }
        }

        /**
         * Overwrites all blocks from y=0 up to the topmost natural block at (x,z) with bedrock,
         * and then places a 5 tall vertical bedrock wall above the surface.
         */
        private static void setBedrockWall(World world, int x, int z, int minY, int maxY) {
            // 1. Fill from minY up to the surface with bedrock (y=0 to y=surfaceY)
            int surfaceY = world.getHighestBlockYAt(x, z);

            for (int y = minY; y <= surfaceY; y++) {
                Block block = world.getBlockAt(x, y, z);
                block.setType(Material.BEDROCK, false);
            }

            // 2. Place 4 blocks of bedrock above the surface
            for (int y = surfaceY + 1; y <= surfaceY + 1; y++) {
                if (y <= maxY) {
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(Material.BEDROCK, false);
                }
            }
        }
    }

    // --- Ghost Border utility ---
    // Replace showGhostGlass and removeGhostGlass methods with:
    private void showGhostGlass(Player player) {
        Set<Location> glassBlocks = borderManager.getGlassBorderLocations(player.getLocation(), 7);
        for (Location loc : glassBlocks) {
            sendFakeBlock(player, loc, Material.RED_STAINED_GLASS);
        }
    }

    private void removeGhostGlass(Player player) {
        Set<Location> glassBlocks = borderManager.getGlassBorderLocations(player.getLocation(), 7);
        for (Location loc : glassBlocks) {
            // show real block underneath
            sendFakeBlock(player, loc, loc.getWorld().getBlockAt(loc).getType());
        }
    }

    // Use Bukkit API for fake blocks, safe and version-tolerant
    private void sendFakeBlock(Player player, Location loc, Material material) {
        player.sendBlockChange(loc, material.createBlockData());
    }
}