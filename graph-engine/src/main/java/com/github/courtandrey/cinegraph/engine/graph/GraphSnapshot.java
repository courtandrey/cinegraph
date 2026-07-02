package com.github.courtandrey.cinegraph.engine.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

public final class GraphSnapshot {

    private static final Logger log = LoggerFactory.getLogger(GraphSnapshot.class);

    private static final int MAGIC = 0x43475348;
    private static final int VERSION = 1;
    private static final int CHUNK = 1 << 20;

    private GraphSnapshot() {}

    public static Optional<ImmutableGraph> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (FileChannel ch = FileChannel.open(path, READ)) {
            ByteBuffer header = readFully(ch, ByteBuffer.allocate(3 * Integer.BYTES));
            if (header.getInt() != MAGIC || header.getInt() != VERSION) {
                log.warn("[engine] snapshot {} has an unrecognized header, ignoring", path);
                return Optional.empty();
            }
            int n = header.getInt();
            long[] ids = new long[n];
            readLongs(ch, ids);
            int[] offsets = new int[n + 1];
            readInts(ch, offsets);
            int[] neighbors = new int[offsets[n]];
            readInts(ch, neighbors);
            return Optional.of(new ImmutableGraph(ids, offsets, neighbors));
        } catch (Exception e) {
            log.warn("[engine] failed to read snapshot {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public static void write(Path path, ImmutableGraph graph) throws IOException {
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
        long[] ids = graph.ids();
        try (FileChannel ch = FileChannel.open(tmp, CREATE, WRITE, TRUNCATE_EXISTING)) {
            ByteBuffer header = ByteBuffer.allocate(3 * Integer.BYTES)
                    .putInt(MAGIC).putInt(VERSION).putInt(ids.length).flip();
            writeFully(ch, header);
            writeLongs(ch, ids);
            writeInts(ch, graph.offsets());
            writeInts(ch, graph.neighbors());
        }
        Files.move(tmp, path, REPLACE_EXISTING, ATOMIC_MOVE);
    }

    public static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[engine] failed to delete snapshot {}: {}", path, e.getMessage());
        }
    }

    private static ByteBuffer readFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new EOFException();
        }
        return buf.flip();
    }

    private static void writeFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) ch.write(buf);
    }

    private static void writeLongs(FileChannel ch, long[] a) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
        int per = buf.capacity() / Long.BYTES;
        for (int i = 0; i < a.length; ) {
            buf.clear();
            int m = Math.min(a.length - i, per);
            for (int j = 0; j < m; j++) buf.putLong(a[i++]);
            writeFully(ch, buf.flip());
        }
    }

    private static void writeInts(FileChannel ch, int[] a) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
        int per = buf.capacity() / Integer.BYTES;
        for (int i = 0; i < a.length; ) {
            buf.clear();
            int m = Math.min(a.length - i, per);
            for (int j = 0; j < m; j++) buf.putInt(a[i++]);
            writeFully(ch, buf.flip());
        }
    }

    private static void readLongs(FileChannel ch, long[] a) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
        int per = buf.capacity() / Long.BYTES;
        for (int i = 0; i < a.length; ) {
            int m = Math.min(a.length - i, per);
            buf.clear().limit(m * Long.BYTES);
            readFully(ch, buf);
            for (int j = 0; j < m; j++) a[i++] = buf.getLong();
        }
    }

    private static void readInts(FileChannel ch, int[] a) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(CHUNK);
        int per = buf.capacity() / Integer.BYTES;
        for (int i = 0; i < a.length; ) {
            int m = Math.min(a.length - i, per);
            buf.clear().limit(m * Integer.BYTES);
            readFully(ch, buf);
            for (int j = 0; j < m; j++) a[i++] = buf.getInt();
        }
    }
}
