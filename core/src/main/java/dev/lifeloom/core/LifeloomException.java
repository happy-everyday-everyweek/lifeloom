package dev.lifeloom.core;

/** Lifeloom 核心统一异常。 */
public class LifeloomException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LifeloomException(String message) {
        super(message);
    }

    public LifeloomException(String message, Throwable cause) {
        super(message, cause);
    }
}