package dev.stsv.economy.secret;

import dev.stsv.economy.EcoPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import java.util.List;
import java.util.UUID;

/**
 * One hidden secret (docs/admin/숨은_비밀.md): found by one player, solved by one player per server. The module keeps who
 * is at which stage, whether it is on, who solved it and each player's test mode; a secret only plays its story.
 * <p>
 * Stage 0 = not started. A secret's own listeners are registered by the module (a secret is a Listener).
 */
public abstract class Secret implements Listener {

    protected final SecretModule m;
    protected final EcoPlugin eco;
    protected YamlConfiguration cfg = new YamlConfiguration();

    protected Secret(SecretModule m) {
        this.m = m;
        this.eco = m.eco();
    }

    /** Short ascii id: kv keys, the file contents/economy/secrets/<id>.yml, admin commands use the Korean name too. */
    public abstract String id();

    /** Korean name for admins and the solved line ("동방해운"). */
    public abstract String name();

    /** How many stages before the end (the status shows n/stages). */
    public abstract int stages();

    /** (Re)reads its settings. */
    void load(YamlConfiguration y) {
        cfg = y;
        loaded();
    }

    protected void loaded() {}

    /** Starts it for this player now, whatever the chance, hour or conditions (admin 시작, or its own trigger). */
    public abstract void start(Player p);

    /** Jumps a player to a stage (admin 단계): the secret sets up whatever that stage needs. */
    public void jump(Player p, int stage) {
        m.stage(this, p.getUniqueId(), stage);
    }

    /** Takes away whatever it shows a player (visuals, effects) and forgets in-memory state; the saved stage stays. */
    public void cleanup(UUID player) {}

    /** Called each second (main thread) while enabled. */
    public void tick() {}

    /** Status line extras for /관리자 비밀 상태 (after "단계 n/max"). */
    public String status(UUID player) {
        return "";
    }

    /** Extra admin words: /관리자 비밀 연출 <비밀> <word> [플레이어] — plays one scene for a quick look. */
    public List<String> scenes() {
        return List.of();
    }

    /** Plays one scene (see {@link #scenes}) on a player. Returns false when the word is unknown. */
    public boolean scene(CommandSender admin, Player p, String word) {
        return false;
    }

    /** Whether it is on (admin 켜기/끄기 over the file's enabled). */
    protected boolean on() {
        return m.enabled(this);
    }

    /** Whether this player may still go on: on, and not solved by someone else (a tester always may). */
    protected boolean open(UUID player) {
        return on() && (m.test(player) || !m.closed(this));
    }
}
