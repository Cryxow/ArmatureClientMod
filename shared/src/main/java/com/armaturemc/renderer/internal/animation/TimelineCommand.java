package com.armaturemc.renderer.internal.animation;

import java.util.Map;

/** Backend-neutral visual consequence of one Blockbench timeline script. */
public sealed interface TimelineCommand permits TimelineCommand.PartVisibility, TimelineCommand.Signal,
    TimelineCommand.Effect, TimelineCommand.UnknownScript {
    record PartVisibility(String bone, boolean visible) implements TimelineCommand {
    }

    record Signal(String name) implements TimelineCommand {
    }

    /**
     * A renderer-owned Blockbench effect. The payload is kept as normalized
     * metadata so the native runtime can execute it without BetterModel.
     */
    record Effect(String type, String argument, Map<String, String> metadata)
        implements TimelineCommand {
        public Effect {
            if (type == null || type.isBlank()) throw new IllegalArgumentException("Effect type is blank");
            argument = argument == null ? "" : argument.trim();
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }

    /** Unsupported scripts remain explicit for diagnostics instead of firing false signals. */
    record UnknownScript(String script) implements TimelineCommand {
    }
}
