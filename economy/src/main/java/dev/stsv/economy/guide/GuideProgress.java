package dev.stsv.economy.guide;

import java.util.Map;

/**
 * Where a player is in one topic: the step and how many times its goal was met so far. Pure: signals in, results out.
 * A step advances only when its own goal is met (a signal for another step is ignored).
 */
public final class GuideProgress {

    public enum Result { NONE, COUNTED, STEP_DONE, TOPIC_DONE }

    private final GuideSpec.Topic topic;
    private int step;
    private int count;

    public GuideProgress(GuideSpec.Topic topic) {
        this.topic = topic;
    }

    public GuideSpec.Topic topic() {
        return topic;
    }

    /** The current step's index (0-based); equals the number of steps once done. */
    public int step() {
        return step;
    }

    /** How many times the current goal was met. */
    public int count() {
        return count;
    }

    public boolean done() {
        return step >= topic.steps().size();
    }

    /** The current step, or null once done. */
    public GuideSpec.Step current() {
        return done() ? null : topic.steps().get(step);
    }

    /** Something happened: counts when it answers the current goal; the step is done once the count is reached. */
    public Result signal(String type, Map<String, String> data) {
        GuideSpec.Step s = current();
        if (s == null || !s.goal().matches(type, data)) return Result.NONE;
        count++;
        if (count < s.goal().count()) return Result.COUNTED;
        count = 0;
        step++;
        return done() ? Result.TOPIC_DONE : Result.STEP_DONE;
    }

    /** "2/4": the step shown as 1-based out of all. */
    public String position() {
        return Math.min(step + 1, topic.steps().size()) + "/" + topic.steps().size();
    }
}
