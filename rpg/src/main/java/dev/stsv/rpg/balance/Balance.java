package dev.stsv.rpg.balance;

import org.bukkit.configuration.ConfigurationSection;

import java.util.EnumMap;
import java.util.Map;

/**
 * Every number of the game comes from here (plan: "모든 숫자는 공식이 만든다").
 *
 * The targets (how long a mob of each role takes to kill for a reference player, how many hits a
 * reference player survives) are set in config.yml; mob health, mob attack, experience and the
 * level curve are derived from them. People change coefficients, never individual numbers.
 *
 * Reference player of level L: base attack/health for L times the reference gear factors (rare +3
 * gear), the reference damage reduction, base crit, and one basic attack every basic-interval seconds.
 */
public final class Balance {

    public final double growth, baseAttack, baseHealth;
    public final double defenseBase, defensePerLevel, maxReduction;
    public final double baseCritChance, baseCritDamage, critChanceCap, critDamageCap, variance;
    public final double basicInterval, refGearAttack, refGearHealth, refReduction, mobReduction;
    public final double advantage, disadvantage;
    public final double baseStamina, baseStaminaRegen;
    public final int levelCap, levelGap;
    public final double overLevelExpStep, overLevelExpMin, underLevelHurtStep, underLevelHurtMax;
    public final double expBase, killsPerMinute;
    private final double[][] minutesCurve;
    private final Map<Role, Double> seconds = new EnumMap<>(Role.class), hits = new EnumMap<>(Role.class), exp = new EnumMap<>(Role.class);

    public Balance(ConfigurationSection c) {
        growth = req(c, "growth");
        baseAttack = req(c, "base-attack");
        baseHealth = req(c, "base-health");
        defenseBase = req(c, "defense.base");
        defensePerLevel = req(c, "defense.per-level");
        maxReduction = req(c, "defense.max-reduction");
        baseCritChance = req(c, "crit.base-chance");
        baseCritDamage = req(c, "crit.base-damage");
        critChanceCap = req(c, "crit.chance-cap");
        critDamageCap = req(c, "crit.damage-cap");
        variance = req(c, "variance");
        basicInterval = req(c, "basic-attack-seconds");
        refGearAttack = req(c, "reference.gear-attack");
        refGearHealth = req(c, "reference.gear-health");
        refReduction = req(c, "reference.player-reduction");
        mobReduction = req(c, "reference.mob-reduction");
        advantage = req(c, "element.advantage");
        disadvantage = req(c, "element.disadvantage");
        baseStamina = req(c, "stamina.base");
        baseStaminaRegen = req(c, "stamina.regen-per-second");
        levelCap = (int) req(c, "level.cap");
        levelGap = (int) req(c, "level.gap");
        overLevelExpStep = req(c, "level.over-exp-step");
        overLevelExpMin = req(c, "level.over-exp-min");
        underLevelHurtStep = req(c, "level.under-hurt-step");
        underLevelHurtMax = req(c, "level.under-hurt-max");
        expBase = req(c, "exp.base");
        killsPerMinute = req(c, "exp.kills-per-minute");
        var pts = c.getMapList("exp.minutes-per-level");
        if (pts.isEmpty()) throw new IllegalArgumentException("balance.exp.minutes-per-level 가 비어 있음");
        minutesCurve = new double[pts.size()][2];
        for (int i = 0; i < pts.size(); i++) {
            Map<?, ?> m = pts.get(i);
            minutesCurve[i][0] = ((Number) m.get("level")).doubleValue();
            minutesCurve[i][1] = ((Number) m.get("minutes")).doubleValue();
        }
        for (Role r : Role.values()) {
            seconds.put(r, req(c, "roles." + r.id() + ".kill-seconds"));
            hits.put(r, req(c, "roles." + r.id() + ".hits-to-down"));
            exp.put(r, req(c, "roles." + r.id() + ".exp"));
        }
    }

    private static double req(ConfigurationSection c, String key) {
        if (!c.contains(key)) throw new IllegalArgumentException("config.yml 의 balance." + key + " 가 없음");
        return c.getDouble(key);
    }

    // ------------------------------------------------------------------------------------------
    // Level curves
    // ------------------------------------------------------------------------------------------

    private double scale(int level) {
        return Math.pow(growth, Math.max(1, level) - 1);
    }

    /** Base attack of a player of this level (no gear). */
    public double attack(int level) {
        return baseAttack * scale(level);
    }

    /** Base maximum health of a player of this level (no gear). */
    public double health(int level) {
        return baseHealth * scale(level);
    }

    /** Damage reduction from a defense value against an attacker of this level, capped. */
    public double reduction(double defense, int attackerLevel) {
        if (defense <= 0) return 0;
        return Math.min(maxReduction, defense / (defense + defenseBase + defensePerLevel * Math.max(1, attackerLevel)));
    }

    /** The defense value that gives {@code reduction} against an attacker of this level. */
    public double defenseFor(double reduction, int attackerLevel) {
        return reduction / (1 - reduction) * (defenseBase + defensePerLevel * Math.max(1, attackerLevel));
    }

    // ------------------------------------------------------------------------------------------
    // The reference player and mobs derived from the targets
    // ------------------------------------------------------------------------------------------

    /** Damage per second of the reference player of this level against a same-level mob. */
    public double referenceDps(int level) {
        double hitsPerSecond = 1 / basicInterval;
        double critFactor = 1 + baseCritChance * (baseCritDamage - 1);
        return attack(level) * refGearAttack * hitsPerSecond * critFactor * (1 - mobReduction);
    }

    public double killSeconds(Role role) {
        return seconds.get(role);
    }

    public double hitsToDown(Role role) {
        return hits.get(role);
    }

    /** Maximum health of a mob: the reference player kills it in its role's target time. */
    public double mobHealth(int level, Role role) {
        return referenceDps(level) * seconds.get(role);
    }

    /** Defense of a mob of this level (gives the reference mob reduction against same-level players). */
    public double mobDefense(int level) {
        return defenseFor(mobReduction, level);
    }

    /** Attack of a mob: its ordinary hit downs the reference player in its role's number of hits. */
    public double mobAttack(int level, Role role) {
        double n = hits.get(role);
        if (n <= 0) return 0;
        return health(level) * refGearHealth / (n * (1 - refReduction));
    }

    // ------------------------------------------------------------------------------------------
    // Experience
    // ------------------------------------------------------------------------------------------

    /** Experience a mob gives, before the level-difference rule. */
    public double mobExp(int level, Role role) {
        return expBase * scale(level) * exp.get(role);
    }

    /** Planned minutes of play to go from this level to the next (linear between the curve points). */
    public double minutesForLevel(int level) {
        double[][] p = minutesCurve;
        if (level <= p[0][0]) return p[0][1];
        for (int i = 1; i < p.length; i++) {
            if (level <= p[i][0]) {
                double t = (level - p[i - 1][0]) / (p[i][0] - p[i - 1][0]);
                return p[i - 1][1] + (p[i][1] - p[i - 1][1]) * t;
            }
        }
        return p[p.length - 1][1];
    }

    /** Experience needed from this level to the next: the planned minutes of same-level normal kills. */
    public long expToNext(int level) {
        if (level >= levelCap) return Long.MAX_VALUE;
        return Math.max(1, Math.round(minutesForLevel(level) * killsPerMinute * mobExp(level, Role.NORMAL)));
    }

    /** Experience multiplier when the player out-levels the mob (no penalty for fighting stronger mobs). */
    public double expFactor(int playerLevel, int mobLevel) {
        int over = playerLevel - mobLevel;
        if (over < levelGap) return 1;
        return Math.max(overLevelExpMin, 1 - overLevelExpStep * (over - levelGap + 1));
    }

    /** Extra damage a player takes from a mob that out-levels them by the gap or more. */
    public double hurtFactor(int playerLevel, int mobLevel) {
        int under = mobLevel - playerLevel;
        if (under < levelGap) return 1;
        return Math.min(underLevelHurtMax, 1 + underLevelHurtStep * (under - levelGap + 1));
    }
}
