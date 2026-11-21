package dev.stsv.economy.secret.letter;

/**
 * What the day's ritual asks for, saved as one kv string: a chunk (their first land), a land by id (where they live, or
 * the town), a spot with a radius (where they first came in), an item id to sell, any sale, or nothing to stand on.
 * No Bukkit here.
 */
record Target(Kind kind, String world, int x, int y, int z, int r, long land, String item) {

    enum Kind { CHUNK, LAND, NEAR, ITEM, ANY_ITEM, NONE }

    /** A chunk; x/z hold its middle block. */
    static Target chunk(String world, int cx, int cz) {
        return new Target(Kind.CHUNK, world, cx * 16 + 8, 0, cz * 16 + 8, 0, 0, null);
    }

    /** Any chunk of a land; x/z are one of its blocks (for the letter's "near"). */
    static Target land(long id, String world, int x, int z) {
        return new Target(Kind.LAND, world, x, 0, z, 0, id, null);
    }

    static Target near(String world, int x, int y, int z, int r) {
        return new Target(Kind.NEAR, world, x, y, z, Math.max(1, r), 0, null);
    }

    static Target item(String id) {
        return new Target(Kind.ITEM, null, 0, 0, 0, 0, 0, id);
    }

    static Target anyItem() {
        return new Target(Kind.ANY_ITEM, null, 0, 0, 0, 0, 0, null);
    }

    static Target none() {
        return new Target(Kind.NONE, null, 0, 0, 0, 0, 0, null);
    }

    int chunkX() {
        return Math.floorDiv(x, 16);
    }

    int chunkZ() {
        return Math.floorDiv(z, 16);
    }

    /** Whether a block position counts as "there" (CHUNK and NEAR only; LAND is asked of the land index). */
    boolean contains(String w, int bx, int by, int bz) {
        return switch (kind) {
            case CHUNK -> world.equals(w) && Math.floorDiv(bx, 16) == chunkX() && Math.floorDiv(bz, 16) == chunkZ();
            case NEAR -> world.equals(w) && (long) (bx - x) * (bx - x) + (long) (bz - z) * (bz - z) <= (long) r * r && Math.abs(by - y) <= Math.max(6, r);
            default -> false;
        };
    }

    String encode() {
        return switch (kind) {
            case CHUNK -> "chunk " + world + " " + chunkX() + " " + chunkZ();
            case LAND -> "land " + land + " " + world + " " + x + " " + z;
            case NEAR -> "near " + world + " " + x + " " + y + " " + z + " " + r;
            case ITEM -> "item " + item;
            case ANY_ITEM -> "item *";
            case NONE -> "none";
        };
    }

    static Target decode(String s) {
        if (s == null || s.isBlank()) return none();
        String[] w = s.trim().split(" ");
        try {
            return switch (w[0]) {
                case "chunk" -> chunk(w[1], Integer.parseInt(w[2]), Integer.parseInt(w[3]));
                case "land" -> land(Long.parseLong(w[1]), w[2], Integer.parseInt(w[3]), Integer.parseInt(w[4]));
                case "near" -> near(w[1], Integer.parseInt(w[2]), Integer.parseInt(w[3]), Integer.parseInt(w[4]), Integer.parseInt(w[5]));
                case "item" -> w.length < 2 || w[1].equals("*") ? anyItem() : item(w[1]);
                default -> none();
            };
        } catch (RuntimeException e) {
            return none();
        }
    }

    /** For the admin status line. */
    String describe() {
        return switch (kind) {
            case CHUNK -> "첫 땅 청크 " + world + " (" + x + ", " + z + ")";
            case LAND -> "땅 #" + land + " (" + world + " " + x + ", " + z + " 근처)";
            case NEAR -> world + " " + x + ", " + y + ", " + z + " 반경 " + r;
            case ITEM -> "판매 " + item;
            case ANY_ITEM -> "아무거나 판매";
            case NONE -> "-";
        };
    }
}
