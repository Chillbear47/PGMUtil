package me.hi;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.reflect.StructureModifier;
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

import java.util.*;

public class BlitzUHC implements Listener {

    private BorderManager borderManager;
    private BorderShrinkTask borderShrinkTask;
    private final Map<UUID, Set<Location>> playerGlassBlocks = new HashMap<>();
    private JavaPlugin plugin;
    private ProtocolManager protocolManager;

    public BlitzUHC(JavaPlugin plugin) {
        this.plugin = plugin;
        setupProtocolLib();
    }

    private void setupProtocolLib() {
        protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new PacketAdapter(plugin, ListenerPriority.HIGH,
                PacketType.Play.Client.BLOCK_DIG, PacketType.Play.Client.BLOCK_PLACE) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Player player = event.getPlayer();
                Set<Location> glassBlocks = playerGlassBlocks.get(player.getUniqueId());
                if (glassBlocks == null) return;

                StructureModifier<com.comphenix.protocol.wrappers.BlockPosition> posMod = event.getPacket().getBlockPositionModifier();
                if (posMod.size() > 0) {
                    com.comphenix.protocol.wrappers.BlockPosition pos = posMod.read(0);
                    Location loc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
                    if (glassBlocks.contains(loc)) {
                        event.setCancelled(true);
                        sendFakeBlock(player, loc, Material.STAINED_GLASS, (byte) 14);
                    }
                }
            }
        });
    }

    @EventHandler
    public void onMatchStart(MatchStartEvent event) {
        Match match = event.getMatch();

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

        world.getWorldBorder().setCenter(0, 0);
        world.getWorldBorder().setSize(30000);

        this.borderShrinkTask = new BorderShrinkTask(borderManager, world, plugin, this);
        borderShrinkTask.startShrinkPhase(0);

        Bukkit.getPluginManager().registerEvents(this, plugin);

        // Schedule periodic safe border check (every 5 seconds)
        new BukkitRunnable() {
            @Override
            public void run() {
                checkAndTeleportAllPlayersSafe();
            }
        }.runTaskTimer(plugin, 0L, 100L); // 5 seconds
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (borderManager == null) return;

        Player player = event.getPlayer();
        Location loc = player.getLocation();

        BorderManager.BorderStatus status = borderManager.getPlayerBorderStatus(loc);

        if (status == BorderManager.BorderStatus.INSIDE) {
            // Continue to ghost glass logic below
        } else if (status == BorderManager.BorderStatus.GLITCHED_FAR) {
            Location safeLoc = borderManager.getSafeSurfaceLocationInsideBorder(player.getWorld(), loc, 2);
            player.teleport(safeLoc);
            player.sendMessage("§cYou were teleported back inside the border!");
            return;
        } else if (status == BorderManager.BorderStatus.GLITCHED_NEAR) {
            Location nudgeLoc = borderManager.getSafeSurfaceLocationInsideBorder(player.getWorld(), loc, 1);
            player.setVelocity(nudgeLoc.toVector().subtract(loc.toVector()).normalize().multiply(0.4));
            return;
        } else if (status == BorderManager.BorderStatus.IMMOBILIZE) {
            event.setCancelled(true);
            player.sendMessage("§cYou cannot move outside the border!");
            return;
        }

        double dist = borderManager.distanceToBorder(loc);
        boolean near = dist <= 7.0 && borderManager.isNearBorder(loc);

        Set<Location> newGlass = near ? borderManager.getGlassBorderLocations(loc, 7) : new HashSet<>();
        Set<Location> oldGlass = playerGlassBlocks.getOrDefault(player.getUniqueId(), new HashSet<>());

        for (Location oldLoc : oldGlass) {
            if (!newGlass.contains(oldLoc)) {
                Block realBlock = oldLoc.getWorld().getBlockAt(oldLoc);
                sendFakeBlock(player, oldLoc, realBlock.getType(), realBlock.getData());
            }
        }
        for (Location newLoc : newGlass) {
            if (!oldGlass.contains(newLoc)) {
                sendFakeBlock(player, newLoc, Material.STAINED_GLASS, (byte) 14);
            }
        }

        if (newGlass.isEmpty()) {
            playerGlassBlocks.remove(player.getUniqueId());
        } else {
            playerGlassBlocks.put(player.getUniqueId(), newGlass);
        }
    }

    // Call this after border shrink and on timer
    public void checkAndTeleportAllPlayersSafe() {
        if (borderManager == null) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            Location loc = player.getLocation();
            if (borderManager.getPlayerBorderStatus(loc) != BorderManager.BorderStatus.INSIDE) {
                Location safeLoc = borderManager.getSafeSurfaceLocationInsideBorder(player.getWorld(), loc, 2);
                player.teleport(safeLoc);
                player.sendMessage("§cYou were teleported back inside the border!");
            }
        }
    }

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
            GLITCHED_NEAR,
            GLITCHED_FAR,
            IMMOBILIZE
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

        // Teleport to 2 blocks inside border, at safe surface
        public Location getSafeSurfaceLocationInsideBorder(World world, Location from, int buffer) {
            double x = from.getX();
            double z = from.getZ();

            if (x < minX) x = minX + buffer;
            if (x > maxX) x = maxX - buffer;
            if (z < minZ) z = minZ + buffer;
            if (z > maxZ) z = maxZ - buffer;

            int blockX = (int) Math.floor(x);
            int blockZ = (int) Math.floor(z);
            int surfaceY = world.getHighestBlockYAt(blockX, blockZ);
            return new Location(world, blockX + 0.5, surfaceY + 1, blockZ + 0.5, from.getYaw(), from.getPitch());
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

        public Set<Location> getGlassBorderLocations(Location playerLoc, int radius) {
            Set<Location> locs = new HashSet<>();
            World world = playerLoc.getWorld();
            int playerX = playerLoc.getBlockX();
            int playerY = playerLoc.getBlockY();
            int playerZ = playerLoc.getBlockZ();

            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        int x = playerX + dx;
                        int y = playerY + dy;
                        int z = playerZ + dz;

                        boolean onBorder =
                                (x == minX || x == maxX) && (z >= minZ && z <= maxZ) ||
                                        (z == minZ || z == maxZ) && (x >= minX && x <= maxX);

                        if (onBorder && y >= 0 && y < world.getMaxHeight()) {
                            Material type = world.getBlockAt(x, y, z).getType();
                            if (type != Material.BEDROCK) {
                                locs.add(new Location(world, x, y, z));
                            }
                        }
                    }
                }
            }
            return locs;
        }

        public void setBorderSize(int size) {
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;
            int half = size / 2;
            this.minX = centerX - half;
            this.maxX = centerX + half;
            this.minZ = centerZ - half;
            this.maxZ = centerZ + half;
        }
    }

    private static class BorderShrinkTask {
        private final BorderManager borderManager;
        private final World world;
        private final JavaPlugin plugin;
        private final BlitzUHC blitzUHC;
        private final int[][] shrinkPhases = {
                {2000, 750},
                {1500, 750},
                {1000, 750},
                {500, 750},
                {100, 300},
                {50, 300},
                {25, 180}
        };

        public BorderShrinkTask(BorderManager borderManager, World world, JavaPlugin plugin, BlitzUHC blitzUHC) {
            this.borderManager = borderManager;
            this.world = world;
            this.plugin = plugin;
            this.blitzUHC = blitzUHC;
        }

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

            // Teleport all players safely inside the new border
            blitzUHC.checkAndTeleportAllPlayersSafe();

            new BukkitRunnable() {
                @Override
                public void run() {
                    startShrinkPhase(phase + 1);
                }
            }.runTaskLater(plugin, duration * 20L);
        }
    }

    private static class BorderUtil {
        public static void generateBedrockBorder(World world, int x1, int z1, int x2, int z2) {
            int minX = Math.min(x1, x2);
            int maxX = Math.max(x1, x2);
            int minZ = Math.min(z1, z2);
            int maxZ = Math.max(z1, z2);

            int worldMinY = 0;
            int worldMaxY = world.getMaxHeight();

            for (int x = minX; x <= maxX; x++) {
                setBedrockWall(world, x, minZ, worldMinY, worldMaxY);
                setBedrockWall(world, x, maxZ, worldMinY, worldMaxY);
            }

            for (int z = minZ + 1; z <= maxZ - 1; z++) {
                setBedrockWall(world, minX, z, worldMinY, worldMaxY);
                setBedrockWall(world, maxX, z, worldMinY, worldMaxY);
            }
        }

        private static void setBedrockWall(World world, int x, int z, int minY, int maxY) {
            int surfaceY = world.getHighestBlockYAt(x, z);

            for (int y = minY; y <= surfaceY; y++) {
                Block block = world.getBlockAt(x, y, z);
                block.setType(Material.BEDROCK, false);
            }

            for (int y = surfaceY + 1; y <= surfaceY + 1; y++) {
                if (y <= maxY) {
                    Block block = world.getBlockAt(x, y, z);
                    block.setType(Material.BEDROCK, false);
                }
            }
        }
    }

    @SuppressWarnings("deprecation")
    private void sendFakeBlock(Player player, Location loc, Material material, byte data) {
        player.sendBlockChange(loc, material, data);
    }
}