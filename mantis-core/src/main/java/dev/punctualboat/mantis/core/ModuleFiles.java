package dev.punctualboat.mantis.core;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileTime;
import java.util.*;

final class ModuleFiles implements FileSystem {
    static final Path ROOT = Path.of("/mantis").toAbsolutePath().normalize();
    private final Map<Path, byte[]> files = new HashMap<>();

    ModuleFiles(Map<String, String> scripts, Map<String, Map<String, Object>> modules) {
        scripts.forEach((name, text) -> put(scriptPath(name), text));
        modules.forEach((name, bindings) -> {
            StringBuilder text = new StringBuilder();
            bindings.forEach((binding, value) -> {
                if (!binding.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                    throw new IllegalArgumentException("Invalid module export: " + binding);
                }
                text.append("export const ").append(binding).append(" = globalThis.__mantis_bindings['")
                        .append(name).append("']['").append(binding).append("'];\n");
            });
            put(modulePath(name), text.toString());
        });
    }

    static Path scriptPath(String name) {
        Path path = ROOT.resolve("scripts").resolve(name).normalize();
        if (!path.startsWith(ROOT.resolve("scripts")) || path.equals(ROOT.resolve("scripts"))) {
            throw new IllegalArgumentException("Script path escapes its source root: " + name);
        }
        return path;
    }

    static Path modulePath(String name) {
        if (!name.matches("[a-z][a-z0-9_.-]*:[a-zA-Z0-9/_-]+") || name.contains("..")) {
            throw new IllegalArgumentException("Invalid host module: " + name);
        }
        return ROOT.resolve("modules").resolve(name.replace(':', '/')).resolveSibling(
                Path.of(name.replace(':', '/')).getFileName() + ".mjs").normalize();
    }

    private void put(Path path, String source) { files.put(path, source.getBytes(StandardCharsets.UTF_8)); }

    @Override public Path parsePath(URI uri) {
        if ("file".equals(uri.getScheme())) return Path.of(uri);
        return modulePath(uri.toString());
    }

    @Override public Path parsePath(String path) {
        return path.matches("[a-z][a-z0-9_.-]*:.*") ? modulePath(path) : Path.of(path);
    }

    private byte[] content(Path path) throws NoSuchFileException {
        byte[] bytes = files.get(toAbsolutePath(path));
        if (bytes == null) throw new NoSuchFileException(path.toString());
        return bytes;
    }

    @Override public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options) throws IOException {
        if (modes.stream().anyMatch(mode -> mode != AccessMode.READ)) throw new AccessDeniedException(path.toString());
        content(path);
    }

    @Override public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException { throw new AccessDeniedException(dir.toString()); }
    @Override public void delete(Path path) throws IOException { throw new AccessDeniedException(path.toString()); }

    @Override public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
        if (options.stream().anyMatch(option -> option != StandardOpenOption.READ)) throw new AccessDeniedException(path.toString());
        return new ReadChannel(content(path));
    }

    @Override public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException {
        throw new AccessDeniedException(dir.toString());
    }

    @Override public Path toAbsolutePath(Path path) { return (path.isAbsolute() ? path : ROOT.resolve("scripts").resolve(path)).normalize(); }
    @Override public Path toRealPath(Path path, LinkOption... options) throws IOException { content(path); return toAbsolutePath(path); }

    @Override public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        byte[] bytes = content(path);
        return Map.of("isRegularFile", true, "isDirectory", false, "isSymbolicLink", false,
                "isOther", false, "size", (long) bytes.length, "lastModifiedTime", FileTime.fromMillis(0),
                "lastAccessTime", FileTime.fromMillis(0), "creationTime", FileTime.fromMillis(0), "fileKey", toAbsolutePath(path));
    }

    private static final class ReadChannel implements SeekableByteChannel {
        private final ByteBuffer bytes;
        private boolean open = true;
        ReadChannel(byte[] bytes) { this.bytes = ByteBuffer.wrap(bytes).asReadOnlyBuffer(); }
        private void checkOpen() throws ClosedChannelException { if (!open) throw new ClosedChannelException(); }
        @Override public int read(ByteBuffer target) throws IOException {
            checkOpen();
            if (!target.hasRemaining()) return 0;
            if (!bytes.hasRemaining()) return -1;
            int length = Math.min(target.remaining(), bytes.remaining());
            ByteBuffer slice = bytes.slice();
            slice.limit(length);
            target.put(slice);
            bytes.position(bytes.position() + length);
            return length;
        }
        @Override public int write(ByteBuffer src) { throw new UnsupportedOperationException("Read only"); }
        @Override public long position() throws IOException { checkOpen(); return bytes.position(); }
        @Override public SeekableByteChannel position(long position) throws IOException { checkOpen(); bytes.position(Math.toIntExact(position)); return this; }
        @Override public long size() throws IOException { checkOpen(); return bytes.limit(); }
        @Override public SeekableByteChannel truncate(long size) { throw new UnsupportedOperationException("Read only"); }
        @Override public boolean isOpen() { return open; }
        @Override public void close() { open = false; }
    }
}
