package me.hi;

import org.bukkit.*;
import org.bukkit.block.Block;

public class BorderUtil {

    /**
     * Lays a 1 block thick, 4 block high bedrock border around the defined rectangular area.
     * @param world The world to place in.
     * @param x1 First corner X
     * @param z1 First corner Z
     * @param x2 Second corner X
     * @param z2 Second corner Z
     */
    public static void generateBedrockBorder(World world, int x1, int z1, int x2, int z2) {
        int minX = Math.min(x1, x2);
        int maxX = Math.max(x1, x2);
        int minZ = Math.min(z1, z2);
        int maxZ = Math.max(z1, z2);

        int worldMinY = world.getMinHeight(); // usually 0
        int worldMaxY = world.getMaxHeight(); // usually 256

        for (int x = minX; x <= maxX; x++) {
            // North wall
            setBedrockColumn(world, x, minZ, worldMinY, worldMaxY);
            // South wall
            setBedrockColumn(world, x, maxZ, worldMinY, worldMaxY);
        }

        for (int z = minZ; z <= maxZ; z++) {
            // West wall
            setBedrockColumn(world, minX, z, worldMinY, worldMaxY);
            // East wall
            setBedrockColumn(world, maxX, z, worldMinY, worldMaxY);
        }
    }

    private static void setBedrockColumn(World world, int x, int z, int minY, int maxY) {
        int surfaceY = world.getHighestBlockYAt(x, z);
        // Place bedrock from surfaceY down to minY (bedrock layer), and up to surfaceY+3
        for (int y = minY; y <= surfaceY + 3; y++) {
            Block block = world.getBlockAt(x, y, z);
            block.setType(Material.BEDROCK, false);
        }
    }
}