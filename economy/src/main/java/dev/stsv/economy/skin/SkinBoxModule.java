package dev.stsv.economy.skin;

import dev.stsv.core.hud.HudModule;
import dev.stsv.core.util.Lang;
import dev.stsv.core.util.Text;
import dev.stsv.economy.EcoModule;
import dev.stsv.economy.EcoPlugin;
import dev.stsv.economy.util.Items;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 골드 스킨북 상자 (skin_box.yml): right-click it and one of the gold skin books comes out. A player never draws a book
 * twice before they have them all: what they have drawn is kept on them (economy:skin_box_drawn); once every book has
 * come out the round starts again. The book is given at once; the names flicking by in the title are only a show.
 */
public final class SkinBoxModule extends EcoModule {

    public static final NamespacedKey DRAWN = new NamespacedKey("economy", "skin_box_drawn");
    private String boxItem = "economy:skin_box";
    private List<String> books = List.of();

    public SkinBoxModule(EcoPlugin eco) {
        super(eco);
    }

    @Override
    public String id() {
        return "economy-skin-box";
    }

    @Override
    protected void start() {
        YamlConfiguration y = eco.data("skin_box.yml");
        boxItem = Items.normalize(y.getString("item", boxItem));
        List<String> in = new ArrayList<>();
        for (String id : y.getStringList("books")) {
            String n = Items.normalize(id);
            if (Items.create(n, 1).isEmpty()) eco.getLogger().warning("[skin_box.yml] 모르는 아이템 " + id);
            else if (!in.contains(n)) in.add(n);
        }
        books = List.copyOf(in);
    }

    public List<String> books() {
        return books;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (e.getHand() != EquipmentSlot.HAND) return;
        ItemStack hand = e.getItem();
        if (hand == null || !boxItem.equals(Items.key(hand))) return;
        e.setCancelled(true);
        if (books.isEmpty()) return;
        Player p = e.getPlayer();
        Set<String> drawn = drawn(p);
        drawn.retainAll(books);   // a book taken out of the box since does not count
        boolean fresh = drawn.size() >= books.size();
        String pick = SkinBox.draw(books, drawn, ThreadLocalRandom.current());
        ItemStack book = Items.create(pick, 1).orElse(null);
        if (book == null) return;
        hand.subtract(1);
        p.getPersistentDataContainer().set(DRAWN, PersistentDataType.STRING, String.join(",", drawn));
        for (ItemStack left : p.getInventory().addItem(book).values()) eco.mail().deliver(p.getUniqueId(), left, "skin_box");
        if (fresh) HudModule.flash(p, Lang.mm("skin-box.round"));
        show(p, Items.mmName(book), drawn.size());
    }

    private Set<String> drawn(Player p) {
        String s = p.getPersistentDataContainer().get(DRAWN, PersistentDataType.STRING);
        Set<String> out = new LinkedHashSet<>();
        if (s != null) for (String id : s.split(",")) if (!id.isBlank()) out.add(id);
        return out;
    }

    /** Book names flick by in the title, slowing down, then the one drawn stays. */
    private void show(Player p, String name, int have) {
        int[] at = {0, 2, 4, 6, 9, 12, 16, 21, 27};
        Title.Times quick = Title.Times.times(Duration.ZERO, Duration.ofMillis(400), Duration.ZERO);
        for (int i = 0; i < at.length; i++) {
            boolean last = i == at.length - 1;
            float pitch = 1.0f + 0.1f * i;
            Bukkit.getScheduler().runTaskLater(eco, () -> {
                if (!p.isOnline()) return;
                if (last) {
                    p.showTitle(Title.title(Text.item(name), Lang.item("skin-box.count", "have", have, "all", books.size()),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(2000), Duration.ofMillis(500))));
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                    HudModule.flash(p, Lang.mm("skin-box.got", "item", name, "have", have, "all", books.size()));
                } else {
                    String other = Items.mmName(books.get(ThreadLocalRandom.current().nextInt(books.size())));
                    p.showTitle(Title.title(Text.item("<gray>" + Text.plain(other)), Lang.item("skin-box.rolling"), quick));
                    p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, pitch);
                }
            }, at[i]);
        }
    }
}
