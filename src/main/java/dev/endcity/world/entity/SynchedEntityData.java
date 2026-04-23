package dev.endcity.world.entity;

import dev.endcity.network.utils.PacketBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Server-side packer for LCE's per-entity metadata stream. Used by {@code AddPlayerPacket},
 * {@code AddMobPacket}, {@code AddEntityPacket}, and {@code SetEntityDataPacket} to carry
 * indexed typed values (flags, health, potion effect color, etc.) about an entity.
 *
 * <h2>Wire format</h2>
 * For each defined item, the source emits:
 * <pre>
 *   [header byte = ((type &lt;&lt; 5) | (id &amp; 0x1F)) &amp; 0xFF]
 *   [value bytes — type-specific]
 * </pre>
 * then a single {@code 0x7F} terminator.
 *
 * <p>Items are packed in order of ascending id (0, 1, 2, …). See
 * {@code Minecraft.World/SynchedEntityData.cpp::packAll} which iterates
 * {@code itemsById[0..MAX_ID_VALUE]}.
 *
 * <h2>Design notes</h2>
 * <ul>
 *   <li><b>Write-only for M2.</b> The server never reads a SynchedEntityData stream off the wire in
 *       M2. {@code AddPlayerPacket} is server→client only here. Reading ({@code unpack}) is deferred
 *       to whatever milestone first handles inbound {@code SetEntityDataPacket}.</li>
 *   <li><b>No dirty tracking.</b> The source distinguishes "pack all" from "pack only dirty items"
 *       to support {@code SetEntityDataPacket} deltas during gameplay. M2 only does the full
 *       initial pack for {@code AddPlayerPacket}, so dirty tracking is deferred.</li>
 *   <li><b>No ItemInstance / Pos.</b> {@code TYPE_ITEMINSTANCE} needs the NBT codec (M4+); the
 *       source {@code asserts(false)} on {@code TYPE_POS} in {@code getPos} and never actually
 *       writes one — not a real wire type, skipped.</li>
 *   <li><b>Id 31 collides with EOF on the wire.</b> The header byte is
 *       {@code (type << 5) | (id & 0x1F)}. With id=31 and type=3 the header is {@code 0x7F}, which
 *       an unpacker reads as the EOF marker — the unpacker would stop there and whatever bytes
 *       followed would desync the stream. The source's {@code checkId} is {@code #if 0}'d out so it
 *       enforces nothing; we enforce defensively by rejecting {@code id == 31} on any define. In
 *       practice vanilla only uses ids 0..10.</li>
 * </ul>
 *
 * <h2>Source</h2>
 * <ul>
 *   <li>{@code Minecraft.World/SynchedEntityData.h} — type constants, {@code TYPE_SHIFT},
 *       {@code MAX_ID_VALUE}, {@code EOF_MARKER}.</li>
 *   <li>{@code Minecraft.World/SynchedEntityData.cpp::writeDataItem} — header packing + per-type
 *       value encoding.</li>
 *   <li>{@code Minecraft.World/SynchedEntityData.cpp::packAll} — iteration order + EOF terminator.</li>
 * </ul>
 */
public final class SynchedEntityData {

    /** {@code SynchedEntityData.h:62-64} — type ordinals for the wire header. */
    public static final int TYPE_BYTE   = 0;
    public static final int TYPE_SHORT  = 1;
    public static final int TYPE_INT    = 2;
    public static final int TYPE_FLOAT  = 3;
    public static final int TYPE_STRING = 4;

    /** {@code SynchedEntityData.h:72} — header byte is {@code (type << TYPE_SHIFT) | id}. */
    public static final int TYPE_SHIFT = 5;

    /** {@code SynchedEntityData.h:76} — id occupies the low 5 bits; top 3 are the type. */
    public static final int MAX_ID_VALUE = 0x1F;

    /**
     * {@code SynchedEntityData.h:60} — terminates the packed stream. Also overlaps with the header
     * byte for {@code (type=3, id=31)}, which is why we reject {@code id == 31} in the
     * {@code defineXxx} methods below.
     */
    public static final int EOF_MARKER = 0x7F;

    /**
     * {@code SynchedEntityData.h:59} — client-side max on incoming string items. We cap our
     * outgoing strings at this to match; the client would truncate anything longer.
     */
    public static final int MAX_STRING_DATA_LENGTH = 64;

    /** Slots 0..{@link #MAX_ID_VALUE} inclusive. {@code null} == slot unused. */
    private final Item[] items = new Item[MAX_ID_VALUE + 1];

    // ----------------------------------------------------------- define (write-only)

    public void defineByte(int id, byte value) {
        checkId(id);
        items[id] = Item.ofByte(id, value);
    }

    public void defineShort(int id, short value) {
        checkId(id);
        items[id] = Item.ofShort(id, value);
    }

    public void defineInt(int id, int value) {
        checkId(id);
        items[id] = Item.ofInt(id, value);
    }

    public void defineFloat(int id, float value) {
        checkId(id);
        items[id] = Item.ofFloat(id, value);
    }

    /**
     * Define a string item. The string is length-prefixed as an LCE UTF (see
     * {@link PacketBuffer#writeLceUtf}).
     *
     * @throws IllegalArgumentException if the string exceeds {@link #MAX_STRING_DATA_LENGTH}
     *         characters — the client won't read more.
     */
    public void defineString(int id, String value) {
        checkId(id);
        if (value == null) {
            throw new IllegalArgumentException("string value must not be null");
        }
        if (value.length() > MAX_STRING_DATA_LENGTH) {
            throw new IllegalArgumentException(
                "string length " + value.length() + " exceeds max " + MAX_STRING_DATA_LENGTH);
        }
        items[id] = Item.ofString(id, value);
    }

    /**
     * @return the number of defined items (for tests + estimated size calculations).
     */
    public int size() {
        int n = 0;
        for (Item it : items) if (it != null) n++;
        return n;
    }

    /**
     * Write every defined item to the buffer in ascending-id order, followed by the EOF marker.
     * Mirrors {@code SynchedEntityData.cpp::packAll}. An empty SynchedEntityData writes exactly one
     * byte: {@code 0x7F}.
     */
    public void packAll(PacketBuffer buf) throws IOException {
        for (Item it : items) {
            if (it != null) writeItem(buf, it);
        }
        buf.writeByte(EOF_MARKER);
    }

    /**
     * Read one packed SynchedEntityData stream from {@code buf}, advancing it past the terminating
     * EOF marker, and return the exact raw bytes consumed (including the EOF marker).
     *
     * <p>This is intentionally narrower than a full {@code unpack()} implementation: for the
     * server's current needs we only need to preserve the on-wire bytes when decoding outbound-only
     * packets in tests and helpers. The supported item types match the write path above.
     */
    public static byte[] readPackedBytes(PacketBuffer buf) throws IOException {
        int start = buf.position();
        while (true) {
            int header = buf.readByte() & 0xFF;
            if (header == EOF_MARKER) break;

            int type = (header >>> TYPE_SHIFT) & 0x7;
            switch (type) {
                case TYPE_BYTE -> buf.readByte();
                case TYPE_SHORT -> buf.readShort();
                case TYPE_INT -> buf.readInt();
                case TYPE_FLOAT -> buf.readFloat();
                case TYPE_STRING -> buf.readLceUtf(MAX_STRING_DATA_LENGTH);
                default -> throw new IOException("unsupported SynchedEntityData type " + type);
            }
        }

        int end = buf.position();
        ByteBuffer raw = buf.raw();
        byte[] out = new byte[end - start];
        for (int i = 0; i < out.length; i++) {
            out[i] = raw.get(start + i);
        }
        return out;
    }

    private static void writeItem(PacketBuffer buf, Item it) throws IOException {
        int header = ((it.type << TYPE_SHIFT) | (it.id & MAX_ID_VALUE)) & 0xFF;
        buf.writeByte(header);
        switch (it.type) {
            case TYPE_BYTE   -> buf.writeByte(it.byteVal);
            case TYPE_SHORT  -> buf.writeShort(it.shortVal);
            case TYPE_INT    -> buf.writeInt(it.intVal);
            case TYPE_FLOAT  -> buf.writeFloat(it.floatVal);
            case TYPE_STRING -> buf.writeLceUtf(it.stringVal);
            default -> throw new IllegalStateException("unreachable type " + it.type);
        }
    }

    private static void checkId(int id) {
        // Source's checkId is #if 0'd out; we enforce ourselves. Id must fit in 5 bits — but also
        // id=31 with type=3 produces a header byte 0x7F which the unpacker would read as EOF and
        // desync the stream. Vanilla only uses ids 0..10, so we reject the whole slot rather than
        // getting clever about allowed type/id combinations.
        if (id < 0 || id >= MAX_ID_VALUE) {
            throw new IllegalArgumentException(
                "id " + id + " out of range [0, " + (MAX_ID_VALUE - 1) + "] — "
                + "id=" + MAX_ID_VALUE + " is reserved to avoid EOF collision on the wire");
        }
    }

    @Override
    public String toString() {
        return "SynchedEntityData{items=" + Arrays.stream(items).filter(i -> i != null).toList() + "}";
    }

    // ----------------------------------------------------------- internal record

    /**
     * One item slot. Rather than a hierarchy of {@code DataItem<Byte>/<Short>/…}, we inline all
     * typed fields in a flat record and rely on {@link #type} to discriminate. Only the field
     * matching {@code type} is meaningful; the others are default values.
     */
    private record Item(
        int type, int id,
        byte byteVal, short shortVal, int intVal, float floatVal, String stringVal
    ) {
        static Item ofByte(int id, byte v)     { return new Item(TYPE_BYTE,   id, v, (short)0, 0, 0f, null); }
        static Item ofShort(int id, short v)   { return new Item(TYPE_SHORT,  id, (byte)0, v, 0, 0f, null); }
        static Item ofInt(int id, int v)       { return new Item(TYPE_INT,    id, (byte)0, (short)0, v, 0f, null); }
        static Item ofFloat(int id, float v)   { return new Item(TYPE_FLOAT,  id, (byte)0, (short)0, 0, v, null); }
        static Item ofString(int id, String v) { return new Item(TYPE_STRING, id, (byte)0, (short)0, 0, 0f, v); }
    }
}
