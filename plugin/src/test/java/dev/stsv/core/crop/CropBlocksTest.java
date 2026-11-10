package dev.stsv.core.crop;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Crops as tripwire blocks: state codes, the allocation (stable, append-only, capacity) and the block rules. */
class CropBlocksTest {

    @Test
    void codesAndPropertiesRoundTrip() {
        Set<String> seen = new HashSet<>();
        for (int c = 0; c < CropBlocks.STATES; c++) {
            String p = CropBlocks.properties(c);
            assertTrue(seen.add(p));
            assertEquals(c, CropBlocks.parse(p));
            assertEquals(7, p.split(",").length, "all seven properties, in the game's order");
        }
        assertEquals("attached=true,disarmed=false,east=false,north=false,powered=false,south=false,west=true", CropBlocks.properties(65));
        assertEquals(CropBlocks.ATTACHED | CropBlocks.WEST, CropBlocks.parse("west=true,attached=true,disarmed=false,east=false,north=false,powered=false,south=false"));
        assertEquals(CropBlocks.code(true, false, false, true, true, false, false), CropBlocks.ATTACHED | CropBlocks.NORTH | CropBlocks.POWERED);
        assertThrows(IllegalArgumentException.class, () -> CropBlocks.parse("attached=true"));
        assertThrows(IllegalArgumentException.class, () -> CropBlocks.properties(128));
    }

    @Test
    void twoStatesStayStringTheRestAreForCrops() {
        assertTrue(CropBlocks.reserved(CropBlocks.PLAIN) && CropBlocks.reserved(CropBlocks.PLAIN_ATTACHED));
        List<Integer> thin = CropBlocks.pool(true), tall = CropBlocks.pool(false);
        assertEquals(63, thin.size());
        assertEquals(63, tall.size());
        Set<Integer> all = new HashSet<>(thin);
        all.addAll(tall);
        assertEquals(126, all.size());
        assertFalse(all.contains(0) || all.contains(64));
        for (int c : thin) assertTrue(CropBlocks.thin(c));
        for (int c : tall) assertFalse(CropBlocks.thin(c));
        assertEquals(List.of(65, 66, 67), thin.subList(0, 3), "fixed order");
        assertEquals(List.of(1, 2, 3), tall.subList(0, 3), "fixed order");
    }

    @Test
    void unusedStatesLookLikeVanillaString() {
        // as assets/minecraft/blockstates/tripwire.json of 26.3 (checked against the client once)
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_ns", 0), CropBlocks.vanilla(CropBlocks.PLAIN));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_attached_ns", 0), CropBlocks.vanilla(CropBlocks.PLAIN_ATTACHED));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_ns", 90), CropBlocks.vanilla(CropBlocks.EAST | CropBlocks.WEST));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_n", 270), CropBlocks.vanilla(CropBlocks.WEST | CropBlocks.POWERED));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_ne", 90), CropBlocks.vanilla(CropBlocks.EAST | CropBlocks.SOUTH));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_ne", 180), CropBlocks.vanilla(CropBlocks.SOUTH | CropBlocks.WEST));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_nse", 0), CropBlocks.vanilla(CropBlocks.NORTH | CropBlocks.SOUTH | CropBlocks.EAST));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_nse", 180), CropBlocks.vanilla(CropBlocks.NORTH | CropBlocks.SOUTH | CropBlocks.WEST));
        assertEquals(new CropBlocks.Vanilla("minecraft:block/tripwire_attached_nsew", 0), CropBlocks.vanilla(127));
    }

    private static List<CropBlocks.Crop> field() {
        return List.of(new CropBlocks.Crop("a:tomato", 5), new CropBlocks.Crop("a:onion", 4), new CropBlocks.Crop("a:corn", 5));
    }

    @Test
    void earlyStagesGetThinStatesInOrder() throws Exception {
        CropBlocks.Allocation a = CropBlocks.allocate(List.of(), field());
        assertEquals(14, a.byKey().size());
        assertEquals(14, a.added().size());
        // tomato 0,1 thin; 2,3,4 tall; onion 0,1 thin; 2,3 tall; ...
        assertEquals(65, a.code("a:tomato", 0));
        assertEquals(66, a.code("a:tomato", 1));
        assertEquals(1, a.code("a:tomato", 2));
        assertEquals(3, a.code("a:tomato", 4));
        assertEquals(67, a.code("a:onion", 0));
        assertEquals(4, a.code("a:onion", 2));
        for (Map.Entry<String, Integer> e : a.byKey().entrySet()) {
            int stage = Integer.parseInt(e.getKey().substring(e.getKey().indexOf('/') + 1));
            int stages = e.getKey().startsWith("a:onion") ? 4 : 5;
            assertEquals(CropBlocks.early(stage, stages), CropBlocks.thin(e.getValue()), e.getKey());
            // state <-> (crop, stage), both ways
            assertEquals(e.getKey(), a.keyOf(e.getValue()));
        }
        assertNull(a.keyOf(CropBlocks.PLAIN));
        assertNull(a.keyOf(127));
    }

    @Test
    void allocationIsStableAndAppendOnly() throws Exception {
        CropBlocks.Allocation first = CropBlocks.allocate(List.of(), field());
        Map<String, Integer> file = new LinkedHashMap<>(first.byKey());
        // the same again: nothing new
        CropBlocks.Allocation again = CropBlocks.allocate(List.of(file), field());
        assertEquals(first.byKey(), again.byKey());
        assertTrue(again.added().isEmpty());
        // a new crop placed FIRST in the list and a new stage of tomato: existing states never move
        List<CropBlocks.Crop> more = new ArrayList<>();
        more.add(new CropBlocks.Crop("a:garlic", 4));
        more.add(new CropBlocks.Crop("a:tomato", 6));
        more.addAll(field().subList(1, 3));
        CropBlocks.Allocation grown = CropBlocks.allocate(List.of(file), more);
        for (Map.Entry<String, Integer> e : file.entrySet()) assertEquals(e.getValue(), grown.code(e.getKey().split("/")[0], Integer.parseInt(e.getKey().split("/")[1])));
        assertEquals(List.of("a:garlic/0", "a:garlic/1", "a:garlic/2", "a:garlic/3", "a:tomato/5"), grown.added());
        assertEquals(new ArrayList<>(file.keySet()), new ArrayList<>(grown.byKey().keySet()).subList(0, file.size()), "the file's entries first");
        // a crop removed from the content: its entries stay taken, never reused
        CropBlocks.Allocation fewer = CropBlocks.allocate(List.of(file), List.of(new CropBlocks.Crop("a:tomato", 5), new CropBlocks.Crop("a:radish", 4)));
        assertEquals(file.get("a:onion/0"), fewer.byKey().get("a:onion/0"));
        for (int st = 0; st < 4; st++) assertFalse(file.containsValue(fewer.code("a:radish", st)), "a new crop never takes an old crop's state");
    }

    @Test
    void capacityAndBadEntries() throws Exception {
        // 126 stages fit (thin pool first for early ones, then the other pool)
        List<CropBlocks.Crop> many = new ArrayList<>();
        for (int i = 0; i < 18; i++) many.add(new CropBlocks.Crop("a:c" + i, 7));
        CropBlocks.Allocation full = CropBlocks.allocate(List.of(), many);
        assertEquals(126, new HashSet<>(full.byKey().values()).size());
        many.add(new CropBlocks.Crop("a:one_too_many", 2));
        CropBlocks.Full e = assertThrows(CropBlocks.Full.class, () -> CropBlocks.allocate(List.of(), many));
        assertTrue(e.getMessage().contains("a:one_too_many/0") && e.getMessage().contains(CropBlocks.FILE), e.getMessage());
        // when the thin states are gone, early stages take tall ones (and the other way round)
        Map<String, Integer> thinTaken = new LinkedHashMap<>();
        List<Integer> thin = CropBlocks.pool(true);
        for (int i = 0; i < thin.size(); i++) thinTaken.put("a:old/" + i, thin.get(i));
        CropBlocks.Allocation mixed = CropBlocks.allocate(List.of(thinTaken), List.of(new CropBlocks.Crop("a:new", 2)));
        assertFalse(CropBlocks.thin(mixed.code("a:new", 0)), "thin pool used up: an early stage takes a tall state");
        assertFalse(CropBlocks.thin(mixed.code("a:new", 1)));

        Map<String, Integer> bad = new LinkedHashMap<>();
        bad.put("a:x/0", 0);       // real string
        bad.put("a:x/1", 300);     // no such state
        bad.put("a:y/0", 70);
        bad.put("a:y/1", 70);      // taken twice
        bad.put("a:z/0", null);
        CropBlocks.Allocation a = CropBlocks.allocate(List.of(bad), List.of(new CropBlocks.Crop("a:y", 2)));
        assertEquals(4, a.problems().size(), a.problems().toString());
        assertEquals(70, a.code("a:y", 0));
        assertTrue(a.code("a:y", 1) != 70 && !CropBlocks.reserved(a.code("a:y", 1)));
    }

    @Test
    void whatHappensToTheBlockOfAPlantedCrop() {
        // chunk load / growth check: the database says a crop is here
        assertEquals(CropBlocks.Placement.OK, CropBlocks.placement(true, true, false));
        assertEquals(CropBlocks.Placement.SET, CropBlocks.placement(true, false, false), "its tripwire in another state (a new stage, a shuffled pack)");
        assertEquals(CropBlocks.Placement.SET, CropBlocks.placement(false, false, true), "air: an old crop drawn by entities moves into a block");
        assertEquals(CropBlocks.Placement.POP, CropBlocks.placement(false, false, false), "a block someone put there: back to a seed");
    }

    @Test
    void strayTripwireAndChunkMarks() {
        assertEquals(-1, CropBlocks.stray(CropBlocks.EAST | CropBlocks.WEST, CropBlocks.Stray.AIR));
        assertEquals(-1, CropBlocks.stray(CropBlocks.PLAIN, CropBlocks.Stray.AIR), "air takes even plain string");
        assertEquals(CropBlocks.PLAIN, CropBlocks.stray(CropBlocks.EAST | CropBlocks.WEST | CropBlocks.POWERED, CropBlocks.Stray.STRING));
        assertEquals(CropBlocks.PLAIN_ATTACHED, CropBlocks.stray(CropBlocks.ATTACHED | CropBlocks.NORTH | CropBlocks.SOUTH, CropBlocks.Stray.STRING),
                "a temple trap stays a hung string, without its connections");
        assertEquals(CropBlocks.PLAIN, CropBlocks.stray(CropBlocks.PLAIN, CropBlocks.Stray.STRING), "already plain: left as it is");
        assertEquals(CropBlocks.Stray.AIR, CropBlocks.Stray.of(null));
        assertEquals(CropBlocks.Stray.AIR, CropBlocks.Stray.of("whatever"));
        assertEquals(CropBlocks.Stray.STRING, CropBlocks.Stray.of(" String "));
        assertTrue(CropBlocks.needsCheck(null), "a chunk never checked (an old one, or new: temple traps)");
        assertTrue(CropBlocks.needsCheck(0));
        assertFalse(CropBlocks.needsCheck(CropBlocks.CHECK_VERSION), "once per chunk");
    }

    @Test
    void stringIsNeverPlacedAsABlock() {
        assertTrue(CropBlocks.stringPlacement(Material.TRIPWIRE, Material.STRING), "string on farmland, on a crop, anywhere");
        assertTrue(CropBlocks.stringPlacement(Material.TRIPWIRE, Material.PAPER), "anything else that would end up as tripwire");
        assertTrue(CropBlocks.stringPlacement(Material.AIR, Material.STRING));
        assertFalse(CropBlocks.stringPlacement(Material.TRIPWIRE_HOOK, Material.TRIPWIRE_HOOK), "hooks are placed (they just never connect)");
        assertFalse(CropBlocks.stringPlacement(Material.WHITE_WOOL, Material.WHITE_WOOL));
    }

    @Test
    void renderSetting() {
        assertEquals(CropModule.Render.BLOCKS, CropModule.Render.of(null));
        assertEquals(CropModule.Render.BLOCKS, CropModule.Render.of("blocks"));
        assertEquals(CropModule.Render.ENTITIES, CropModule.Render.of("Entities"));
    }
}
