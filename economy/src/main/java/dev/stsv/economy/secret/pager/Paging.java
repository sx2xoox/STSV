package dev.stsv.economy.secret.pager;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Text;
import dev.stsv.economy.util.Players;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the live pager does (pager.yml {@code page}): its owner right-clicks with it, types "받는 사람 숫자" — the line is
 * caught and never shown — and that player hears the triple beep and sees the digits. No sender, no way to answer.
 */
final class Paging {

    private final Pager s;
    /** player -> until when their next chat line is the page (read from the chat thread too) */
    private final Map<UUID, Long> typing = new ConcurrentHashMap<>();
    /** player -> when they last paged someone (kept over a relog) */
    private final Map<UUID, Long> last = new ConcurrentHashMap<>();

    Paging(Pager s) {
        this.s = s;
    }

    boolean enabled() {
        return s.cfg().getBoolean("page.enabled", true);
    }

    private long cooldownLeft(UUID u, long now) {
        Long at = last.get(u);
        long cd = Math.max(0, s.cfg().getLong("page.cooldown-seconds", 60)) * 1000L;
        return at == null ? 0 : Math.max(0, at + cd - now);
    }

    /** Right-clicked with the live pager: asks who and what. */
    void prompt(Player p) {
        UUID u = p.getUniqueId();
        long now = System.currentTimeMillis();
        Long until = typing.get(u);
        if (until != null && until > now) return;   // already asked: the next line is the page
        long left = cooldownLeft(u, now);
        if (left > 0) {
            HudModule.flash(p, Text.mm(s.text("page.lines.cooldown", "<dark_gray>…아직 화면이 식지 않았다. ({seconds}초)").replace("{seconds}", String.valueOf((left + 999) / 1000))), 2500);
            return;
        }
        typing.put(u, now + Math.max(5, s.cfg().getLong("page.timeout-seconds", 30)) * 1000L);
        p.sendMessage(Text.mm(s.text("page.lines.prompt", "<#9fd8a0>받는 사람과 숫자를 치세요 <gray>(예: 닉네임 8282)")));
        s.sound(p, p.getLocation(), s.text("beep.sound", "block.note_block.bit"), 0.4f, 1.6f);
    }

    /** Whether their next chat line is caught (any thread). */
    boolean capturing(UUID u) {
        Long until = typing.get(u);
        if (until == null) return false;
        if (until > System.currentTimeMillis()) return true;
        typing.remove(u);
        return false;
    }

    /** The caught line (main thread). */
    void typed(Player p, String text) {
        UUID u = p.getUniqueId();
        if (typing.remove(u) == null) return;
        if (!enabled() || !s.mayPage(p)) return;
        Codes.Page page = Codes.parsePage(text);
        if (page == null || page.digits() == null) {
            p.sendMessage(Text.mm(s.text("page.lines.bad", "<dark_gray>…삐삐는 숫자만 보낼 수 있다. 열두 자리까지.")));
            return;
        }
        Player to = Players.online(page.name());
        if (to == null) {
            p.sendMessage(Text.mm(s.text("page.lines.nobody", "<dark_gray>…신호가 가지 않는다. 그런 사람은 지금 없다.")));
            return;
        }
        last.put(u, System.currentTimeMillis());
        s.chirp(to, to.getLocation(), 1f);
        String line = s.text("page.lines.receive", "<#9fd8a0>삐삐삐— <white>{digits}").replace("{digits}", page.digits());
        HudModule.flash(to, Text.mm(line), 4000);
        if (s.cfg().getBoolean("page.chat", true)) to.sendMessage(Text.mm(line));
        p.sendMessage(Text.mm(s.text("page.lines.sent", "<dark_gray>…{digits}. 보냈다.").replace("{digits}", page.digits())));
        s.sound(p, p.getLocation(), s.text("beep.sound", "block.note_block.bit"), 0.4f, 1.9f);
    }

    void forget(UUID u) {
        typing.remove(u);
    }
}
