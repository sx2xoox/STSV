package dev.stsv.core.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Serves the resource pack on the Minecraft port itself, so no second port has to be opened.
 *
 * Paper calls every ChannelInitializeListener once a new connection's pipeline is set up
 * (io.papermc.paper.network.ChannelInitializeListenerHolder, internal). For each connection we put a
 * small handler in front of the Minecraft ones that looks at the first bytes: "GET " is an HTTP request
 * (a Minecraft client starts with a packet length and the packet id 0, never "GET "), anything else is
 * handed on untouched and the handler removes itself. HTTP requests for {@link #PATH} get the pack and the
 * connection is closed; the Minecraft handlers never see them. Other HTTP requests are handed on untouched too,
 * so another plugin can serve its own files on the same port.
 *
 * Does not work when something in front of the server only lets Minecraft traffic through (proxies
 * such as Velocity or BungeeCord, DDoS protection such as TCPShield): use the separate port then.
 */
final class SamePortHost {

    private static final Key KEY = Key.key("stsv", "pack_http");
    private static final byte[] GET = "GET ".getBytes(StandardCharsets.US_ASCII);
    private static final int MAX_REQUEST = 8192;
    private static final String HANDLER = "stsv_pack_http";
    /** Requests under this path are ours; any other HTTP request is handed on to the next handler. */
    static final String PATH = "/pack/";

    private final Supplier<PackBuilder.Built> pack;
    private final Logger log;
    private final Class<?> holder;
    private final Class<?> listenerType;

    /** @throws ReflectiveOperationException if this server has no channel initialize listeners */
    SamePortHost(Supplier<PackBuilder.Built> pack, Logger log) throws ReflectiveOperationException {
        this.pack = pack;
        this.log = log;
        ClassLoader cl = Bukkit.getServer().getClass().getClassLoader();
        holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", true, cl);
        listenerType = Class.forName("io.papermc.paper.network.ChannelInitializeListener", true, cl);
    }

    void install() throws ReflectiveOperationException {
        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[]{listenerType}, (proxy, m, args) -> {
            switch (m.getName()) {
                case "afterInitChannel" -> attach((Channel) args[0]);
                case "hashCode" -> { return System.identityHashCode(proxy); }
                case "equals" -> { return proxy == args[0]; }
                case "toString" -> { return "STSV resource pack on the game port"; }
                default -> { }
            }
            return null;
        });
        Method remove = holder.getMethod("removeListener", Key.class);
        remove.invoke(null, KEY); // a reload leaves the previous one behind
        holder.getMethod("addListener", Key.class, listenerType).invoke(null, KEY, listener);
    }

    void uninstall() {
        try {
            holder.getMethod("removeListener", Key.class).invoke(null, KEY);
        } catch (ReflectiveOperationException e) {
            log.log(Level.FINE, "removeListener", e);
        }
    }

    private void attach(Channel ch) {
        var p = ch.pipeline();
        if (p.get(HANDLER) != null) return;
        // as early as possible: right after the read timeout, before the proxy protocol and Minecraft decoders
        if (p.get("timeout") != null) p.addAfter("timeout", HANDLER, new Sniffer(pack));
        else p.addFirst(HANDLER, new Sniffer(pack));
    }

    /** Per connection: HTTP request -> the pack; anything else -> the Minecraft handlers. */
    static final class Sniffer extends ChannelInboundHandlerAdapter {
        private final Supplier<PackBuilder.Built> pack;
        private ByteBuf buffered;
        private boolean http, answered;

        Sniffer(Supplier<PackBuilder.Built> pack) {
            this.pack = pack;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf in)) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (answered) { // the client keeps talking after the answer: ignore, the connection is closing
                in.release();
                return;
            }
            if (buffered == null) buffered = ctx.alloc().buffer();
            buffered.writeBytes(in);
            in.release();
            if (!http) {
                int n = Math.min(buffered.readableBytes(), GET.length);
                for (int i = 0; i < n; i++) {
                    if (buffered.getByte(buffered.readerIndex() + i) != GET[i]) {
                        passOn(ctx);
                        return;
                    }
                }
                if (n < GET.length) return; // could still be "GET ": wait for more
                http = true;
            }
            String request = buffered.toString(StandardCharsets.ISO_8859_1);
            int end = request.indexOf("\r\n\r\n");
            if (end < 0) {
                if (buffered.readableBytes() > MAX_REQUEST) ctx.close();
                return;
            }
            String line = request.substring(0, request.indexOf("\r\n"));
            String[] parts = line.split(" ");
            String path = parts.length >= 2 ? parts[1] : "";
            if (!path.startsWith(PATH)) {
                // not ours (e.g. another plugin serving its own pack on this port): hand it on untouched
                passOn(ctx);
                return;
            }
            buffered.release();
            buffered = null;
            answered = true;
            // a download takes longer than the game's read timeout allows; use our own limit
            if (ctx.pipeline().get("timeout") != null) ctx.pipeline().remove("timeout");
            ctx.executor().schedule(() -> ctx.close(), 120, TimeUnit.SECONDS);
            respond(ctx, path);
        }

        /** Not HTTP: give the bytes to the Minecraft handlers and step out of the way. */
        private void passOn(ChannelHandlerContext ctx) {
            ByteBuf data = buffered;
            buffered = null;
            ctx.fireChannelRead(data);
            ctx.pipeline().remove(this);
        }

        private void respond(ChannelHandlerContext ctx, String path) {
            PackBuilder.Built built = pack.get();
            byte[] body;
            String status;
            String type;
            if (built != null) {
                body = built.zip();
                status = "200 OK";
                type = "application/zip";
            } else {
                body = "Not Found".getBytes(StandardCharsets.US_ASCII);
                status = "404 Not Found";
                type = "text/plain";
            }
            byte[] head = ("HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: " + type + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            ctx.writeAndFlush(Unpooled.wrappedBuffer(head, body)).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (buffered != null) {
                buffered.release();
                buffered = null;
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (http) ctx.close();
            else ctx.fireExceptionCaught(cause);
        }
    }
}
