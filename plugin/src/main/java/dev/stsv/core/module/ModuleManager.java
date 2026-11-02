package dev.stsv.core.module;

import dev.stsv.core.STSVPlugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

public final class ModuleManager {

    private final STSVPlugin plugin;
    private final Map<String, StsvModule> modules = new LinkedHashMap<>();
    private final List<StsvModule> enabled = new ArrayList<>();

    public ModuleManager(STSVPlugin plugin) {
        this.plugin = plugin;
    }

    public void register(StsvModule module) {
        if (modules.putIfAbsent(module.id(), module) != null) {
            throw new IllegalStateException("Duplicate module id: " + module.id());
        }
        if (!enabled.isEmpty() || plugin.isEnabled()) {
            enable(module);
        }
    }

    public void enableAll() {
        for (StsvModule module : modules.values()) {
            if (!enabled.contains(module)) enable(module);
        }
    }

    private void enable(StsvModule module) {
        try {
            module.enable(plugin);
            enabled.add(module);
            plugin.getLogger().info("모듈 활성화: " + module.id());
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "모듈 활성화 실패: " + module.id(), e);
        }
    }

    /** Reloads all modules in order; returns the ids that failed. */
    public List<String> reloadAll() {
        List<String> failed = new ArrayList<>();
        for (StsvModule module : enabled) {
            try {
                module.reload();
            } catch (Exception e) {
                failed.add(module.id());
                plugin.getLogger().log(Level.SEVERE, "모듈 리로드 실패: " + module.id(), e);
            }
        }
        return failed;
    }

    /** Disables and forgets a module (a content plugin shutting down before the core). */
    public void unregister(String id) {
        StsvModule module = modules.remove(id);
        if (module == null) return;
        if (enabled.remove(module)) {
            try {
                module.disable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "모듈 비활성화 중 오류: " + module.id(), e);
            }
        }
    }

    public void disableAll() {
        List<StsvModule> reversed = new ArrayList<>(enabled);
        Collections.reverse(reversed);
        for (StsvModule module : reversed) {
            try {
                module.disable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "모듈 비활성화 중 오류: " + module.id(), e);
            }
        }
        enabled.clear();
    }

    @SuppressWarnings("unchecked")
    public <T extends StsvModule> Optional<T> get(String id, Class<T> type) {
        StsvModule m = modules.get(id);
        return type.isInstance(m) ? Optional.of((T) m) : Optional.empty();
    }
}
