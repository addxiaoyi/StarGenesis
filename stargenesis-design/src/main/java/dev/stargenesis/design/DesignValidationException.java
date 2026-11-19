package dev.stargenesis.design;

public final class DesignValidationException extends IllegalArgumentException {
    public DesignValidationException(String message) { super(message); }
    public DesignValidationException(String message, Throwable cause) { super(message, cause); }
}
