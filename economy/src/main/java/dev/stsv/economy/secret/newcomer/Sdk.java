package dev.stsv.economy.secret.newcomer;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.LibraryLoader;

import java.io.InputStream;
import java.io.StringReader;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Where the Anthropic SDK comes from, fail-soft. It used to be in plugin.yml {@code libraries:}; but Paper resolves those
 * before the plugin loads and a failure there (an offline host, Maven Central blocked) stops the WHOLE economy plugin from
 * loading. Now nothing of the plugin refers to the SDK except {@link ClaudeCall}, which is defined in a class loader of its
 * own ({@link Bridge}) on top of the SDK's jars, fetched once in the background with the server's own library loader (the
 * same resolver, mirror and {@code libraries/} folder as plugin.yml libraries, so a server that once had them works
 * offline). Until they are there, or if they never come, he speaks from his scripts. Nothing here touches Bukkit off the
 * main thread except the download itself (which only reads the network and the libraries folder).
 */
final class Sdk {

    private Sdk() {}

    static final String COORDS = "com.anthropic:anthropic-java:3.3.0";
    static final String BRIDGE = "dev.stsv.economy.secret.newcomer.ClaudeCall";

    enum State { NONE, LOADING, READY, FAILED }

    private static volatile State state = State.NONE;
    private static volatile ClassLoader bridge;
    private static volatile String why;
    private static volatile Runnable onReady;

    static State state() {
        return state;
    }

    /** Why the SDK is not there (for 상태), or null. */
    static String why() {
        return why;
    }

    /**
     * Fetches the SDK once (later calls while it is loading only replace what runs when it is done). then: run on the main
     * thread when it is ready (not when it failed).
     */
    static synchronized void fetch(Plugin plugin, Logger log, Runnable then) {
        onReady = then;
        if (state != State.NONE) return;
        state = State.LOADING;
        Thread t = new Thread(() -> {
            try {
                ClassLoader b = bridge(libraries(log));
                Class<?> c = Class.forName(BRIDGE, true, b);   // linked against the SDK now, not at the first call
                c.getConstructor(String.class, long.class, Duration.class, Consumer.class);
                bridge = b;
                state = State.READY;
                log.info("[비밀] 새로 온 사람: AI 라이브러리 (" + COORDS + ") 를 불러왔습니다");
                Runnable r = onReady;
                if (r != null && plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, r);
            } catch (Throwable e) {   // anything at all (a resolution error, a missing class, a broken jar): no model
                if (e instanceof VirtualMachineError v) throw v;
                why = e.getClass().getSimpleName();
                state = State.FAILED;
                log.log(Level.WARNING, "[비밀] 새로 온 사람: AI 라이브러리 (" + COORDS + ") 를 받을 수 없어서 대본으로만 말합니다 (다른 기능은 모두 그대로)."
                        + " 서버가 Maven Central (또는 Paper 의 미러) 에 나갈 수 있으면 다음 시작 때 다시 시도합니다: " + e);
            }
        }, "stsv-newcomer-sdk");
        t.setDaemon(true);
        t.start();
    }

    /** The SDK's jars, by the server's library loader (what plugin.yml libraries would have done; throws on failure). */
    static ClassLoader libraries(Logger log) throws Exception {
        PluginDescriptionFile d = new PluginDescriptionFile(new StringReader(
                "name: STSV-Economy-AI\nversion: '1'\nmain: dev.stsv.economy.Ai\nlibraries:\n  - " + COORDS + "\n"));
        ClassLoader l = new LibraryLoader(log).createLoader(d);
        if (l == null) throw new IllegalStateException("library loader gave nothing");
        return l;
    }

    /** A loader that defines {@link ClaudeCall} itself (child-first for that one class) on top of the SDK's loader. */
    static ClassLoader bridge(ClassLoader libs) throws Exception {
        byte[] bytes;
        try (InputStream in = Sdk.class.getClassLoader().getResourceAsStream(BRIDGE.replace('.', '/') + ".class")) {
            if (in == null) throw new IllegalStateException("ClaudeCall.class is not in the plugin");
            bytes = in.readAllBytes();
        }
        return new Bridge(libs, bytes);
    }

    static final class Bridge extends ClassLoader {
        private final byte[] bytes;

        Bridge(ClassLoader parent, byte[] bytes) {
            super("stsv-newcomer-ai", parent);
            this.bytes = bytes;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (!name.equals(BRIDGE)) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> c = findLoadedClass(name);
                if (c == null) c = defineClass(name, bytes, 0, bytes.length);
                if (resolve) resolveClass(c);
                return c;
            }
        }
    }

    /**
     * A mind on the SDK in this bridge loader, or empty when it cannot be made (the SDK is not there or does not link):
     * never throws.
     */
    @SuppressWarnings("unchecked")
    static Optional<NewcomerMind> mind(ClassLoader bridge, String model, long maxTokens, Duration timeout, Consumer<String> problem) {
        try {
            Object o = Class.forName(BRIDGE, true, bridge).getConstructor(String.class, long.class, Duration.class, Consumer.class)
                    .newInstance(model, maxTokens, timeout, problem);
            return Optional.of(new ClaudeMind((BiFunction<String, String, String>) o, (AutoCloseable) o, problem));
        } catch (Throwable e) {
            if (e instanceof VirtualMachineError v) throw v;
            Throwable c = e instanceof java.lang.reflect.InvocationTargetException i && i.getCause() != null ? i.getCause() : e;
            problem.accept("AI 를 만들 수 없음 (" + c.getClass().getSimpleName() + ")");
            return Optional.empty();
        }
    }

    /** The mind when the SDK is ready, else empty (then {@link #fetch} it). */
    static Optional<NewcomerMind> mind(String model, long maxTokens, Duration timeout, Consumer<String> problem) {
        ClassLoader b = bridge;
        return state == State.READY && b != null ? mind(b, model, maxTokens, timeout, problem) : Optional.empty();
    }
}
