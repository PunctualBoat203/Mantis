package dev.punctualboat.mantis.core;

public final class HostCallException extends RuntimeException {
    private final String call;
    public HostCallException(String call, Throwable cause) {
        super(call + ": " + cause.getMessage(), cause);
        this.call = call;
    }
    public String call() { return call; }
}
