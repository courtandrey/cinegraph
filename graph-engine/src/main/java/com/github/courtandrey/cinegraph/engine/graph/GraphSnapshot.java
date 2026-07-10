package com.github.courtandrey.cinegraph.engine.graph;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static java.nio.channels.FileChannel.MapMode.READ_ONLY;
import static java.nio.channels.FileChannel.MapMode.READ_WRITE;
import static java.nio.file.StandardCopyOption.ATOMIC_MOVE;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;

/**
 * Binary CSR snapshot of the movie graph. The per-edge sections (neighbor index and edge
 * score per directed edge, by far the dominant part) are memory-mapped rather than loaded,
 * so the graph is served from the OS page cache instead of the Java heap; only the per-node
 * arrays are materialized. A DB rebuild scatter-fills the edge sections directly into the
 * mapped tmp file via {@link Builder}, so peak heap stays independent of edge count.
 * The header carries a flags word reserved for optional future sections.
 */
public final class GraphSnapshot {

    private static final Logger log = LoggerFactory.getLogger(GraphSnapshot.class);

    private static final int MAGIC = 0x43475348;
    private static final int VERSION = 2;
    private static final int FLAGS_NONE = 0;
    private static final int HEADER_BYTES = 4 * Integer.BYTES;

    private GraphSnapshot() {}

    public static Optional<ImmutableGraph> read(Path path) {
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (FileChannel ch = FileChannel.open(path, READ)) {
            ByteBuffer header = readFully(ch, ByteBuffer.allocate(HEADER_BYTES));
            if (header.getInt() != MAGIC || header.getInt() != VERSION) {
                log.warn("[engine] snapshot {} has an unrecognized header, ignoring", path);
                return Optional.empty();
            }
            int n = header.getInt();
            header.getInt();
            long idsBytes = 8L * n;
            long offBytes = 4L * (n + 1);

            MappedByteBuffer meta = ch.map(READ_ONLY, HEADER_BYTES, idsBytes + offBytes);
            long[] ids = new long[n];
            meta.slice(0, (int) idsBytes).asLongBuffer().get(ids);
            int[] offsets = new int[n + 1];
            meta.slice((int) idsBytes, (int) offBytes).asIntBuffer().get(offsets);

            long nbPos = HEADER_BYTES + idsBytes + offBytes;
            long nbBytes = 4L * offsets[n];
            long scPos = nbPos + nbBytes;
            long scBytes = 4L * offsets[n];
            if (ch.size() < scPos + scBytes) {
                log.warn("[engine] snapshot {} is truncated, ignoring", path);
                return Optional.empty();
            }
            IntBuffer neighbors = ch.map(READ_ONLY, nbPos, nbBytes).asIntBuffer();
            FloatBuffer scores = ch.map(READ_ONLY, scPos, scBytes).asFloatBuffer();
            return Optional.of(new ImmutableGraph(ids, offsets, neighbors, scores));
        } catch (Exception e) {
            log.warn("[engine] failed to read snapshot {}: {}", path, e.getMessage());
            return Optional.empty();
        }
    }

    public static Builder builder(Path target, long[] ids, int[] offsets) throws IOException {
        return new Builder(target, ids, offsets);
    }

    public static void delete(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("[engine] failed to delete snapshot {}: {}", path, e.getMessage());
        }
    }

    public static final class Builder {

        private final Path target;
        private final Path tmp;
        private final FileChannel ch;
        private final MappedByteBuffer meta;
        private final MappedByteBuffer nbb;
        private final MappedByteBuffer scb;
        private final IntBuffer neighbors;
        private final FloatBuffer scores;

        private Builder(Path target, long[] ids, int[] offsets) throws IOException {
            this.target = target;
            Path parent = target.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            this.tmp = target.resolveSibling(target.getFileName() + ".tmp");

            int n = ids.length;
            long idsBytes = 8L * n;
            long offBytes = 4L * (n + 1);
            long metaBytes = HEADER_BYTES + idsBytes + offBytes;
            long nbBytes = 4L * offsets[n];
            long scBytes = 4L * offsets[n];

            this.ch = FileChannel.open(tmp, CREATE, READ, WRITE, TRUNCATE_EXISTING);
            this.meta = ch.map(READ_WRITE, 0, metaBytes);
            meta.putInt(MAGIC).putInt(VERSION).putInt(n).putInt(FLAGS_NONE);
            meta.slice(HEADER_BYTES, (int) idsBytes).asLongBuffer().put(ids);
            meta.slice((int) (HEADER_BYTES + idsBytes), (int) offBytes).asIntBuffer().put(offsets);
            this.nbb = ch.map(READ_WRITE, metaBytes, nbBytes);
            this.neighbors = nbb.asIntBuffer();
            this.scb = ch.map(READ_WRITE, metaBytes + nbBytes, scBytes);
            this.scores = scb.asFloatBuffer();
        }

        public IntBuffer neighbors() {
            return neighbors;
        }

        public FloatBuffer scores() {
            return scores;
        }

        public void commit() throws IOException {
            meta.force();
            nbb.force();
            scb.force();
            ch.close();
            Files.move(tmp, target, REPLACE_EXISTING, ATOMIC_MOVE);
        }

        public void abort() {
            try {
                ch.close();
                Files.deleteIfExists(tmp);
            } catch (IOException e) {
                log.warn("[engine] failed to clean up snapshot tmp {}: {}", tmp, e.getMessage());
            }
        }
    }

    private static ByteBuffer readFully(FileChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) throw new EOFException();
        }
        return buf.flip();
    }
}
