package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.Potion;
import org.bukkit.potion.PotionType;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.api.match.event.MatchStartEvent;
import tc.oc.pgm.destroyable.DestroyableMatchModule;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * InventorySorter (Spigot 1.8.8 compatible)
 *
 * - Always saves player's latest layout (slots 0..35) after they change inventory (click/drag/pick/drop/place/break/etc.).
 * - Reapplies saved layout after PGM applies kits, on respawn/join AND on PGM match start (covers map rotation).
 * - Layout persists to disk, so it survives match rotations and server restarts.
 * - Collision handling: if a saved target slot is occupied, the colliding item goes to 9,10,...,35 then 0..8.
 * - Only main inventory and hotbar (0..35). Armor is untouched. (Offhand doesn't exist in 1.8.)
 *
 * Signature matching is 1.8-safe (Material + Potion.fromItemStack + display name + enchant names).
 */
public class InventorySorter implements Listener {

    private final JavaPlugin plugin;

    // Set to true to only apply in DTM matches
    private static final boolean SCOPE_DTM_ONLY = false;

    // Persistent store (disk-backed)
    private final LayoutStore store;

    // In-memory cache of saved layouts this session (UUID -> slot->signature)
    private final Map<UUID, LinkedHashMap<Integer, ItemSignature>> savedLayouts = new HashMap<UUID, LinkedHashMap<Integer, ItemSignature>>();

    // Prevent snapshots while we are programmatically applying a layout
    private final Set<UUID> applying = new HashSet<UUID>();

    public InventorySorter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new LayoutStore(new File(plugin.getDataFolder(), "inventory_layouts.yml"));
        // Warm cache from disk
        this.savedLayouts.putAll(this.store.loadAll());
    }

    // Ensure we apply on new match start (covers map/match rotation)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchStart(final MatchStartEvent event) {
        final World world = Bukkit.getWorld(event.getMatch().getWorld().getName());
        if (world == null) return;
        // Run next tick so PGM has finished kit application
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                for (Player p : world.getPlayers()) {
                    if (!p.isOnline()) continue;
                    onAfterKitApplied(p);
                }
            }
        });
    }

    // Also apply after respawn
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(final PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                onAfterKitApplied(player);
            }
        });
    }

    // And when player joins mid-match
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                onAfterKitApplied(player);
            }
        });
    }

    private void onAfterKitApplied(Player player) {
        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return;

        UUID id = player.getUniqueId();
        LinkedHashMap<Integer, ItemSignature> layout = savedLayouts.get(id);

        if (layout != null && !layout.isEmpty()) {
            applying.add(id);
            try {
                reorderToSavedLayout(player.getInventory(), layout);
                player.updateInventory();
            } finally {
                applying.remove(id);
            }
        } else {
            // If nothing saved yet, snapshot current layout as baseline
            snapshotLayout(player);
        }
    }

    private boolean isDTM(World world) {
        try {
            Match match = PGM.get().getMatchManager().getMatch(world);
            if (match == null) return false;
            return match.getModule(DestroyableMatchModule.class) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    // Always-save triggers: after inventory changes, snapshot on next tick
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        HumanEntity he = event.getWhoClicked();
        if (!(he instanceof Player)) return;
        final Player player = (Player) he;
        if (skip(player)) return;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                snapshotLayout(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        HumanEntity he = event.getWhoClicked();
        if (!(he instanceof Player)) return;
        final Player player = (Player) he;
        if (skip(player)) return;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                snapshotLayout(player);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        snapshotNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        snapshotNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        snapshotNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        snapshotNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) snapshotNextTick((Player) event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        snapshotNextTick(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) snapshotNextTick((Player) event.getEntity());
    }

    private void snapshotNextTick(final Player player) {
        if (skip(player)) return;
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                snapshotLayout(player);
            }
        });
    }

    private boolean skip(Player player) {
        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return true;
        return applying.contains(player.getUniqueId());
    }

    // Save the current layout to memory + disk
    private void snapshotLayout(Player player) {
        if (!player.isOnline()) return;
        PlayerInventory inv = player.getInventory();
        LinkedHashMap<Integer, ItemSignature> layout = new LinkedHashMap<Integer, ItemSignature>();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) continue;
            layout.put(slot, ItemSignature.of(stack));
        }
        UUID id = player.getUniqueId();
        savedLayouts.put(id, layout);
        store.save(id, layout); // persist
    }

    // Apply the saved layout with collision handling
    private void reorderToSavedLayout(PlayerInventory inv, LinkedHashMap<Integer, ItemSignature> saved) {
        // Current items grouped by signature preserving order
        List<SlotItem> items = new ArrayList<SlotItem>();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s == null || s.getType() == Material.AIR) continue;
            items.add(new SlotItem(slot, s.clone(), ItemSignature.of(s)));
        }

        Map<ItemSignature, Deque<SlotItem>> bySig = new LinkedHashMap<ItemSignature, Deque<SlotItem>>();
        for (SlotItem it : items) {
            Deque<SlotItem> q = bySig.get(it.sig);
            if (q == null) {
                q = new ArrayDeque<SlotItem>();
                bySig.put(it.sig, q);
            }
            q.add(it);
        }

        ItemStack[] newLayout = new ItemStack[36];
        boolean[] occupied = new boolean[36];

        // Backup order: 9..35 then 0..8
        List<Integer> backupOrder = new ArrayList<Integer>(36);
        for (int i = 9; i <= 35; i++) backupOrder.add(i);
        for (int i = 0; i <= 8; i++) backupOrder.add(i);

        // First pass: place items into their saved target slots
        List<ItemStack> collided = new ArrayList<ItemStack>();
        for (Map.Entry<Integer, ItemSignature> e : saved.entrySet()) {
            int target = e.getKey();
            ItemSignature sig = e.getValue();
            Deque<SlotItem> q = bySig.get(sig);
            if (q == null || q.isEmpty()) continue;

            ItemStack stack = q.removeFirst().stack;
            if (!occupied[target] && newLayout[target] == null) {
                newLayout[target] = stack;
                occupied[target] = true;
            } else {
                collided.add(stack);
            }
        }

        // Place collided into backup slots
        for (ItemStack s : collided) {
            boolean placed = false;
            for (int i = 0; i < backupOrder.size(); i++) {
                int slot = backupOrder.get(i);
                if (!occupied[slot] && newLayout[slot] == null) {
                    newLayout[slot] = s;
                    occupied[slot] = true;
                    placed = true;
                    break;
                }
            }
            if (!placed) {
                // Fallback: find any remaining free slot
                for (int slot = 0; slot <= 35; slot++) {
                    if (!occupied[slot] && newLayout[slot] == null) {
                        newLayout[slot] = s;
                        occupied[slot] = true;
                        break;
                    }
                }
            }
        }

        // Remaining items (not referenced in saved), preserve original order
        List<SlotItem> leftovers = new ArrayList<SlotItem>();
        for (Map.Entry<ItemSignature, Deque<SlotItem>> e : bySig.entrySet()) {
            Deque<SlotItem> q = e.getValue();
            while (!q.isEmpty()) leftovers.add(q.removeFirst());
        }

        List<Integer> free = new ArrayList<Integer>();
        for (int i = 0; i <= 35; i++) if (!occupied[i] && newLayout[i] == null) free.add(i);

        int idx = 0;
        for (SlotItem it : leftovers) {
            if (idx >= free.size()) break;
            newLayout[free.get(idx++)] = it.stack;
        }

        // Apply
        for (int slot = 0; slot <= 35; slot++) {
            inv.setItem(slot, newLayout[slot]);
        }
    }

    private static final class SlotItem {
        final int originalSlot;
        final ItemStack stack;
        final ItemSignature sig;

        SlotItem(int originalSlot, ItemStack stack, ItemSignature sig) {
            this.originalSlot = originalSlot;
            this.stack = stack;
            this.sig = sig;
        }
    }

    /**
     * 1.8.8-safe item signature:
     * - Material
     * - Potion (if material == POTION): type/level/extended/splash via Potion.fromItemStack
     * - Display name (if present)
     * - Enchantments by legacy name -> level
     */
    private static final class ItemSignature {
        private final Material material;
        private final String potionKey; // only for potions
        private final String displayName;
        private final SortedMap<String, Integer> enchants;

        private ItemSignature(Material material, String potionKey, String displayName, SortedMap<String, Integer> enchants) {
            this.material = material;
            this.potionKey = potionKey;
            this.displayName = displayName;
            this.enchants = enchants;
        }

        static ItemSignature of(ItemStack stack) {
            Material mat = stack.getType();
            String potion = null;
            String name = null;
            SortedMap<String, Integer> ench = new TreeMap<String, Integer>();

            if (mat == Material.POTION) {
                try {
                    Potion p = Potion.fromItemStack(stack);
                    if (p != null) {
                        PotionType t = p.getType();
                        potion = (t != null ? t.name() : "UNKNOWN")
                                + ":lvl=" + p.getLevel()
                                + ":ext=" + (p.hasExtendedDuration() ? "1" : "0")
                                + ":splash=" + (p.isSplash() ? "1" : "0");
                    }
                } catch (Throwable ignored) {}
            }

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    name = meta.getDisplayName();
                }
                Map<org.bukkit.enchantments.Enchantment, Integer> e = meta.getEnchants();
                if (e != null && !e.isEmpty()) {
                    for (Map.Entry<org.bukkit.enchantments.Enchantment, Integer> en : e.entrySet()) {
                        String enchName = en.getKey() != null && en.getKey().getName() != null
                                ? en.getKey().getName()
                                : "UNK";
                        ench.put(enchName, en.getValue());
                    }
                }
            }
            return new ItemSignature(mat, potion, name, ench);
        }

        static ItemSignature fromString(String s) {
            // Format: MAT[|potion=...][|name=...][|ench=e1:l1,e2:l2]
            String[] parts = s.split("\\|");
            Material mat = Material.valueOf(parts[0]);
            String potion = null;
            String name = null;
            SortedMap<String, Integer> ench = new TreeMap<String, Integer>();

            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (p.startsWith("potion=")) {
                    potion = p.substring("potion=".length());
                } else if (p.startsWith("name=")) {
                    name = p.substring("name=".length());
                } else if (p.startsWith("ench=")) {
                    String list = p.substring("ench=".length());
                    if (!list.isEmpty()) {
                        String[] kvs = list.split(",");
                        for (String kv : kvs) {
                            String[] pair = kv.split(":");
                            if (pair.length == 2) {
                                try {
                                    ench.put(pair[0], Integer.valueOf(pair[1]));
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                    }
                }
            }
            return new ItemSignature(mat, potion, name, ench);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ItemSignature)) return false;
            ItemSignature that = (ItemSignature) o;
            if (material != that.material) return false;
            if (potionKey != null ? !potionKey.equals(that.potionKey) : that.potionKey != null) return false;
            if (displayName != null ? !displayName.equals(that.displayName) : that.displayName != null) return false;
            return enchants != null ? enchants.equals(that.enchants) : that.enchants == null;
        }

        @Override
        public int hashCode() {
            int result = material != null ? material.hashCode() : 0;
            result = 31 * result + (potionKey != null ? potionKey.hashCode() : 0);
            result = 31 * result + (displayName != null ? displayName.hashCode() : 0);
            result = 31 * result + (enchants != null ? enchants.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(material.name());
            if (potionKey != null) sb.append("|potion=").append(potionKey);
            if (displayName != null) sb.append("|name=").append(displayName.replace('|', '¦'));
            if (enchants != null && !enchants.isEmpty()) {
                sb.append("|ench=");
                boolean first = true;
                for (Map.Entry<String, Integer> e : enchants.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
            }
            return sb.toString();
        }
    }

    /**
     * Simple disk store: YAML-like via Bukkit config API but with compact keys.
     *
     * Structure:
     *   <uuid>:
     *     "<slot>": "<signatureString>"
     *     ...
     */
    private static final class LayoutStore {
        private final File file;

        LayoutStore(File file) {
            this.file = file;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
        }

        synchronized Map<UUID, LinkedHashMap<Integer, ItemSignature>> loadAll() {
            Map<UUID, LinkedHashMap<Integer, ItemSignature>> result = new HashMap<UUID, LinkedHashMap<Integer, ItemSignature>>();
            if (!file.exists()) return result;

            org.bukkit.configuration.file.YamlConfiguration yml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);

            for (String key : yml.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    LinkedHashMap<Integer, ItemSignature> map = new LinkedHashMap<Integer, ItemSignature>();
                    if (yml.isConfigurationSection(key)) {
                        for (String slotKey : yml.getConfigurationSection(key).getKeys(false)) {
                            int slot = Integer.parseInt(slotKey);
                            String sigStr = yml.getString(key + "." + slotKey, null);
                            if (sigStr != null) {
                                map.put(slot, ItemSignature.fromString(sigStr));
                            }
                        }
                    }
                    result.put(id, map);
                } catch (IllegalArgumentException ignored) {}
            }
            return result;
        }

        synchronized void save(UUID id, LinkedHashMap<Integer, ItemSignature> layout) {
            org.bukkit.configuration.file.YamlConfiguration yml =
                    org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);

            // Clear existing node
            if (yml.isConfigurationSection(id.toString())) {
                yml.set(id.toString(), null);
            }

            for (Map.Entry<Integer, ItemSignature> e : layout.entrySet()) {
                String path = id.toString() + "." + e.getKey();
                yml.set(path, e.getValue().toString());
            }

            try {
                yml.save(file);
            } catch (IOException ex) {
                ex.printStackTrace();
            }
        }
    }
}