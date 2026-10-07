package dev.punctualboat.mantis.runtime;

import org.graalvm.polyglot.Value;

public final class PromiseRejectedException extends RuntimeException {
    private final String scriptStack;
    public PromiseRejectedException(Value reason) {
        super(text(reason, "message"));
        scriptStack = reason != null && reason.hasMember("stack") ? text(reason, "stack") : "";
    }
    public String scriptStack() { return scriptStack; }
    private static String text(Value value, String field) {
        if (value == null || value.isNull()) return "Promise rejected without a reason";
        Value member = value.hasMember(field) ? value.getMember(field) : value;
        String text = member == null ? "Promise rejected" : member.isString() ? member.asString() : member.toString();
        return text.length() <= 8192 ? text : text.substring(0, 8192);
    }
}
