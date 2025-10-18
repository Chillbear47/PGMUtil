package me.hi;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
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
import tc.oc.pgm.destroyable.DestroyableMatchModule;

import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * InventorySorter (Spigot 1.8.8 compatible)
 *
 * - Uses only Spigot-API 1.8.8 classes/methods.
 * - No Material#isAir, CustomModelData, or NamespacedKey APIs.
 * - Potion signature derived via Potion.fromItemStack (type/level/extended/splash).
 *
 * Behavior:
 * - Captures the player's preferred layout (slots 0..35) right before their first content change.
 * - Reapplies that layout after PGM applies kits (on respawn/join tick later).
 * - Only reorders; contents unchanged. Armor/offhand untouched (offhand doesn't exist in 1.8).
 */
public class InventorySorter implements Listener {

    private final JavaPlugin plugin;

    // Set to true to only run during DTM matches.
    private static final boolean SCOPE_DTM_ONLY = false;

    private final PreferenceStore store;
    private final SessionState state = new SessionState();

    public InventorySorter(JavaPlugin plugin) {
        this.plugin = plugin;
        this.store = new PreferenceStore(new File(plugin.getDataFolder(), "inventory_prefs.yml"));
    }

    // After respawn, let PGM apply kit, then reorder/apply arming
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        final Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                onAfterKitApplied(player);
            }
        });
    }

    // On join, try to arm and reorder (safe no-op if kit not yet applied)
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
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
            return false;
        }
    }

    // Content-change detectors (Spigot 1.8-safe)
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBowShoot(EntityShootBowEvent event) {
        if (event.getEntity() instanceof Player) {
            maybeSnapshot((Player) event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        maybeSnapshot(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player) {
            // Armor durability may change on damage
            maybeSnapshot((Player) event.getEntity());
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

    // Build preferences from current layout
    private Map<ItemSignature, List<Integer>> computePreferences(PlayerInventory inv) {
        Map<ItemSignature, List<Integer>> map = new LinkedHashMap<ItemSignature, List<Integer>>();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null || stack.getType() == Material.AIR) continue;
            ItemSignature sig = ItemSignature.of(stack);
            List<Integer> list = map.get(sig);
            if (list == null) {
                list = new ArrayList<Integer>();
                map.put(sig, list);
            }
            list.add(slot);
        }
        return map;
    }

    // Reorder to preferences without changing content
    private void reorderToPreferences(PlayerInventory inv, Map<ItemSignature, List<Integer>> preferences) {
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

        // Place items in preferred target slots first
        for (Map.Entry<ItemSignature, List<Integer>> e : preferences.entrySet()) {
            ItemSignature sig = e.getKey();
            Deque<SlotItem> q = bySig.get(sig);
            if (q == null || q.isEmpty()) continue;

            List<Integer> targets = e.getValue();
            for (int i = 0; i < targets.size(); i++) {
                int target = targets.get(i);
                if (target < 0 || target > 35) continue;
                if (q.isEmpty()) break;
                if (occupied[target]) continue;
                newLayout[target] = q.removeFirst().stack;
                occupied[target] = true;
            }
        }

        // Fill remaining slots preserving original order
        List<Integer> freeSlots = new ArrayList<Integer>();
        for (int i = 0; i <= 35; i++) if (!occupied[i]) freeSlots.add(i);

        List<SlotItem> leftovers = new ArrayList<SlotItem>();
        for (Map.Entry<ItemSignature, Deque<SlotItem>> e : bySig.entrySet()) {
            Deque<SlotItem> q = e.getValue();
            while (!q.isEmpty()) leftovers.add(q.removeFirst());
        }

        int idx = 0;
        for (SlotItem it : leftovers) {
            if (idx >= freeSlots.size()) break;
            newLayout[freeSlots.get(idx++)] = it.stack;
        }

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

    // Per-session arm/lock state
    private static final class SessionState {
        private final Set<UUID> armed = new HashSet<UUID>();
        private final Set<UUID> locked = new HashSet<UUID>();

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

    // Signature of an item for reordering (1.8-safe)
    private static final class ItemSignature {
        private final Material material;
        private final String potionKey; // from Potion.fromItemStack for POTION/SPLASH_POTION
        private final String displayName;
        private final SortedMap<String, Integer> enchants;

        private ItemSignature(Material material,
                              String potionKey,
                              String displayName,
                              SortedMap<String, Integer> enchants) {
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

            // 1.8 potion parsing via Potion.fromItemStack
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
                } catch (Throwable ignored) {
                    // leave potion null
                }
            }

            ItemMeta meta = stack.getItemMeta();
            if (meta != null) {
                if (meta.hasDisplayName()) {
                    name = meta.getDisplayName();
                }
                Map<Enchantment, Integer> e = meta.getEnchants();
                if (e != null && !e.isEmpty()) {
                    for (Map.Entry<Enchantment, Integer> entry : e.entrySet()) {
                        Enchantment enchKey = entry.getKey();
                        String enchName = (enchKey != null && enchKey.getName() != null) ? enchKey.getName() : "UNK";
                        ench.put(enchName, entry.getValue());
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

        static ItemSignature fromString(String s) {
            String[] parts = s.split("\\|");
            Material mat = Material.valueOf(parts[0]);
            String potion = null;
            String name = null;
            SortedMap<String, Integer> ench = new TreeMap<String, Integer>();

            for (int i = 1; i < parts.length; i++) {
                String p = parts[i];
                if (p.startsWith("potion=")) potion = p.substring("potion=".length());
                else if (p.startsWith("name=")) name = p.substring("name=".length());
                else if (p.startsWith("ench=")) {
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
    }

    // Simple YAML-backed preference store (no external deps)
    private static final class PreferenceStore {
        private final File file;
        private final Map<UUID, Map<ItemSignature, List<Integer>>> cache = new HashMap<UUID, Map<ItemSignature, List<Integer>>>();

        PreferenceStore(File file) {
            this.file = file;
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            load();
        }

        Map<ItemSignature, List<Integer>> getPreferences(UUID playerId) {
            Map<ItemSignature, List<Integer>> m = cache.get(playerId);
            return m == null ? Collections.<ItemSignature, List<Integer>>emptyMap() : m;
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
                    Map<ItemSignature, List<Integer>> prefs = new LinkedHashMap<ItemSignature, List<Integer>>();

                    if (yml.isConfigurationSection(key)) {
                        for (String sigKey : yml.getConfigurationSection(key).getKeys(false)) {
                            String path = key + "." + sigKey;
                            List<Integer> slots = yml.getIntegerList(path);
                            ItemSignature sig = ItemSignature.fromString(sigKey);
                            prefs.put(sig, new ArrayList<Integer>(slots));
                        }
                    }
                    cache.put(id, prefs);
                } catch (IllegalArgumentException ignored) {
                    // skip invalid UUID keys
                }
            }
        }

        private void save() {
            YamlConfiguration yml = new YamlConfiguration();
            for (Map.Entry<UUID, Map<ItemSignature, List<Integer>>> entry : cache.entrySet()) {
                String id = entry.getKey().toString();
                Map<ItemSignature, List<Integer>> prefs = entry.getValue();
                for (Map.Entry<ItemSignature, List<Integer>> s : prefs.entrySet()) {
                    String path = id + "." + s.getKey().toString();
                    yml.set(path, new ArrayList<Integer>(s.getValue()));
                }
            }
            try {
                yml.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        private static Map<ItemSignature, List<Integer>> deepCopy(Map<ItemSignature, List<Integer>> src) {
            Map<ItemSignature, List<Integer>> dst = new LinkedHashMap<ItemSignature, List<Integer>>();
            for (Map.Entry<ItemSignature, List<Integer>> e : src.entrySet()) {
                dst.put(e.getKey(), new ArrayList<Integer>(e.getValue()));
            }
            return dst;
        }
    }
}