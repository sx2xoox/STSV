package dev.stsv.core.painting;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * One painting: a map (its id is the painting's number), who paints it, its pixels and, once finished, its title.
 * id: the painting's unique identity (also on its items, so a copy made some other way is told apart).
 * mountedOn: the easel (its display entity) the canvas stands on, null while it is an item; a canvas item is only
 * put up while this is null, so one canvas is never on two easels.
 *
 * <p>Stored as {@link #encode()}: gzip of a small header and the 16 384 colour bytes (a few KB a painting).
 */
public final class Painting {

    private static final int MAGIC = 0x53545650;   // "STVP"
    private static final int VERSION = 1;
    /** Longest title (characters). */
    public static final int TITLE_MAX = 24;

    private final int mapId;
    private final UUID id;
    private UUID painter;
    private String painterName;
    private String title;
    private final long created;
    private long finished;
    private UUID mountedOn;
    private final PaintCanvas canvas;
    /** bumped on every change of the pixels (the renderer redraws when it differs from what it drew) */
    private volatile int version;

    public Painting(int mapId, UUID id, UUID painter, String painterName, long created) {
        this(mapId, id, painter, painterName, null, created, 0, null, new PaintCanvas());
    }

    private Painting(int mapId, UUID id, UUID painter, String painterName, String title, long created, long finished, UUID mountedOn,
                     PaintCanvas canvas) {
        this.mapId = mapId;
        this.id = id;
        this.painter = painter;
        this.painterName = painterName;
        this.title = title;
        this.created = created;
        this.finished = finished;
        this.mountedOn = mountedOn;
        this.canvas = canvas;
    }

    public int mapId() { return mapId; }
    public UUID id() { return id; }
    public UUID painter() { return painter; }
    public String painterName() { return painterName; }
    public String title() { return title; }
    public long created() { return created; }
    public long finishedAt() { return finished; }
    public boolean finished() { return finished > 0; }
    public UUID mountedOn() { return mountedOn; }
    public PaintCanvas canvas() { return canvas; }
    public int version() { return version; }

    public void changed() {
        version++;
    }

    public void painter(UUID uuid, String name) {
        this.painter = uuid;
        this.painterName = name;
    }

    public void mountedOn(UUID easel) {
        this.mountedOn = easel;
    }

    /** Finishes it: the title is kept (cut to {@link #TITLE_MAX}) and the pixels are read only from now on. */
    public void finish(String title, long at) {
        this.title = cleanTitle(title);
        this.finished = Math.max(1, at);
        this.mountedOn = null;
    }

    /** A title as kept: one line, trimmed, at most {@link #TITLE_MAX} characters; null if nothing is left. */
    public static String cleanTitle(String raw) {
        if (raw == null) return null;
        String t = raw.replaceAll("[\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > TITLE_MAX) t = t.substring(0, TITLE_MAX).trim();
        return t.isEmpty() ? null : t;
    }

    // ------------------------------------------------------------------------------------------
    // Storage
    // ------------------------------------------------------------------------------------------

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
            try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
                out.writeInt(MAGIC);
                out.writeInt(VERSION);
                out.writeInt(mapId);
                uuid(out, id);
                uuid(out, painter);
                out.writeUTF(painterName == null ? "" : painterName);
                out.writeUTF(title == null ? "" : title);
                out.writeLong(created);
                out.writeLong(finished);
                uuid(out, mountedOn);
                out.writeInt(PaintCanvas.PIXELS);
                out.write(canvas.raw());
            }
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Painting decode(byte[] data) throws IOException {
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC) throw new IOException("not a painting");
            int version = in.readInt();
            if (version != VERSION) throw new IOException("unknown painting version " + version);
            int mapId = in.readInt();
            UUID id = uuid(in);
            UUID painter = uuid(in);
            String painterName = in.readUTF();
            String title = in.readUTF();
            long created = in.readLong();
            long finished = in.readLong();
            UUID mounted = uuid(in);
            int n = in.readInt();
            if (n != PaintCanvas.PIXELS) throw new IOException("pixels: " + n);
            byte[] px = new byte[n];
            in.readFully(px);
            if (id == null) throw new IOException("painting without id");
            return new Painting(mapId, id, painter, painterName, title.isEmpty() ? null : title, created, finished, mounted,
                    new PaintCanvas(px));
        }
    }

    private static void uuid(DataOutputStream out, UUID u) throws IOException {
        out.writeBoolean(u != null);
        if (u != null) {
            out.writeLong(u.getMostSignificantBits());
            out.writeLong(u.getLeastSignificantBits());
        }
    }

    private static UUID uuid(DataInputStream in) throws IOException {
        return in.readBoolean() ? new UUID(in.readLong(), in.readLong()) : null;
    }
}
