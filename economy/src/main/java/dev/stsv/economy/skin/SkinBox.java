package dev.stsv.economy.skin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/** 골드 스킨북 상자's draw, apart from the server so it can be tested. */
public final class SkinBox {

    private SkinBox() {}

    /** The book a player draws next: one they have not drawn this round (all of them again once the round is done). */
    public static String draw(List<String> books, Set<String> drawn, RandomGenerator r) {
        List<String> left = new ArrayList<>();
        for (String b : books) if (!drawn.contains(b)) left.add(b);
        if (left.isEmpty()) {
            drawn.clear();
            left.addAll(books);
        }
        String pick = left.get(r.nextInt(left.size()));
        drawn.add(pick);
        return pick;
    }
}
