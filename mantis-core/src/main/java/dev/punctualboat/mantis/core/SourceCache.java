package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.Source;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class SourceCache {
    record Stats(long hits, long misses, long evictions, int entries, long bytes) {}
    private record Key(String name, URI uri, boolean module, String code) {}
    private record Entry(Source source, int bytes) {}
    private final LinkedHashMap<Key, Entry> entries = new LinkedHashMap<>(16, .75f, true);
    private final int maxEntries;
    private final long maxBytes;
    private long hits, misses, evictions, bytes;

    SourceCache(int maxEntries, long maxBytes) { this.maxEntries = maxEntries; this.maxBytes = maxBytes; }

    synchronized Source get(String name, String code, URI uri, boolean module) {
        Key key = new Key(name, uri, module, code);
        Entry found = entries.get(key);
        if (found != null) { hits++; return found.source(); }
        misses++;
        int size = code.getBytes(StandardCharsets.UTF_8).length;
        Source.Builder builder = Source.newBuilder("js", code, name).cached(true);
        if (uri != null) builder.uri(uri);
        if (module) builder.mimeType("application/javascript+module");
        Source source = builder.buildLiteral();
        if (size <= maxBytes) {
            entries.put(key, new Entry(source, size)); bytes += size;
            while (entries.size() > maxEntries || bytes > maxBytes) {
                var iterator = entries.entrySet().iterator();
                bytes -= iterator.next().getValue().bytes(); iterator.remove(); evictions++;
            }
        }
        return source;
    }

    synchronized Stats stats() { return new Stats(hits, misses, evictions, entries.size(), bytes); }
    synchronized void clear() { entries.clear(); bytes = 0; }
}
