package dev.stsv.core.command;

import org.bukkit.command.CommandSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * What content plugins add to /관리자: their own sub commands, a section of /관리자 보고서 and the
 * things /관리자 초기화 can wipe. The core's own words always win a name clash.
 */
public final class AdminRegistry {

    public interface Handler {
        /** args start after the sub command word. */
        void run(CommandSender sender, String[] args);
    }

    public interface Completer {
        /** args start after the sub command word; the last one is the word being typed. */
        List<String> complete(CommandSender sender, String[] args);
    }

    public interface ReportSection {
        /** args are what follows "보고서". */
        void write(CommandSender sender, String[] args);
    }

    public interface Reset {
        CompletableFuture<?> run(CommandSender by);
    }

    public record Sub(String owner, String name, String usage, String help, Handler handler, Completer completer) {}

    public record ResetTarget(String owner, String word, String help, Reset reset) {}

    private final Map<String, Sub> subs = new LinkedHashMap<>();
    private final Map<String, ReportSection> reports = new LinkedHashMap<>();
    private final Map<String, ResetTarget> resets = new LinkedHashMap<>();

    public void add(String owner, String name, String usage, String help, Handler handler, Completer completer) {
        subs.put(name, new Sub(owner, name, usage, help, handler, completer));
    }

    public void add(String owner, String name, String usage, String help, Handler handler) {
        add(owner, name, usage, help, handler, null);
    }

    public void report(String owner, ReportSection section) {
        reports.put(owner, section);
    }

    public void reset(String owner, String word, String help, Reset reset) {
        resets.put(word, new ResetTarget(owner, word, help, reset));
    }

    /** Drops everything a plugin added (on its disable). */
    public void removeAll(String owner) {
        subs.values().removeIf(s -> s.owner().equals(owner));
        reports.remove(owner);
        resets.values().removeIf(r -> r.owner().equals(owner));
    }

    public Sub sub(String name) {
        return subs.get(name);
    }

    public List<Sub> subs() {
        return new ArrayList<>(subs.values());
    }

    public Map<String, ReportSection> reports() {
        return Map.copyOf(reports);
    }

    public List<ReportSection> reportList() {
        return new ArrayList<>(reports.values());
    }

    public ResetTarget resetTarget(String word) {
        return resets.get(word);
    }

    public List<ResetTarget> resetTargets() {
        return new ArrayList<>(resets.values());
    }
}
