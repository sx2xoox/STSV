package dev.stsv.rpg;

import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.rpg.balance.Balance;
import dev.stsv.rpg.balance.Element;
import dev.stsv.rpg.balance.Role;
import dev.stsv.rpg.combat.CombatModule;
import dev.stsv.rpg.player.PlayerModule;
import dev.stsv.rpg.player.RpgPlayer;
import dev.stsv.rpg.stat.Stat;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** /스탯 (players) and /rpg (admin: level, experience, test mobs, target info). */
final class Commands {

    private Commands() {
    }

    static void register(RpgPlugin rpg) {
        set(rpg, "스탯", new Stats(rpg));
        set(rpg, "rpg", new Admin(rpg));
    }

    private static void set(RpgPlugin rpg, String name, TabExecutor ex) {
        PluginCommand c = rpg.getCommand(name);
        if (c == null) {
            rpg.getLogger().warning("plugin.yml 에 없는 명령어: " + name);
            return;
        }
        c.setExecutor(ex);
        c.setTabCompleter(ex);
    }

    private record Stats(RpgPlugin rpg) implements TabExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
            if (!(s instanceof Player p)) {
                s.sendMessage(Lang.msg("rpg.players-only"));
                return true;
            }
            RpgPlayer rp = rpg.players().get(p);
            long next = rpg.balance().expToNext(rp.level());
            p.sendMessage(Lang.mm("rpg.stats.header", "player", p.getName(), "level", rp.level(),
                    "exp", PlayerModule.fmt(rp.exp()), "next", next == Long.MAX_VALUE ? "-" : PlayerModule.fmt(next)));
            for (Stat st : Stat.values()) {
                double v = rp.stats().get(st);
                if (v == 0 && st != Stat.DEFENSE) continue;
                p.sendMessage(Lang.mm("rpg.stats.line", "stat", st.label, "value", st.format(v)));
            }
            return true;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
            return List.of();
        }
    }

    private record Admin(RpgPlugin rpg) implements TabExecutor {
        @Override
        public boolean onCommand(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
            try {
                run(s, a);
            } catch (RuntimeException e) {
                s.sendMessage(Text.msg("<red>오류: " + e.getMessage()));
            }
            return true;
        }

        private void run(CommandSender s, String[] a) {
            if (a.length == 0) {
                s.sendMessage(Lang.msg("rpg.admin.usage"));
                return;
            }
            Balance b = rpg.balance();
            switch (a[0]) {
                case "레벨", "level" -> {
                    Player t = player(s, a, 1);
                    if (t == null) return;
                    int lv = Integer.parseInt(a[2]);
                    rpg.players().setLevel(t, lv);
                    s.sendMessage(Lang.msg("rpg.admin.level-set", "player", t.getName(), "level", rpg.players().get(t).level()));
                }
                case "경험치", "exp" -> {
                    Player t = player(s, a, 1);
                    if (t == null) return;
                    long n = Long.parseLong(a[2].replace(",", ""));
                    rpg.players().giveExp(t, n);
                    s.sendMessage(Lang.msg("rpg.admin.exp-given", "player", t.getName(), "exp", PlayerModule.fmt(n)));
                }
                case "몹", "mob" -> {
                    if (!(s instanceof Player p)) {
                        s.sendMessage(Lang.msg("rpg.players-only"));
                        return;
                    }
                    int lv = Math.max(1, Math.min(b.levelCap, Integer.parseInt(a[1])));
                    Role role = a.length > 2 ? Role.parse(a[2]) : Role.NORMAL;
                    Element el = a.length > 3 ? Element.parse(a[3]) : Element.NONE;
                    Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(4));
                    LivingEntity mob = (LivingEntity) p.getWorld().spawnEntity(at, EntityType.ZOMBIE);
                    rpg.combat().assign(mob, lv, role, el);
                    s.sendMessage(Lang.msg("rpg.admin.mob-spawned", "role", role.label, "level", lv,
                            "hp", PlayerModule.fmt(b.mobHealth(lv, role)), "atk", PlayerModule.fmt(b.mobAttack(lv, role)),
                            "def", PlayerModule.fmt(b.mobDefense(lv))));
                }
                case "정보", "info" -> {
                    if (!(s instanceof Player p)) {
                        s.sendMessage(Lang.msg("rpg.players-only"));
                        return;
                    }
                    Entity e = p.getTargetEntity(16);
                    CombatModule.MobInfo m = e == null ? null : rpg.combat().info(e);
                    if (!(e instanceof LivingEntity le) || m == null) {
                        s.sendMessage(Lang.msg("rpg.admin.no-target"));
                        return;
                    }
                    s.sendMessage(Lang.msg("rpg.admin.info", "name", CombatModule.nameOf(le), "level", m.level(), "role", m.role().label,
                            "hp", PlayerModule.fmt(rpg.combat().health(le, m)), "max", PlayerModule.fmt(rpg.combat().maxHealth(m)),
                            "atk", PlayerModule.fmt(b.mobAttack(m.level(), m.role())), "def", PlayerModule.fmt(b.mobDefense(m.level())),
                            "element", m.element().label));
                }
                default -> s.sendMessage(Lang.msg("rpg.admin.usage"));
            }
        }

        private Player player(CommandSender s, String[] a, int i) {
            if (a.length <= i + 1) {
                s.sendMessage(Lang.msg("rpg.admin.usage"));
                return null;
            }
            Player t = Bukkit.getPlayerExact(a[i]);
            if (t == null) s.sendMessage(Lang.msg("rpg.admin.no-player", "name", a[i]));
            return t;
        }

        @Override
        public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command c, @NotNull String l, String @NotNull [] a) {
            List<String> out = new ArrayList<>();
            if (a.length == 1) out.addAll(List.of("레벨", "경험치", "몹", "정보"));
            else if (a.length == 2 && (a[0].equals("레벨") || a[0].equals("경험치"))) Bukkit.getOnlinePlayers().forEach(p -> out.add(p.getName()));
            else if (a.length == 3 && a[0].equals("몹")) for (Role r : Role.values()) out.add(r.label.replace(" ", ""));
            else if (a.length == 4 && a[0].equals("몹")) for (Element e : Element.values()) out.add(e.label);
            String last = a.length == 0 ? "" : a[a.length - 1].toLowerCase(Locale.ROOT);
            out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(last));
            return out;
        }
    }
}
