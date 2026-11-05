package dev.stsv.rpg.balance;

/**
 * The one damage formula of the game (plan: "실제 피해는 한 줄의 공식으로만 계산합니다").
 *
 * damage = attack × skill multiplier × crit × element × (1 + skill damage) × (1 + element damage)
 *          × (1 − reduction) × (1 − element resist) × variance × extra
 *
 * Pure: randomness comes in as arguments, so the same inputs always give the same result (tests,
 * the simulator).
 */
public final class Damage {

    private Damage() {
    }

    /**
     * @param attack       attacker's attack stat
     * @param skillMult    the skill's multiplier (1 for a plain basic attack)
     * @param critChance   0..1 before the cap
     * @param critDamage   total multiplier on a crit (1.5 = +50%) before the cap
     * @param skillDamage  bonus fraction (0.2 = +20%), applies to everything but plain basic attacks
     * @param elementDamage bonus fraction for an attack that has an element
     * @param element      the attack's element
     * @param level        attacker level (defense is weighed against it)
     * @param forceCrit    weak points and some skills always crit
     */
    public record Hit(double attack, double skillMult, double critChance, double critDamage, double skillDamage,
                      double elementDamage, Element element, int level, boolean forceCrit) {
    }

    /** @param resist 0..1 resist against the hit's element */
    public record Target(double defense, Element element, double resist) {
    }

    public record Result(double amount, boolean crit, double elementMult) {
        public long rounded() {
            return Math.max(1, Math.round(amount));
        }

        public boolean advantage() {
            return elementMult > 1;
        }
    }

    /**
     * @param critRoll uniform 0..1; a crit happens when it is below the (capped) chance
     * @param varRoll  uniform -1..1, scaled by the balance variance
     * @param extra    other multipliers the caller owns (break state, level difference, party rules)
     */
    public static Result compute(Balance b, Hit h, Target t, double critRoll, double varRoll, double extra) {
        double chance = Math.min(b.critChanceCap, Math.max(0, h.critChance()));
        boolean crit = h.forceCrit() || critRoll < chance;
        double critMult = crit ? Math.min(b.critDamageCap, Math.max(1, h.critDamage())) : 1;
        double elem = h.element().against(t.element(), b.advantage, b.disadvantage);
        double elemBonus = h.element() == Element.NONE ? 1 : 1 + h.elementDamage();
        double resist = h.element() == Element.NONE ? 0 : Math.min(0.5, Math.max(0, t.resist()));
        double reduction = b.reduction(t.defense(), h.level());
        double var = 1 + b.variance * Math.max(-1, Math.min(1, varRoll));
        double amount = h.attack() * h.skillMult() * critMult * elem * (1 + h.skillDamage()) * elemBonus
                * (1 - reduction) * (1 - resist) * var * extra;
        return new Result(Math.max(0, amount), crit, elem);
    }
}
