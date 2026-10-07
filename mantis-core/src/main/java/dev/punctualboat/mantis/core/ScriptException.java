package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.SourceSection;
import java.util.*;

public final class ScriptException extends RuntimeException {
    public record Frame(String function, String source, int line, int column) {}
    private final String source;
    private final int line, column;
    private final List<Frame> scriptStack;
    private final Throwable javaCause;
    private final String hostCall;

    ScriptException(String operation) {
        super(operation + ": Execution limit exceeded");
        source = operation; line = column = 0; scriptStack = List.of(); javaCause = null; hostCall = null;
    }

    public ScriptException(PolyglotException cause) {
        super(describe(cause), cause);
        SourceSection location = location(cause);
        source = location == null ? "<callback>" : location.getSource().getName();
        line = location == null ? 0 : location.getStartLine();
        column = location == null ? 0 : location.getStartColumn();
        List<Frame> frames = new ArrayList<>();
        for (PolyglotException.StackFrame frame : cause.getPolyglotStackTrace()) {
            if (!frame.isGuestFrame()) continue;
            SourceSection section = frame.getSourceLocation();
            frames.add(new Frame(frame.getRootName(), section == null ? "<callback>" : section.getSource().getName(),
                    section == null ? 0 : section.getStartLine(), section == null ? 0 : section.getStartColumn()));
        }
        scriptStack = List.copyOf(frames);
        Throwable host = cause.isHostException() ? cause.asHostException() : null;
        hostCall = host instanceof HostCallException call ? call.call() : null;
        javaCause = host instanceof HostCallException ? host.getCause() : host;
    }

    public String source() { return source; }
    public int line() { return line; }
    public int column() { return column; }
    public List<Frame> scriptStack() { return scriptStack; }
    public Throwable javaCause() { return javaCause; }
    public String hostCall() { return hostCall; }

    public String format() {
        StringBuilder text = new StringBuilder(getMessage());
        for (Frame frame : scriptStack) text.append("\n  at ").append(frame.function()).append(" (")
                .append(frame.source()).append(':').append(frame.line()).append(':').append(frame.column()).append(')');
        if (javaCause != null) text.append("\n  caused by ").append(javaCause.getClass().getSimpleName()).append(": ").append(javaCause.getMessage());
        if (hostCall != null) text.append("\n  host call: ").append(hostCall);
        return text.toString();
    }

    private static String describe(PolyglotException error) {
        SourceSection location = location(error);
        String prefix = location == null ? "<callback>" : location.getSource().getName()
                + ":" + location.getStartLine() + ":" + location.getStartColumn();
        return prefix + ": " + (error.isCancelled() || error.isResourceExhausted() ? "Execution limit exceeded" : error.getMessage());
    }

    private static SourceSection location(PolyglotException error) {
        if (error.getSourceLocation() != null) return error.getSourceLocation();
        for (PolyglotException.StackFrame frame : error.getPolyglotStackTrace()) {
            if (frame.isGuestFrame() && frame.getSourceLocation() != null) return frame.getSourceLocation();
        }
        return null;
    }
}
