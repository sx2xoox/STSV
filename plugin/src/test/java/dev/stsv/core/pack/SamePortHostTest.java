package dev.stsv.core.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pack and Minecraft share one port: HTTP gets the pack, everything else reaches the game untouched. */
class SamePortHostTest {

    private static final byte[] ZIP = "PK-fake-zip-content".getBytes(StandardCharsets.US_ASCII);
    private static final PackBuilder.Built BUILT = new PackBuilder.Built(ZIP, "abc", 1, List.of(), Set.of());

    /** Stands for the Minecraft handlers: collects what reaches them. */
    private static final class Game extends ChannelInboundHandlerAdapter {
        final ByteArrayOutputStream got = new ByteArrayOutputStream();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf b = (ByteBuf) msg;
            byte[] a = new byte[b.readableBytes()];
            b.readBytes(a);
            b.release();
            got.writeBytes(a);
        }
    }

    private Game game;

    private EmbeddedChannel channel() {
        game = new Game();
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.pipeline().addLast("timeout", new ReadTimeoutHandler(30));
        ch.pipeline().addLast("stsv_pack_http", new SamePortHost.Sniffer(() -> BUILT));
        ch.pipeline().addLast("game", game);
        return ch;
    }

    private static ByteBuf bytes(String s) {
        return Unpooled.wrappedBuffer(s.getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String response(EmbeddedChannel ch) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuf b;
        while ((b = ch.readOutbound()) != null) {
            byte[] a = new byte[b.readableBytes()];
            b.readBytes(a);
            b.release();
            out.writeBytes(a);
        }
        return out.toString(StandardCharsets.ISO_8859_1);
    }

    @Test
    void servesThePackAndCloses() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(bytes("GET /pack/abc.zip HTTP/1.1\r\nHost: mc.example.com:25565\r\nUser-Agent: Minecraft\r\n\r\n"));
        String r = response(ch);
        assertTrue(r.startsWith("HTTP/1.1 200 OK\r\n"), r);
        assertTrue(r.contains("Content-Type: application/zip\r\n"));
        assertTrue(r.contains("Content-Length: " + ZIP.length + "\r\n"));
        assertTrue(r.endsWith("\r\n\r\n" + new String(ZIP, StandardCharsets.ISO_8859_1)));
        assertFalse(ch.isOpen(), "closed after the answer");
        assertEquals(0, game.got.size(), "the game never sees HTTP");
        assertNull(ch.pipeline().get("timeout"), "a download may take longer than the game's read timeout");
    }

    @Test
    void requestSplitAcrossPackets() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(bytes("G"));
        ch.writeInbound(bytes("ET /pack/abc.zip HT"));
        assertTrue(response(ch).isEmpty());
        ch.writeInbound(bytes("TP/1.1\r\nHost: x\r\n\r\n"));
        assertTrue(response(ch).startsWith("HTTP/1.1 200 OK"));
    }

    /** Another plugin may serve its own pack on the same port: requests for other paths reach the next handler. */
    @Test
    void otherPathsArePassedOnUntouched() {
        EmbeddedChannel ch = channel();
        String request = "GET /mcpl-pack/def.zip HTTP/1.1\r\nHost: x\r\n\r\n";
        ch.writeInbound(bytes("GET /mcpl"));
        ch.writeInbound(bytes(request.substring(9)));
        assertEquals(request, game.got.toString(StandardCharsets.ISO_8859_1));
        assertTrue(response(ch).isEmpty(), "not answered by us");
        assertNull(ch.pipeline().get("stsv_pack_http"), "steps out");
        assertNotNull(ch.pipeline().get("timeout"), "the game's read timeout stays");
        assertTrue(ch.isOpen());
    }

    /** Handshake: packet length, packet id 0, protocol, host, port, intent. Must arrive byte for byte. */
    @Test
    void minecraftHandshakePassesThroughUntouched() {
        EmbeddedChannel ch = channel();
        byte[] handshake = {0x10, 0x00, (byte) 0xF3, 0x05, 0x09, 'l', 'o', 'c', 'a', 'l', 'h', 'o', 's', 't', 0x63, (byte) 0xDD, 0x02};
        byte[] login = {0x05, 0x00, 0x01, 0x02, 0x03, 0x04};
        ch.writeInbound(Unpooled.wrappedBuffer(handshake));
        ch.writeInbound(Unpooled.wrappedBuffer(login));
        byte[] expected = new byte[handshake.length + login.length];
        System.arraycopy(handshake, 0, expected, 0, handshake.length);
        System.arraycopy(login, 0, expected, handshake.length, login.length);
        assertArrayEquals(expected, game.got.toByteArray());
        assertNull(ch.pipeline().get("stsv_pack_http"), "steps out after the first bytes");
        assertTrue(ch.isOpen());
        assertTrue(response(ch).isEmpty());
    }

    /** A packet length of 71 is the byte 'G'; the packet id that follows is 0, never 'E'. */
    @Test
    void handshakeStartingWithTheLetterG() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(bytes("G"));
        assertEquals(0, game.got.size(), "waits: could still be GET");
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{0x00, 0x01, 0x02}));
        assertArrayEquals(new byte[]{'G', 0x00, 0x01, 0x02}, game.got.toByteArray());
        assertTrue(ch.isOpen());
    }

    @Test
    void legacyServerListPingPassesThrough() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(Unpooled.wrappedBuffer(new byte[]{(byte) 0xFE, 0x01}));
        assertArrayEquals(new byte[]{(byte) 0xFE, 0x01}, game.got.toByteArray());
    }

    @Test
    void oversizedRequestIsDropped() {
        EmbeddedChannel ch = channel();
        ch.writeInbound(bytes("GET /pack/abc.zip HTTP/1.1\r\n" + "X: " + "a".repeat(9000)));
        assertFalse(ch.isOpen());
        assertEquals(0, game.got.size());
    }

    @Test
    void localhostPublicUrlCountsAsUnset() {
        assertTrue(PackModule.isLocal("http://localhost:8163"));
        assertTrue(PackModule.isLocal("http://127.0.0.1:8163"));
        assertFalse(PackModule.isLocal("http://mc.example.com:25565"));
        assertFalse(PackModule.isLocal("http://123.45.67.89:8163"));
    }
}
