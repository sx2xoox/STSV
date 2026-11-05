package dev.stsv.economy;

import dev.stsv.economy.util.Items;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Item names in messages: each player's own language, no colour running on, no tags from renamed items. */
class ItemNameTest {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    @Test
    void vanillaNamesStayTranslationKeys() {
        // the client turns this into "청금석" for a Korean player
        assertEquals("<lang:item.minecraft.lapis_lazuli>", Items.mm(Component.translatable("item.minecraft.lapis_lazuli")));
        // rarity colour (an enchanted golden apple is pink) is dropped: the message decides the colour
        assertEquals("<lang:item.minecraft.enchanted_golden_apple>",
                Items.mm(Component.translatable("item.minecraft.enchanted_golden_apple").color(NamedTextColor.LIGHT_PURPLE)));
    }

    @Test
    void customNamesKeepTheirTextButNotTheirColour() {
        String name = Items.mm(MM.deserialize("<!italic><#9fb4c8>사파이어 원석"));
        assertEquals("사파이어 원석", name);
        // put into a message, the rest of the line keeps the message's colour
        Component line = MM.deserialize("<white>" + name + "</white> <gray>매입가");
        assertEquals("<white>사파이어 원석</white> <gray>매입가", MM.serialize(line));
    }

    @Test
    void aRenamedItemCannotAddTags() {
        String renamed = "<red>공짜 <click:run_command:/op me>클릭";
        String name = Items.mm(Component.text(renamed).decoration(TextDecoration.ITALIC, true));
        Component shown = MM.deserialize("<white>" + name);
        // read back, it is exactly what the player typed, as text: no colour, no click
        assertEquals(renamed, PlainTextComponentSerializer.plainText().serialize(shown));
        assertEquals("[]", clicks(shown).toString());
    }

    private static java.util.List<Object> clicks(Component c) {
        java.util.List<Object> out = new java.util.ArrayList<>();
        if (c.clickEvent() != null) out.add(c.clickEvent());
        for (Component child : c.children()) out.addAll(clicks(child));
        return out;
    }
}
