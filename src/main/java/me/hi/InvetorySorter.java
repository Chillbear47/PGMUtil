package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionData;
import tc.oc.pgm.api.PGM;
import tc.oc.pgm.api.match.Match;
import tc.oc.pgm.destroyable.DestroyableMatchModule;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * InvetorySorter
 *
 * Captures per-player preferred inventory layout (slots 0..35) from the first kit spawn,
 * just before their first content-changing action (place, consume, durability change, etc.),
 * and reapplies that layout after each subsequent spawn by reordering slots only.
 *
 * Notes:
 * - Only reorders main inventory and hotbar (slots 0..35). Armor/offhand are untouched.
 * - Does not change item content, only slot order.
 * - Snapshot is taken before the first content change; inventory clicks do not trigger locking.
 * - If SCOPE_DTM_ONLY is true, this runs only for DTM matches (Destroy The Monument).
 */
public class InvetorySorter implements Listener {

    private final JavaPlugin plugin;

    // Toggle to restrict behavior only to DTM matches. Set true to prioritize DTM only.
    private static final boolean SCOPE_DTM_ONLY = false;

    private final PreferenceStore store;
    private final SessionState state = new SessionState();

    public InvetorySorter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new PreferenceStore(new File(plugin.getDataFolder(), "inventory_prefs.yml"));
    }

    //
    // Spawn/Join hooks: arm the player and reorder (if preferences exist) AFTER kits are applied.
    //

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        // Delay 1 tick to ensure PGM applied the kit
        Bukkit.getScheduler().runTask(plugin, () -> onAfterKitApplied(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Delay a bit as players can join as observers; harmless if nothing is applied yet
        Bukkit.getScheduler().runTask(plugin, () -> onAfterKitApplied(player));
    }

    private void onAfterKitApplied(Player player) {
        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return;

        UUID id = player.getUniqueId();
        // Arm the player to allow rearranging before the first content change
        state.arm(id);

        Map<ItemSignature, List<Integer>> prefs = store.getPreferences(id);
        if (!prefs.isEmpty()) {
            reorderToPreferences(player.getInventory(), prefs);
            player.updateInventory();
        }
    }

    private boolean isDTM(World world) {
        try {
            Match match = PGM.get().getMatchManager().getMatch(world);
            if (match == null) return false;
            return match.getModule(DestroyableMatchModule.class) != null;
        } catch (Throwable t) {
            // If PGM isn't present or API changed, fail open (treat as not DTM)
            return false;
        }
    }

    //
    // Content change detectors: snapshot layout BEFORE first actual content change happens.
    // Inventory clicks alone are NOT listened to, so players can freely rearrange.
    //

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemDamage(PlayerItemDamageEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player p) {
            maybeSnapshot(p);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        // Broad catch: many interactions cause consumption/durability/placement.
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            // Armor durability may change; snapshot before that
            maybeSnapshot(p);
        }
    }

    private void maybeSnapshot(Player player) {
        UUID id = player.getUniqueId();

        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return;
        if (!state.isArmed(id) || state.isLocked(id)) return;

        PlayerInventory inv = player.getInventory();
        Map<ItemSignature, List<Integer>> prefs = computePreferences(inv);
        store.savePreferences(id, prefs);
        state.lock(id);
    }

    //
    // Core logic: build preferences and reorder on spawn
    //

    private Map<ItemSignature, List<Integer>> computePreferences(PlayerInventory inv) {
        Map<ItemSignature, List<Integer>> map = new LinkedHashMap<>();
        // main inventory + hotbar [0..35]
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            ItemSignature sig = ItemSignature.of(stack);
            map.computeIfAbsent(sig, k -> new ArrayList<>()).add(slot);
        }
        return map;
    }

    private void reorderToPreferences(PlayerInventory inv, Map<ItemSignature, List<Integer>> preferences) {
        // Capture current items (0..35)
        List<SlotItem> items = new ArrayList<>();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s == null || s.getType().isAir()) continue;
            items.add(new SlotItem(slot, s.clone(), ItemSignature.of(s)));
        }

        // Queue by signature in current order
        Map<ItemSignature, Deque<SlotItem>> bySig = new LinkedHashMap<>();
        for (SlotItem it : items) {
            bySig.computeIfAbsent(it.sig, k -> new ArrayDeque<>()).add(it);
        }

        ItemStack[] newLayout = new ItemStack[36];
        boolean[] occupied = new boolean[36];

        // Place preferred items into preferred slots
        for (Map.Entry<ItemSignature, List<Integer>> e : preferences.entrySet()) {
            ItemSignature sig = e.getKey();
            Deque<SlotItem> q = bySig.get(sig);
            if (q == null || q.isEmpty()) continue;

            for (int target : e.getValue()) {
                if (target < 0 || target > 35) continue;
                if (q.isEmpty()) break;
                if (occupied[target]) continue;
                newLayout[target] = q.removeFirst().stack;
                occupied[target] = true;
            }
        }

        // Fill remaining slots with remaining items, preserving original order
        List<Integer> freeSlots = new ArrayList<>();
        for (int i = 0; i <= 35; i++) if (!occupied[i]) freeSlots.add(i);

        List<SlotItem> leftovers = new ArrayList<>();
        for (Deque<SlotItem> q : bySig.values()) {
            while (!q.isEmpty()) leftovers.add(q.removeFirst());
        }

        int idx = 0;
        for (SlotItem it : leftovers) {
            if (idx >= freeSlots.size()) break;
            newLayout[freeSlots.get(idx++)] = it.stack;
        }

        // Apply back
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

    //
    // Session state: armed until first content change, then locked
    //

    private static final class SessionState {
        private final Set<UUID> armed = new HashSet<>();
        private final Set<UUID> locked = new HashSet<>();

        void arm(UUID id) {
            armed.add(id);
            locked.remove(id);
        }

        boolean isArmed(UUID id) {
            return armed.contains(id);
        }

        void lock(UUID id) {
            armed.remove(id);
            locked.add(id);
        }

        boolean isLocked(UUID id) {
            return locked.contains(id);
        }
    }

    //
    // ItemSignature: identifies "type of item" for reordering
    //

    private static final class ItemSignature {
        private final Material material;
        private final String potionKey; // base potion params if applicable
        private final Integer customModelData;
        private final String displayName;
        private final SortedMap<String, Integer> enchants;

        private ItemSignature(Material material,
                              String potionKey,
                              Integer customModelData,
                              String displayName,
                              SortedMap<String, Integer> enchants) {
            this.material = material;
            this.potionKey = potionKey;
            this.customModelData = customModelData;
            this.displayName = displayName;
            this.enchants = enchants;
        }

        static ItemSignature of(ItemStack stack) {
            Material mat = stack.getType();
            String potion = null;
            Integer cmd = null;
            String name = null;
            SortedMap<String, Integer> ench = new TreeMap<>();

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta instanceof PotionMeta pm) {
                    PotionData pd = pm.getBasePotionData();
                    potion = pd.getType().name() + ":ext=" + pd.isExtended() + ":upg=" + pd.isUpgraded();
                }
                if (meta.hasCustomModelData()) {
                    cmd = meta.getCustomModelData();
                }
                if (meta.hasDisplayName()) {
                    name = meta.getDisplayName();
                }
                Map<Enchantment, Integer> e = meta.getEnchants();
                if (e != null && !e.isEmpty()) {
                    for (var entry : e.entrySet()) {
                        ench.put(entry.getKey().getKey().toString(), entry.getValue());
                    }
                }
            }
            return new ItemSignature(mat, potion, cmd, name, ench);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ItemSignature that)) return false;
            return material == that.material
                    && Objects.equals(potionKey, that.potionKey)
                    && Objects.equals(customModelData, that.customModelData)
                    && Objects.equals(displayName, that.displayName)
                    && Objects.equals(enchants, that.enchants);
        }

        @Override
        public int hashCode() {
            return Objects.hash(material, potionKey, customModelData, displayName, enchants);
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(material.name());
            if (potionKey != null) sb.append("|potion=").append(potionKey);
            if (customModelData != null) sb.append("|cmd=").append(customModelData);
            if (displayName != null) sb.append("|name=").append(displayName.replace('|', '¦'));
            if (!enchants.isEmpty()) {
                sb.append("|ench=");
                boolean first = true;
                for (var e : enchants.entrySet()) {
                    if (!first) sb.append(',');
                    sb.append(e.getKey()).append(':').append(e.getValue());
                    first = false;
                }
            }
            return sb.toString();
        }

        static ItemSignature fromString(String s) {
            String[] parts = s.split("\\|");
            Material mat = Material.valueOf(parts[0]);
            String potion = null;
            Integer cmd = null;
            String name = null;
            SortedMap<String, Integer> ench = new TreeMap<>();

            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (p.startsWith("potion=")) potion = p.substring("potion=".length());
                else if (p.startsWith("cmd=")) cmd = Integer.valueOf(p.substring("cmd=".length()));
                else if (p.startsWith("name=")) name = p.substring("name=".length());
                else if (p.startsWith("ench=")) {
                    String list = p.substring("ench=".length());
                    if (!list.isEmpty()) {
                        String[] kvs = list.split(",");
                        for (String kv : kvs) {
                            String[] pair = kv.split(":");
                            if (pair.length == 2) {
                                ench.put(pair[0], Integer.valueOf(pair[1]));
                            }
                        }
                    }
                }
            }
            return new ItemSignature(mat, potion, cmd, name, ench);
        }
    }

    //
    // Simple YAML-backed preference store (Bukkit YamlConfiguration, no external deps)
    //

    private static final class PreferenceStore {
        private final File file;
        private final Map<UUID, Map<ItemSignature, List<Integer>>> cache = new HashMap<>();

        PreferenceStore(File file) {
            this.file = file;
            if (!file.getParentFile().exists()) {
                // Ensure plugin data folder exists
                file.getParentFile().mkdirs();
            }
            load();
        }

        Map<ItemSignature, List<Integer>> getPreferences(UUID playerId) {
            Map<ItemSignature, List<Integer>> m = cache.get(playerId);
            return m == null ? Collections.emptyMap() : m;
        }

        void savePreferences(UUID playerId, Map<ItemSignature, List<Integer>> prefs) {
            cache.put(playerId, deepCopy(prefs));
            save();
        }

        private void load() {
            cache.clear();
            if (!file.exists()) return;

            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
            for (String key : yml.getKeys(false)) {
                try {
                    UUID id = UUID.fromString(key);
                    Map<ItemSignature, List<Integer>> prefs = new LinkedHashMap<>();

                    for (String sigKey : Objects.requireNonNull(yml.getConfigurationSection(key)).getKeys(false)) {
                        String path = key + "." + sigKey;
                        List<Integer> slots = yml.getIntegerList(path);
                        ItemSignature sig = ItemSignature.fromString(sigKey);
                        prefs.put(sig, new ArrayList<>(slots));
                    }
                    cache.put(id, prefs);
                } catch (IllegalArgumentException ignored) {
                    // skip invalid UUID keys
                }
            }
        }

        private void save() {
            YamlConfiguration yml = new YamlConfiguration();
            for (var entry : cache.entrySet()) {
                String id = entry.getKey().toString();
                Map<ItemSignature, List<Integer>> prefs = entry.getValue();
                for (var s : prefs.entrySet()) {
                    String path = id + "." + s.getKey().toString();
                    yml.set(path, new ArrayList<>(s.getValue()));
                }
            }
            try {
                yml.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private static Map<ItemSignature, List<Integer>> deepCopy(Map<ItemSignature, List<Integer>> src) {
            Map<ItemSignature, List<Integer>> dst = new LinkedHashMap<>();
            for (var e : src.entrySet()) {
                dst.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            return dst;
        }
    }
}