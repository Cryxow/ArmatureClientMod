package com.armaturemc.renderer.internal.animation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects scripts crossed by an animation clock and parses Armature-supported commands. */
public final class TimelineCursor {
    private static final Pattern PART_VISIBILITY = Pattern.compile(
        "^partvis\\{part=([^;{}]+);visible=(true|false)}$");
    private static final Pattern SIGNAL = Pattern.compile("^signal\\{([^{}]+)}$");
    private static final Pattern EFFECT = Pattern.compile(
        "^([a-zA-Z][a-zA-Z0-9_]*)\\{([^{}]*)}$");

    public List<TimelineCommand> crossed(NativeAnimation animation, double previousTime,
                                         double currentTime, boolean wrapped) {
        return crossed(animation, previousTime, 0L, currentTime, wrapped ? 1L : 0L);
    }

    /**
     * Returns events in authored chronological order, including every complete
     * loop between the two samples. The boolean overload remains useful for
     * callers which only have one modulo-time wrap.
     */
    public List<TimelineCommand> crossed(NativeAnimation animation, double previousTime,
                                         long previousLoop, double currentTime, long currentLoop) {
        return crossed(animation, previousTime, previousLoop, currentTime, currentLoop, false);
    }

    /**
     * Returns events crossed in the direction of playback. Reverse playback
     * emits events in descending authored-time order and handles skipped loops.
     */
    public List<TimelineCommand> crossed(NativeAnimation animation, double previousTime,
                                         long previousLoop, double currentTime, long currentLoop,
                                         boolean reverse) {
        if (!Double.isFinite(previousTime) || !Double.isFinite(currentTime)) {
            throw new IllegalArgumentException("Timeline times must be finite");
        }
        if (previousLoop < 0L || currentLoop < previousLoop) {
            throw new IllegalArgumentException("Timeline loop counters must be monotone");
        }
        List<TimelineCommand> result = new ArrayList<>();
        if (reverse) {
            if (currentLoop == previousLoop) {
                appendBeforeAfterReverse(animation, previousTime, currentTime, result);
                return List.copyOf(result);
            }
            appendBeforeReverse(animation, previousTime, result);
            for (long loop = previousLoop + 1L; loop < currentLoop; loop++) {
                appendAllReverse(animation, result);
            }
            appendFromEndReverse(animation, currentTime, result);
            return List.copyOf(result);
        }
        if (currentLoop == previousLoop) {
            appendAfterBefore(animation, previousTime, currentTime, result);
            return List.copyOf(result);
        }

        // Finish the current cycle, repeat complete cycles, then enter the
        // current one. This handles delayed renders which skipped cycles.
        appendAfter(animation, previousTime, result);
        for (long loop = previousLoop + 1L; loop < currentLoop; loop++) {
            appendAll(animation, result);
        }
        appendBefore(animation, currentTime, result);
        return List.copyOf(result);
    }

    private void appendBeforeAfterReverse(NativeAnimation animation, double previousTime,
                                          double currentTime, List<TimelineCommand> result) {
        for (int index = animation.timeline().size() - 1; index >= 0; index--) {
            NativeAnimation.TimelineEvent event = animation.timeline().get(index);
            if (event.time() < previousTime && event.time() >= currentTime) {
                result.add(parse(event.script()));
            }
        }
    }

    private void appendBeforeReverse(NativeAnimation animation, double previousTime,
                                     List<TimelineCommand> result) {
        for (int index = animation.timeline().size() - 1; index >= 0; index--) {
            NativeAnimation.TimelineEvent event = animation.timeline().get(index);
            if (event.time() < previousTime) result.add(parse(event.script()));
        }
    }

    private void appendFromEndReverse(NativeAnimation animation, double currentTime,
                                      List<TimelineCommand> result) {
        for (int index = animation.timeline().size() - 1; index >= 0; index--) {
            NativeAnimation.TimelineEvent event = animation.timeline().get(index);
            if (event.time() < animation.lengthSeconds() && event.time() >= currentTime) {
                result.add(parse(event.script()));
            }
        }
    }

    private void appendAllReverse(NativeAnimation animation, List<TimelineCommand> result) {
        for (int index = animation.timeline().size() - 1; index >= 0; index--) {
            result.add(parse(animation.timeline().get(index).script()));
        }
    }

    private void appendAfterBefore(NativeAnimation animation, double previousTime,
                                   double currentTime, List<TimelineCommand> result) {
        for (NativeAnimation.TimelineEvent event : animation.timeline()) {
            if (event.time() > previousTime && event.time() <= currentTime) {
                result.add(parse(event.script()));
            }
        }
    }

    private void appendAfter(NativeAnimation animation, double previousTime,
                             List<TimelineCommand> result) {
        for (NativeAnimation.TimelineEvent event : animation.timeline()) {
            if (event.time() > previousTime) result.add(parse(event.script()));
        }
    }

    private void appendBefore(NativeAnimation animation, double currentTime,
                              List<TimelineCommand> result) {
        for (NativeAnimation.TimelineEvent event : animation.timeline()) {
            if (event.time() <= currentTime) result.add(parse(event.script()));
        }
    }

    private void appendAll(NativeAnimation animation, List<TimelineCommand> result) {
        for (NativeAnimation.TimelineEvent event : animation.timeline()) {
            result.add(parse(event.script()));
        }
    }

    public TimelineCommand parse(String script) {
        Matcher visibility = PART_VISIBILITY.matcher(script);
        if (visibility.matches()) {
            return new TimelineCommand.PartVisibility(visibility.group(1),
                Boolean.parseBoolean(visibility.group(2)));
        }
        Matcher signal = SIGNAL.matcher(script);
        if (signal.matches()) return new TimelineCommand.Signal(signal.group(1));
        Matcher effect = EFFECT.matcher(script == null ? "" : script.trim());
        if (effect.matches()) {
            String type = effect.group(1).toLowerCase(Locale.ROOT);
            if (type.equals("sound") || type.equals("armaturesound")
                || type.equals("particle") || type.equals("armatureparticle")) {
                return parseEffect(type, effect.group(2));
            }
        }
        return new TimelineCommand.UnknownScript(script);
    }

    private static TimelineCommand.Effect parseEffect(String type, String body) {
        Map<String, String> metadata = new LinkedHashMap<>();
        String argument = "";
        for (String token : body.split(";", -1)) {
            String value = token.trim();
            if (value.isEmpty()) continue;
            int separator = value.indexOf('=');
            if (separator < 0) {
                if (argument.isEmpty()) argument = value;
                continue;
            }
            String key = value.substring(0, separator).trim().toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            metadata.put(key, value.substring(separator + 1).trim());
        }
        return new TimelineCommand.Effect(type, argument, metadata);
    }
}
