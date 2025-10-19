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
import tc.oc.pgm.api.match.event.MatchFinishEvent;
import tc.oc.pgm.api.match.event.MatchLoadEvent;
import tc.oc.pgm.api.match.event.MatchStartEvent;
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
 *
 * Extended behavior for DTM map rotation/restart:
 * - On match finish/rotation, snapshot every player's current layout so their most recent layout survives into the next map.
 * - When applying the layout in a different map (different items/signatures), resolve slot collisions using category ranking:
 *   - "Highest ranking" item for that category wins the contested slot (e.g., iron sword > stone sword).
 *   - Enchantments increase ranking (e.g., Sharpness/Power levels).
 *   - If rank ties, the item most recently saved to that slot wins.
 *   - Losing items are moved to the first free slots starting at 9, 10, 11, ... (hotbar preserved for winners).
 *
 * Implementation notes:
 * - We persist not only signature->preferred slots, but also per-slot recency ("slot history") timestamps to support tie-breaking.
 * - We also persist per-signature per-slot timestamps to recognize which slots the player last saved for a category.
 * - Storage remains YAML-only; we add namespaced sections under each UUID to avoid conflicts with Bukkit's dotted path semantics.
 *
 * This file is intentionally verbose to keep all logic self-contained and 1.8-safe.
 */
public class InventorySorter implements Listener {

    private final JavaPlugin plugin;

    // Set to true to only run during DTM matches.
    private static final boolean SCOPE_DTM_ONLY = true;

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

    /**
     * PGM integration hooks for rotation/restart.
     * - MatchLoad: arm players again (next layout snapshot should capture first change in the new map)
     * - MatchStart: run a reapply pass after kits (next tick) for online players in the match world
     * - MatchFinish: snapshot all players' current layouts so their latest arrangement persists to next map
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchLoad(MatchLoadEvent event) {
        final World world = event.getMatch().getWorld();
        // Arm all players in this match world (new map is loading)
        for (Player p : world.getPlayers()) {
            state.arm(p.getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchStart(MatchStartEvent event) {
        final World world = event.getMatch().getWorld();
        // After kits apply on start, reapply ordering next tick
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                for (Player p : world.getPlayers()) {
                    onAfterKitApplied(p);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMatchFinish(MatchFinishEvent event) {
        final World world = event.getMatch().getWorld();
        // On match finish/rotation, snapshot everyone's current layout so it survives into next map
        for (Player p : world.getPlayers()) {
            snapshotForRotation(p);
        }
        // Optionally: flush to disk immediately
        store.flush();
    }

    private void onAfterKitApplied(Player player) {
        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return;

        UUID id = player.getUniqueId();
        state.arm(id);

        // Load preferences and reapply with ranking-aware collision resolution
        Map<ItemSignature, List<Integer>> prefs = store.getPreferences(id);
        if (!prefs.isEmpty()) {
            reorderToPreferencesRanked(player, prefs);
            player.updateInventory();
        } else {
            // Even if no explicit signature preferences exist, we may have slot history
            if (store.hasAnySlotHistory(id)) {
                reorderToPreferencesRanked(player, Collections.<ItemSignature, List<Integer>>emptyMap());
                player.updateInventory();
            }
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
            // Armor durability may change on damage (ignore; we don't touch armor), but use as a "content change" arm trigger
            maybeSnapshot((Player) event.getEntity());
        }
    }

    /**
     * Called for "normal" snapshots within a running match.
     * Records signature preferences and per-slot recency while locking the session until next arm.
     */
    private void maybeSnapshot(Player player) {
        UUID id = player.getUniqueId();

        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return;
        if (!state.isArmed(id) || state.isLocked(id)) return;

        PlayerInventory inv = player.getInventory();
        Map<ItemSignature, List<Integer>> prefs = computePreferences(inv);

        // Save both classic preferences and recency metadata
        store.savePreferences(id, prefs);
        store.saveSlotHistory(id, inv, System.currentTimeMillis());

        state.lock(id);
    }

    /**
     * Called at MatchFinish so the next map can inherit the latest arrangement even if a player didn't die or relog.
     * Does NOT require the session to be armed; always snapshots.
     */
    private void snapshotForRotation(Player player) {
        if (SCOPE_DTM_ONLY && !isDTM(player.getWorld())) return;

        UUID id = player.getUniqueId();
        PlayerInventory inv = player.getInventory();

        Map<ItemSignature, List<Integer>> prefs = computePreferences(inv);
        store.savePreferences(id, prefs);
        store.saveSlotHistory(id, inv, System.currentTimeMillis());
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

    /**
     * Legacy reordering used initially; remains available if needed.
     */
    @SuppressWarnings("unused")
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

    /**
     * Ranked, rotation-aware reordering.
     *
     * Rules:
     * - Determine each present item's category ("family"), rank by material tier and enchantments.
     * - Build each item's preferred slots list:
     *   - exact signature slots (most recent first)
     *   - otherwise, family's union of preferred slots from other signatures (most recent first)
     * - For each slot, pick a single winner among candidates:
     *   - higher rank wins; if tied, most recently saved for that slot wins; if still tied, stable order.
     * - Losing candidates are queued for fallback and placed into free slots starting at 9, 10, ...
     * - Finally, any leftover items fill remaining free slots in their original order.
     */
    private void reorderToPreferencesRanked(Player player, Map<ItemSignature, List<Integer>> rawPreferences) {
        PlayerInventory inv = player.getInventory();

        // Collect current items
        List<SlotItem> items = new ArrayList<SlotItem>();
        for (int slot = 0; slot <= 35; slot++) {
            ItemStack s = inv.getItem(slot);
            if (s == null || s.getType() == Material.AIR) continue;
            items.add(new SlotItem(slot, s.clone(), ItemSignature.of(s)));
        }
        if (items.isEmpty()) return;

        UUID id = player.getUniqueId();

        // Prepare helper structures
        ItemStack[] newLayout = new ItemStack[36];
        boolean[] occupied = new boolean[36];

        // Build family-level slot histories and signature-level slot recency
        Map<ItemSignature, List<Integer>> preferences = rawPreferences;
        SlotHistory slotHistory = store.getSlotHistory(id);
        SignatureSlotRecency recency = store.getSignatureSlotRecency(id);

        // Precompute item ranks and preferred slots
        List<RankedItem> ranked = new ArrayList<RankedItem>();
        for (int i = 0; i < items.size(); i++) {
            SlotItem it = items.get(i);
            ItemFamily family = ItemFamily.of(it.stack.getType());
            int rankScore = ItemRanker.score(it.stack, it.sig, family);

            List<Integer> preferred = computePreferredSlotsForItem(it.sig, family, preferences, slotHistory, recency);
            RankedItem ri = new RankedItem(i, it, family, rankScore, preferred);
            ranked.add(ri);
        }

        // Map of slot -> candidates wanting this slot
        Map<Integer, List<RankedItem>> candidatesBySlot = new HashMap<Integer, List<RankedItem>>();
        for (RankedItem ri : ranked) {
            for (int target : ri.preferredSlots) {
                if (target < 0 || target > 35) continue;
                List<RankedItem> list = candidatesBySlot.get(target);
                if (list == null) {
                    list = new ArrayList<RankedItem>();
                    candidatesBySlot.put(target, list);
                }
                list.add(ri);
            }
        }

        // Resolve each slot independently to a single winner
        Set<Integer> assignedItemIdx = new HashSet<Integer>();
        Map<Integer, Integer> winnerItemIndexBySlot = new HashMap<Integer, Integer>(); // slot -> ranked.index
        for (int slot = 0; slot <= 35; slot++) {
            List<RankedItem> cands = candidatesBySlot.get(slot);
            if (cands == null || cands.isEmpty()) continue;

            // Sort candidates by:
            // - rank desc
            // - recency (lastSaved to this slot) desc
            // - stable index asc
            Collections.sort(cands, new RankedItemComparator(slot, recency));
            RankedItem winner = null;
            for (RankedItem ri : cands) {
                if (!assignedItemIdx.contains(ri.index)) {
                    winner = ri;
                    break;
                }
            }
            if (winner != null) {
                newLayout[slot] = winner.item.stack;
                occupied[slot] = true;
                assignedItemIdx.add(winner.index);
                winnerItemIndexBySlot.put(slot, winner.index);
            }
        }

        // Gather losers who attempted to claim a slot but lost
        // Definition: item has preferred slots, but wasn't assigned yet.
        List<RankedItem> losers = new ArrayList<RankedItem>();
        for (RankedItem ri : ranked) {
            if (assignedItemIdx.contains(ri.index)) continue;
            if (!ri.preferredSlots.isEmpty()) {
                losers.add(ri);
            }
        }

        // Sort losers by:
        // - the best recency they had among their preferred slots (older first -> "furthest last saved" goes earlier to 9,10,...)
        // - lower rank first (so stronger items might still get a chance in hotbar if room remains)
        Collections.sort(losers, new java.util.Comparator<RankedItem>() {
            @Override
            public int compare(RankedItem a, RankedItem b) {
                long aBest = bestRecencyForAnyPreferredSlot(a, recency);
                long bBest = bestRecencyForAnyPreferredSlot(b, recency);
                if (aBest != bBest) {
                    // "furthest last saved" -> smaller timestamps first
                    return aBest < bBest ? -1 : 1;
                }
                if (a.rankScore != b.rankScore) return a.rankScore < b.rankScore ? -1 : 1;
                return a.index - b.index;
            }
        });

        // Reserve fallback slots 9..35 first for losers.
        List<Integer> fallbackSlots = new ArrayList<Integer>();
        for (int i = 9; i <= 35; i++) if (!occupied[i]) fallbackSlots.add(i);
        int fIdx = 0;
        for (RankedItem loser : losers) {
            if (fIdx >= fallbackSlots.size()) break;
            int slot = fallbackSlots.get(fIdx++);
            newLayout[slot] = loser.item.stack;
            occupied[slot] = true;
            assignedItemIdx.add(loser.index);
        }

        // Place any remaining unassigned items (no preferences at all or no capacity)
        List<Integer> remainingSlots = new ArrayList<Integer>();
        for (int i = 0; i <= 35; i++) if (!occupied[i]) remainingSlots.add(i);

        for (RankedItem ri : ranked) {
            if (assignedItemIdx.contains(ri.index)) continue;
            if (remainingSlots.isEmpty()) break;
            int slot = remainingSlots.remove(0);
            newLayout[slot] = ri.item.stack;
            occupied[slot] = true;
            assignedItemIdx.add(ri.index);
        }

        // Write back
        for (int slot = 0; slot <= 35; slot++) {
            inv.setItem(slot, newLayout[slot]);
        }
    }

    private long bestRecencyForAnyPreferredSlot(RankedItem ri, SignatureSlotRecency recency) {
        long best = Long.MIN_VALUE;
        for (int s : ri.preferredSlots) {
            long ts = recency.get(ri.item.sig, s);
            if (ts > best) best = ts;
        }
        // If no recency known, set to Long.MIN_VALUE so it sorts as oldest
        return best == Long.MIN_VALUE ? 0L : best;
    }

    /**
     * Determine preferred slots for an item:
     * - use exact-signature saved slots ordered by recency desc
     * - if none, use family union slots ordered by recency desc (across all signatures in that family)
     */
    private List<Integer> computePreferredSlotsForItem(ItemSignature sig,
                                                       ItemFamily family,
                                                       Map<ItemSignature, List<Integer>> preferences,
                                                       SlotHistory slotHistory,
                                                       SignatureSlotRecency recency) {
        // 1) exact signature
        List<Integer> exact = preferences.get(sig);
        if (exact != null && !exact.isEmpty()) {
            // Order by recency desc if we have timestamps; otherwise keep as-is
            List<Integer> sorted = new ArrayList<Integer>(exact);
            Collections.sort(sorted, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    long ta = recency.get(sig, a);
                    long tb = recency.get(sig, b);
                    if (ta == tb) return 0;
                    return ta > tb ? -1 : 1;
                }
            });
            return sorted;
        }

        // 2) family union via slot history
        // SlotHistory stores, for each slot, recent signatures that were saved there.
        // Choose slots where any signature from the same family appeared most recently, sorted by recency desc.
        List<SlotCandidate> cands = new ArrayList<SlotCandidate>();
        for (int slot = 0; slot <= 35; slot++) {
            List<HistoryEntry> hist = slotHistory.get(slot);
            if (hist == null || hist.isEmpty()) continue;
            long best = Long.MIN_VALUE;
            boolean any = false;
            for (HistoryEntry he : hist) {
                if (ItemFamily.of(he.signature.material) == family) {
                    any = true;
                    if (he.savedAt > best) best = he.savedAt;
                }
            }
            if (any) {
                cands.add(new SlotCandidate(slot, best <= 0 ? 1L : best));
            }
        }
        Collections.sort(cands, new java.util.Comparator<SlotCandidate>() {
            @Override
            public int compare(SlotCandidate a, SlotCandidate b) {
                if (a.recency == b.recency) return 0;
                return a.recency > b.recency ? -1 : 1;
            }
        });
        List<Integer> familySlots = new ArrayList<Integer>();
        for (SlotCandidate sc : cands) familySlots.add(sc.slot);
        return familySlots;
    }

    private static final class SlotCandidate {
        final int slot;
        final long recency;
        SlotCandidate(int slot, long recency) {
            this.slot = slot;
            this.recency = recency;
        }
    }

    private static final class RankedItemComparator implements java.util.Comparator<RankedItem> {
        private final int slot;
        private final SignatureSlotRecency recency;

        RankedItemComparator(int slot, SignatureSlotRecency recency) {
            this.slot = slot;
            this.recency = recency;
        }

        @Override
        public int compare(RankedItem a, RankedItem b) {
            if (a.rankScore != b.rankScore) return a.rankScore > b.rankScore ? -1 : 1;
            long ta = recency.get(a.item.sig, slot);
            long tb = recency.get(b.item.sig, slot);
            if (ta != tb) return ta > tb ? -1 : 1;
            return a.index - b.index;
        }
    }

    private static final class RankedItem {
        final int index;            // stable index
        final SlotItem item;        // original data
        final ItemFamily family;    // derived category
        final int rankScore;        // computed ranking score
        final List<Integer> preferredSlots; // ordered

        RankedItem(int index, SlotItem item, ItemFamily family, int rankScore, List<Integer> preferredSlots) {
            this.index = index;
            this.item = item;
            this.family = family;
            this.rankScore = rankScore;
            this.preferredSlots = preferredSlots;
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

    /**
     * Item family/category for ranking and cross-map inheritance.
     * Only uses Material names available in 1.8.
     */
    private enum ItemFamily {
        SWORD,
        AXE,
        PICKAXE,
        SHOVEL,
        HOE,
        BOW,
        ROD,
        POTION,
        BLOCK,
        FOOD,
        TOOL_MISC,
        PROJECTILE,
        OTHER;

        static ItemFamily of(Material mat) {
            if (mat == null) return OTHER;
            String n = mat.name();
            if (n.endsWith("_SWORD")) return SWORD;
            if (n.endsWith("_AXE")) return AXE;
            if (n.endsWith("_PICKAXE")) return PICKAXE;
            if (n.endsWith("_SPADE")) return SHOVEL; // 1.8 shovel
            if (n.endsWith("_HOE")) return HOE;
            if (mat == Material.BOW) return BOW;
            if (mat == Material.FISHING_ROD) return ROD;
            if (mat == Material.POTION) return POTION;
            if (isLikelyBlock(mat)) return BLOCK;
            if (isLikelyFood(mat)) return FOOD;
            if (isMiscTool(mat)) return TOOL_MISC;
            if (isProjectile(mat)) return PROJECTILE;
            return OTHER;
        }

        private static boolean isLikelyBlock(Material m) {
            if (m == null) return false;
            if (m.isBlock()) return true; // In 1.8 API, Material#isBlock exists
            String n = m.name();
            return n.endsWith("_WOOL") || n.endsWith("_CLAY") || n.endsWith("_GLASS") || n.contains("PLANKS") ||
                    n.endsWith("_LOG") || n.endsWith("_LOG_2") || n.endsWith("_LEAVES") || n.endsWith("_LEAVES_2") ||
                    n.endsWith("_STONE") || n.endsWith("_BRICK") || n.endsWith("_BRICKS") || n.contains("SANDSTONE");
        }

        private static boolean isLikelyFood(Material m) {
            if (m == null) return false;
            switch (m) {
                case BREAD:
                case COOKED_BEEF:
                case COOKED_CHICKEN:
                case COOKED_FISH:
                case COOKED_MUTTON:
                case GRILLED_PORK:
                case COOKED_RABBIT:
                case MUSHROOM_SOUP:
                case GOLDEN_CARROT:
                case GOLDEN_APPLE:
                case CARROT_ITEM:
                case POTATO_ITEM:
                case BAKED_POTATO:
                case APPLE:
                case MELON:
                case PUMPKIN_PIE:
                    return true;
                default:
                    return false;
            }
        }

        private static boolean isMiscTool(Material m) {
            if (m == null) return false;
            switch (m) {
                case SHEARS:
                case FLINT_AND_STEEL:
                case COMPASS:
                case MAP:
                case TORCH:
                case LAVA_BUCKET:
                case WATER_BUCKET:
                case BUCKET:
                    return true;
                default:
                    return false;
            }
        }

        private static boolean isProjectile(Material m) {
            if (m == null) return false;
            switch (m) {
                case ARROW:
                case SNOW_BALL:
                case EGG:
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * Simple item ranker:
     * - Base on material tier per family (swords/tools), GOLD < IRON < DIAMOND
     * - Adds enchantment weights
     * - Potions: slight boost for splash; extends considered
     * - Otherwise 0 baseline
     */
    private static final class ItemRanker {
        // Base tiers for tools/swords (1.8 names)
        private static final Map<String, Integer> TIER_BASE = new HashMap<String, Integer>();
        static {
            // Base per family by suffix
            TIER_BASE.put("WOOD", 10);
            TIER_BASE.put("STONE", 20);
            TIER_BASE.put("GOLD", 25);     // weaker than iron
            TIER_BASE.put("IRON", 35);
            TIER_BASE.put("DIAMOND", 50);
        }

        private static int tierBaseFor(Material mat) {
            if (mat == null) return 0;
            String n = mat.name();
            if (n.contains("WOOD")) return TIER_BASE.get("WOOD");
            if (n.contains("STONE")) return TIER_BASE.get("STONE");
            if (n.contains("GOLD")) return TIER_BASE.get("GOLD");
            if (n.contains("IRON")) return TIER_BASE.get("IRON");
            if (n.contains("DIAMOND")) return TIER_BASE.get("DIAMOND");
            return 0;
        }

        static int score(ItemStack stack, ItemSignature sig, ItemFamily family) {
            int score = 0;
            if (family == ItemFamily.SWORD || family == ItemFamily.AXE || family == ItemFamily.PICKAXE || family == ItemFamily.SHOVEL || family == ItemFamily.HOE) {
                score += tierBaseFor(stack.getType());
            }
            if (family == ItemFamily.BOW) {
                // Bow baseline
                score += 30;
            }
            if (family == ItemFamily.ROD) {
                score += 5;
            }
            if (family == ItemFamily.POTION) {
                // Splash potions are more valuable for PvP hotbar; extended slightly
                if (sig.potionKey != null) {
                    if (sig.potionKey.contains("splash=1")) score += 12;
                    if (sig.potionKey.contains("ext=1")) score += 4;
                    // Higher levels add 3 per level
                    int idx = sig.potionKey.indexOf("lvl=");
                    if (idx >= 0) {
                        try {
                            int lvl = Integer.parseInt(sig.potionKey.substring(idx + 4, sig.potionKey.indexOf(':', idx + 4) > -1 ? sig.potionKey.indexOf(':', idx + 4) : sig.potionKey.length()));
                            score += 3 * lvl;
                        } catch (Exception ignored) {}
                    }
                }
            }
            // Enchantments
            score += enchantScore(sig.enchants, family);
            return score;
        }

        private static int enchantScore(SortedMap<String, Integer> ench, ItemFamily family) {
            if (ench == null || ench.isEmpty()) return 0;
            int s = 0;
            for (Map.Entry<String, Integer> e : ench.entrySet()) {
                String name = e.getKey();
                int lvl = e.getValue() == null ? 0 : e.getValue();
                if (lvl <= 0) continue;
                // Weight by relevant enchants
                if (family == ItemFamily.SWORD || family == ItemFamily.AXE) {
                    if ("DAMAGE_ALL".equals(name) || "SHARPNESS".equalsIgnoreCase(name)) s += 6 * lvl;
                    else if ("KNOCKBACK".equalsIgnoreCase(name)) s += 3 * lvl;
                    else if ("FIRE_ASPECT".equalsIgnoreCase(name)) s += 4 * lvl;
                } else if (family == ItemFamily.BOW) {
                    if ("ARROW_DAMAGE".equalsIgnoreCase(name) || "POWER".equalsIgnoreCase(name)) s += 6 * lvl;
                    else if ("ARROW_KNOCKBACK".equalsIgnoreCase(name) || "PUNCH".equalsIgnoreCase(name)) s += 3 * lvl;
                    else if ("ARROW_FIRE".equalsIgnoreCase(name) || "FLAME".equalsIgnoreCase(name)) s += 2 * lvl;
                    else if ("ARROW_INFINITE".equalsIgnoreCase(name) || "INFINITY".equalsIgnoreCase(name)) s += 1;
                } else if (family == ItemFamily.PICKAXE || family == ItemFamily.SHOVEL || family == ItemFamily.HOE) {
                    if ("DIG_SPEED".equalsIgnoreCase(name) || "EFFICIENCY".equalsIgnoreCase(name)) s += 3 * lvl;
                } else {
                    // Generic bonus for any enchant
                    s += lvl;
                }
            }
            return s;
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
                else if (p.startsWith("name=")) {
                    name = p.substring("name=".length());
                    if (name != null) name = name.replace('¦', '|'); // restore
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
    }

    /**
     * Slot history and signature-slot recency metadata.
     * - SlotHistory: for each slot index, a list of history entries (signature, savedAt) most-recent-first.
     * - SignatureSlotRecency: map of signature -> (slot -> lastSavedAt) for direct lookups.
     */
    private static final class HistoryEntry {
        final ItemSignature signature;
        final long savedAt;

        HistoryEntry(ItemSignature signature, long savedAt) {
            this.signature = signature;
            this.savedAt = savedAt;
        }
    }

    private static final class SlotHistory {
        private final Map<Integer, List<HistoryEntry>> bySlot = new HashMap<Integer, List<HistoryEntry>>();

        void add(int slot, ItemSignature sig, long ts, int maxKeep) {
            List<HistoryEntry> list = bySlot.get(slot);
            if (list == null) {
                list = new ArrayList<HistoryEntry>();
                bySlot.put(slot, list);
            }
            // Remove any existing entry for the same signature to maintain uniqueness
            for (int i = 0; i < list.size(); i++) {
                if (list.get(i).signature.equals(sig)) {
                    list.remove(i);
                    break;
                }
            }
            list.add(0, new HistoryEntry(sig, ts));
            while (list.size() > maxKeep) list.remove(list.size() - 1);
        }

        List<HistoryEntry> get(int slot) {
            return bySlot.get(slot);
        }

        Map<Integer, List<HistoryEntry>> all() {
            return bySlot;
        }

        boolean isEmpty() {
            return bySlot.isEmpty();
        }
    }

    private static final class SignatureSlotRecency {
        // Key: signature.toString(), Value: Map<slot, lastSavedAt>
        private final Map<String, Map<Integer, Long>> data = new LinkedHashMap<String, Map<Integer, Long>>();

        void put(ItemSignature sig, int slot, long ts) {
            String key = sig.toString();
            Map<Integer, Long> m = data.get(key);
            if (m == null) {
                m = new LinkedHashMap<Integer, Long>();
                data.put(key, m);
            }
            m.put(slot, ts);
        }

        long get(ItemSignature sig, int slot) {
            String key = sig.toString();
            Map<Integer, Long> m = data.get(key);
            if (m == null) return 0L;
            Long ts = m.get(slot);
            return ts == null ? 0L : ts;
        }

        Map<String, Map<Integer, Long>> raw() {
            return data;
        }

        boolean isEmpty() { return data.isEmpty(); }
    }

    // Simple YAML-backed preference store (no external deps)
    private static final class PreferenceStore {
        private static final String KEY_SIG_PREFS = "sigprefs";    // {uuid}.sigprefs.{signatureString} -> [slots...]
        private static final String KEY_SLOT_HISTORY = "slothistory"; // {uuid}.slothistory.{slot} -> ["ts|signatureString", ...]
        private static final String KEY_SIG_RECENCY = "sigrecency";   // {uuid}.sigrecency.{signatureString} -> ["slot:ts", ...]

        private final File file;
        private final Map<UUID, Map<ItemSignature, List<Integer>>> cache = new HashMap<UUID, Map<ItemSignature, List<Integer>>>();
        private final Map<UUID, SlotHistory> slotHistories = new HashMap<UUID, SlotHistory>();
        private final Map<UUID, SignatureSlotRecency> sigRecencies = new HashMap<UUID, SignatureSlotRecency>();

        // Limits
        private final int maxHistoryPerSlot = 16;

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

        boolean hasAnySlotHistory(UUID playerId) {
            SlotHistory h = slotHistories.get(playerId);
            return h != null && !h.isEmpty();
        }

        SlotHistory getSlotHistory(UUID playerId) {
            SlotHistory h = slotHistories.get(playerId);
            return h == null ? new SlotHistory() : h;
        }

        SignatureSlotRecency getSignatureSlotRecency(UUID playerId) {
            SignatureSlotRecency s = sigRecencies.get(playerId);
            return s == null ? new SignatureSlotRecency() : s;
        }

        void savePreferences(UUID playerId, Map<ItemSignature, List<Integer>> prefs) {
            cache.put(playerId, deepCopy(prefs));
            save(); // keep synchronous write as before
        }

        void saveSlotHistory(UUID playerId, PlayerInventory inv, long timestamp) {
            // Build/update slot history and signature recency for this snapshot
            SlotHistory hist = slotHistories.get(playerId);
            if (hist == null) {
                hist = new SlotHistory();
                slotHistories.put(playerId, hist);
            }
            SignatureSlotRecency rec = sigRecencies.get(playerId);
            if (rec == null) {
                rec = new SignatureSlotRecency();
                sigRecencies.put(playerId, rec);
            }

            for (int slot = 0; slot <= 35; slot++) {
                ItemStack s = inv.getItem(slot);
                if (s == null || s.getType() == Material.AIR) continue;
                ItemSignature sig = ItemSignature.of(s);
                hist.add(slot, sig, timestamp, maxHistoryPerSlot);
                rec.put(sig, slot, timestamp);
            }
            save();
        }

        private void load() {
            cache.clear();
            slotHistories.clear();
            sigRecencies.clear();
            if (!file.exists()) return;

            YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

            // Old format (pre-namespacing): {uuid}.{signatureString} -> [slots...]
            // New format: {uuid}.sigprefs.{signatureString} -> [slots...]
            Set<String> rootKeys = yml.getKeys(false);
            for (String key : rootKeys) {
                UUID id;
                try {
                    id = UUID.fromString(key);
                } catch (IllegalArgumentException ignored) {
                    continue;
                }

                Map<ItemSignature, List<Integer>> prefs = new LinkedHashMap<ItemSignature, List<Integer>>();

                // New namespaced sig prefs
                if (yml.isConfigurationSection(key + "." + KEY_SIG_PREFS)) {
                    for (String sigKey : yml.getConfigurationSection(key + "." + KEY_SIG_PREFS).getKeys(false)) {
                        String path = key + "." + KEY_SIG_PREFS + "." + sigKey;
                        List<Integer> slots = yml.getIntegerList(path);
                        ItemSignature sig = ItemSignature.fromString(sigKey);
                        prefs.put(sig, new ArrayList<Integer>(slots));
                    }
                }

                // Backward compatibility: legacy direct children signature keys
                if (yml.isConfigurationSection(key)) {
                    for (String child : yml.getConfigurationSection(key).getKeys(false)) {
                        // Skip our new sections
                        if (KEY_SIG_PREFS.equals(child) || KEY_SLOT_HISTORY.equals(child) || KEY_SIG_RECENCY.equals(child)) {
                            continue;
                        }
                        // If value is a list of integers, interpret as legacy signature mapping
                        String path = key + "." + child;
                        if (yml.isList(path)) {
                            List<?> raw = yml.getList(path);
                            boolean allInts = true;
                            List<Integer> ints = new ArrayList<Integer>();
                            if (raw != null) {
                                for (Object o : raw) {
                                    if (o instanceof Number) ints.add(((Number) o).intValue());
                                    else {
                                        allInts = false;
                                        break;
                                    }
                                }
                            }
                            if (allInts && !ints.isEmpty()) {
                                try {
                                    ItemSignature sig = ItemSignature.fromString(child);
                                    if (!prefs.containsKey(sig)) {
                                        prefs.put(sig, ints);
                                    }
                                } catch (Throwable ignored) {
                                    // skip
                                }
                            }
                        }
                    }
                }

                cache.put(id, prefs);

                // Load slot history: {uuid}.slothistory.{slot} -> ["ts|signatureString", ...]
                SlotHistory history = new SlotHistory();
                if (yml.isConfigurationSection(key + "." + KEY_SLOT_HISTORY)) {
                    for (String slotStr : yml.getConfigurationSection(key + "." + KEY_SLOT_HISTORY).getKeys(false)) {
                        String path = key + "." + KEY_SLOT_HISTORY + "." + slotStr;
                        List<String> lines = yml.getStringList(path);
                        int slot = -1;
                        try {
                            slot = Integer.parseInt(slotStr);
                        } catch (NumberFormatException ignored) {}
                        if (slot < 0 || slot > 35) continue;

                        if (lines != null) {
                            for (String line : lines) {
                                int bar = line.indexOf('|');
                                if (bar <= 0) continue;
                                String tsStr = line.substring(0, bar);
                                String sigStr = line.substring(bar + 1);
                                try {
                                    long ts = Long.parseLong(tsStr);
                                    ItemSignature sig = ItemSignature.fromString(sigStr);
                                    history.add(slot, sig, ts, maxHistoryPerSlot);
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
                if (!history.isEmpty()) slotHistories.put(id, history);

                // Load signature recency: {uuid}.sigrecency.{signatureString} -> ["slot:ts", ...]
                SignatureSlotRecency rec = new SignatureSlotRecency();
                if (yml.isConfigurationSection(key + "." + KEY_SIG_RECENCY)) {
                    for (String sigKey : yml.getConfigurationSection(key + "." + KEY_SIG_RECENCY).getKeys(false)) {
                        String path = key + "." + KEY_SIG_RECENCY + "." + sigKey;
                        List<String> entries = yml.getStringList(path);
                        if (entries != null) {
                            for (String entry : entries) {
                                int colon = entry.indexOf(':');
                                if (colon <= 0) continue;
                                try {
                                    int slot = Integer.parseInt(entry.substring(0, colon));
                                    long ts = Long.parseLong(entry.substring(colon + 1));
                                    ItemSignature sig = ItemSignature.fromString(sigKey);
                                    rec.put(sig, slot, ts);
                                } catch (Throwable ignored) {}
                            }
                        }
                    }
                }
                if (!rec.isEmpty()) sigRecencies.put(id, rec);
            }
        }

        private void save() {
            YamlConfiguration yml = new YamlConfiguration();
            // Signature preferences
            for (Map.Entry<UUID, Map<ItemSignature, List<Integer>>> entry : cache.entrySet()) {
                String id = entry.getKey().toString();
                Map<ItemSignature, List<Integer>> prefs = entry.getValue();
                for (Map.Entry<ItemSignature, List<Integer>> s : prefs.entrySet()) {
                    String path = id + "." + KEY_SIG_PREFS + "." + s.getKey().toString();
                    yml.set(path, new ArrayList<Integer>(s.getValue()));
                }
            }

            // Slot history
            for (Map.Entry<UUID, SlotHistory> e : slotHistories.entrySet()) {
                String id = e.getKey().toString();
                SlotHistory hist = e.getValue();
                for (Map.Entry<Integer, List<HistoryEntry>> byS : hist.all().entrySet()) {
                    List<String> lines = new ArrayList<String>();
                    for (HistoryEntry he : byS.getValue()) {
                        lines.add(he.savedAt + "|" + he.signature.toString());
                    }
                    yml.set(id + "." + KEY_SLOT_HISTORY + "." + byS.getKey(), lines);
                }
            }

            // Signature slot recency
            for (Map.Entry<UUID, SignatureSlotRecency> e : sigRecencies.entrySet()) {
                String id = e.getKey().toString();
                Map<String, Map<Integer, Long>> raw = e.getValue().raw();
                for (Map.Entry<String, Map<Integer, Long>> sig : raw.entrySet()) {
                    List<String> lines = new ArrayList<String>();
                    for (Map.Entry<Integer, Long> st : sig.getValue().entrySet()) {
                        lines.add(st.getKey() + ":" + st.getValue());
                    }
                    yml.set(id + "." + KEY_SIG_RECENCY + "." + sig.getKey(), lines);
                }
            }

            try {
                yml.save(file);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        void flush() {
            // For now save() is synchronous and immediate, so nothing extra.
            // Kept for API completeness if we later debounce writes.
            save();
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