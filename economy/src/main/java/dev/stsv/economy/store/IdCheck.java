package dev.stsv.economy.store;

import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 신분증 확인 (README "편의점" 신분증, 기획안 §5): a young-looking customer with 술 · 담배 on the counter. The worker may look at
 * their ID ([신분증 확인]: a card with a birth year and a photo), then sells or refuses. A student sometimes hands over an
 * older sibling's card: its year says adult, but the photo is not their face. Selling to a minor (whatever the card said) is
 * a fine; refusing a real adult makes them angry. Pure (unit-tested).
 */
public final class IdCheck {

    private IdCheck() {}

    /**
     * How a customer kind looks at the till (store.yml id.kinds): `young` the chance they look young enough to be asked,
     * `minor` the chance one who looks young really is under age, `sibling` the chance a minor shows an older sibling's card,
     * `tries` the chance a kind that does not usually buy them tries to (a student with a beer).
     */
    public record Kind(double young, double minor, double sibling, double tries) {}

    /** The card shown: the name, the birth year printed on it, whether it is a sibling's (the photo is someone else). */
    public record Card(String name, int birthYear, boolean sibling, boolean minor) {
        /** Whether the year printed on it says adult (연 나이: this year less the birth year at least `age`). */
        public boolean saysAdult(int year, int age) {
            return adult(birthYear, year, age);
        }
    }

    /** What the worker did and how it went. */
    public enum Verdict {
        /** sold to an adult: right */
        SOLD_ADULT,
        /** sold to a minor (their own card or a sibling's): a fine */
        SOLD_MINOR,
        /** refused a minor: right (a little bonus) */
        REFUSED_MINOR,
        /** refused an adult: they are angry */
        REFUSED_ADULT;

        public boolean right() {
            return this == SOLD_ADULT || this == REFUSED_MINOR;
        }
    }

    /** 연 나이: adult when this year less the birth year is at least `age` (19: 청소년보호법). */
    public static boolean adult(int birthYear, int year, int age) {
        return year - birthYear >= age;
    }

    /** Whether these things need an ID check from this kind (any of `items` among them, the kind in `kinds`). */
    public static boolean restricted(List<String> things, Set<String> items) {
        for (String t : things) if (items.contains(t)) return true;
        return false;
    }

    /**
     * A card for a customer who looks young: a minor (by `minor`) shows their own card (an under-age year) or, by `sibling`, an
     * older sibling's (an adult year, someone else's face); an adult shows their own (`age` .. `age` + 8 years ago).
     */
    public static Card card(Kind k, int year, int age, List<String> names, RandomGenerator rnd) {
        boolean minor = rnd.nextDouble() < k.minor();
        boolean sibling = minor && rnd.nextDouble() < k.sibling();
        int birth = minor && !sibling ? year - (age - 1 - rnd.nextInt(4)) : year - (age + rnd.nextInt(9));
        String name = names.isEmpty() ? "홍길동" : names.get(rnd.nextInt(names.size()));
        return new Card(name, birth, sibling, minor);
    }

    /** How it went: sold or refused this customer (asked for the card or not: the truth is who they are). */
    public static Verdict judge(Card c, boolean sold) {
        if (sold) return c.minor() ? Verdict.SOLD_MINOR : Verdict.SOLD_ADULT;
        return c.minor() ? Verdict.REFUSED_MINOR : Verdict.REFUSED_ADULT;
    }
}
