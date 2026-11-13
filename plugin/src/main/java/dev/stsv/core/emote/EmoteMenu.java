package dev.stsv.core.emote;

import dev.stsv.core.util.Lang;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The emote buttons: a screen of the game's own (a dialog) with one button for every emote, opened by /감정표현
 * without a word after it and by the quick key (Ctrl + Shift while standing still). A click runs /감정표현 with that
 * emote's name as the player, so everything the command checks is checked.
 */
final class EmoteMenu {

    /** The quick key opens the buttons no more often than this. */
    static final long QUICK_KEY_GAP_MS = 1000;
    /** Buttons side by side, and how wide one is (pixels of the game's screen). */
    static final int COLUMNS = 2, BUTTON_WIDTH = 130;

    private EmoteMenu() {}

    /** The quick key: sneak and sprint held, nothing else (not walking, not jumping). */
    static boolean quickKey(boolean sneak, boolean sprint, boolean moving) {
        return sneak && sprint && !moving;
    }

    /** What a button runs: the emote command with the emote's name. */
    static String command(String name) {
        return "/감정표현 " + name;
    }

    static void open(Player p, Collection<EmoteSpec> emotes, boolean playing) {
        List<ActionButton> buttons = new ArrayList<>();
        for (EmoteSpec e : emotes) {
            buttons.add(ActionButton.builder(Lang.mm("emote.menu-button", "name", e.name()))
                    .tooltip(Lang.mm("emote.menu-tooltip", "name", e.name(), "seconds", String.format("%.1f", e.length() / 20.0),
                            "kind", Lang.raw(e.loop() ? "emote.menu-loop" : "emote.menu-once")))
                    .width(BUTTON_WIDTH)
                    .action(DialogAction.staticAction(ClickEvent.runCommand(command(e.name()))))
                    .build());
        }
        if (playing) {
            buttons.add(ActionButton.builder(Lang.mm("emote.menu-stop")).width(BUTTON_WIDTH)
                    .action(DialogAction.staticAction(ClickEvent.runCommand(command("그만")))).build());
        }
        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(Lang.mm("emote.menu-title"))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Lang.mm("emote.menu-hint"))))
                        .build())
                .type(DialogType.multiAction(buttons).columns(COLUMNS)
                        .exitAction(ActionButton.builder(Lang.mm("emote.menu-close")).width(BUTTON_WIDTH).build())
                        .build()));
        p.showDialog(dialog);
    }
}
