package com.armaturemc.renderer.internal.diagnostics;

import java.util.Objects;

/** Actionable compiler diagnostic retained with the immutable native catalog. */
public record ModelDiagnostic(String modelId, Severity severity, String code, String message) {
    public ModelDiagnostic {
        modelId = modelId == null || modelId.isBlank() ? "unknown" : modelId;
        severity = Objects.requireNonNull(severity, "severity");
        code = requireText(code, "code");
        message = requireText(message, "message");
    }

    public static ModelDiagnostic warning(String modelId, String code, String message) {
        return new ModelDiagnostic(modelId, Severity.WARNING, code, message);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is blank");
        return value;
    }

    public enum Severity { WARNING, ERROR }
}
