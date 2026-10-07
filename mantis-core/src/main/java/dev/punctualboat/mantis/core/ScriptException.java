package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;

public final class ScriptException extends RuntimeException {
    private final String source;
    private final int line;
    private final int column;

    public ScriptException(PolyglotException cause) {
        super(describe(cause), cause);
        SourceSection location = location(cause);
        source = location == null ? "<callback>" : location.getSource().getName();
        line = location == null ? 0 : location.getStartLine();
        column = location == null ? 0 : location.getStartColumn();
    }

    public String source() { return source; }
    public int line() { return line; }
    public int column() { return column; }

    private static String describe(PolyglotException error) {
        SourceSection location = location(error);
        String prefix = location == null ? "<callback>" : location.getSource().getName()
                + ":" + location.getStartLine() + ":" + location.getStartColumn();
        return prefix + ": " + (error.isCancelled() ? "Execution limit exceeded" : error.getMessage());
    }

    private static SourceSection location(PolyglotException error) {
        if (error.getSourceLocation() != null) return error.getSourceLocation();
        for (PolyglotException.StackFrame frame : error.getPolyglotStackTrace()) {
            if (frame.isGuestFrame() && frame.getSourceLocation() != null) return frame.getSourceLocation();
        }
        return null;
    }
}
