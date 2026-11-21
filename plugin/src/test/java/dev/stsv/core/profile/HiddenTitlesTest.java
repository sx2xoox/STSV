package dev.stsv.core.profile;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** Hidden titles (hidden: true): out of /칭호 tabs and the n/m counts of players who do not have them. */
class HiddenTitlesTest {

    @Test
    void hiddenTitlesLeaveTheListsAndCountsOfNonOwners() {
        List<ProfileModule.Title> all = List.of(new ProfileModule.Title("economy:patron", "[기부왕]", "", "event"),
                new ProfileModule.Title("economy:secret_agent", "[비밀 요원]", "", "event", true),
                new ProfileModule.Title("economy:trader", "[상인]", "", "shop"));
        // a player without it: not in any tab, not in the totals
        assertEquals(List.of("economy:patron", "economy:trader"),
                ProfileModule.inTab(all, TitleCategories.ALL, Set.of(), null).stream().map(ProfileModule.Title::id).toList());
        assertEquals(List.of("economy:patron"), ProfileModule.inTab(all, "event", Set.of(), null).stream().map(ProfileModule.Title::id).toList());
        assertEquals(2, ProfileModule.visible(all, Set.of("economy:patron")).size());
        // its owner: listed (worn first) and counted
        assertEquals(List.of("economy:secret_agent", "economy:patron"),
                ProfileModule.inTab(all, "event", Set.of("economy:secret_agent"), "economy:secret_agent").stream().map(ProfileModule.Title::id).toList());
        assertEquals(3, ProfileModule.visible(all, Set.of("economy:secret_agent")).size());
        assertFalse(new ProfileModule.Title("a:x", "[x]", "", "event").hidden(), "not hidden by default");
    }
}
