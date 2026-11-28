package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.Optional;

/** Keeps the minute cutoff, provider retrieval times and actual published cohort together. */
final class JonggaPrecloseSnapshotStore {
    private static final Logger log = LoggerFactory.getLogger(JonggaPrecloseSnapshotStore.class);
    private static final int MAX_SNAPSHOT_BYTES = 512 * 1024;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private final Path directory;
    private final ObjectMapper mapper;

    JonggaPrecloseSnapshotStore(Path directory, ObjectMapper mapper) {
        this.directory = directory;
        this.mapper = mapper;
    }

    void write(JonggaPrecloseScanner.Snapshot snapshot) {
        Path temp = null;
        try {
            byte[] payload = mapper.writeValueAsBytes(snapshot);
            if (payload.length > MAX_SNAPSHOT_BYTES) throw new IOException("Market snapshot exceeds size limit");
            Path session = Files.createDirectories(directory.resolve(snapshot.date().toString()));
            Path destination = session.resolve(snapshot.publishedAt().toEpochMilli() + ".json");
            temp = Files.createTempFile(session, "scan-", ".tmp");
            Files.write(temp, payload);
            try {
                Files.move(temp, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            log.warn("[JonggaPreclose] snapshot write unavailable: {}", error.getClass().getSimpleName());
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException error) {
                    log.debug("[JonggaPreclose] temporary snapshot cleanup unavailable");
                }
            }
        }
    }

    Optional<JonggaPrecloseScanner.Snapshot> read(LocalDate day) {
        Path session = directory.resolve(day.toString());
        if (!Files.isDirectory(session)) return Optional.empty();
        try (var files = Files.list(session)) {
            return files.filter(path -> path.getFileName().toString().matches("[0-9]{13}\\.json"))
                .filter(Files::isRegularFile).sorted(Comparator.reverseOrder())
                .flatMap(path -> readOne(path, day).stream()).findFirst();
        } catch (IOException error) {
            log.warn("[JonggaPreclose] snapshot read unavailable: {}", error.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<JonggaPrecloseScanner.Snapshot> readOne(Path source, LocalDate day) {
        try {
            if (Files.size(source) > MAX_SNAPSHOT_BYTES) return Optional.empty();
            var value = mapper.readValue(source.toFile(), JonggaPrecloseScanner.Snapshot.class);
            if (value.schemaVersion() != JonggaPrecloseScanner.SCHEMA_VERSION
                || !JonggaPrecloseScanner.STRATEGY_VERSION.equals(value.strategy())
                || !day.equals(value.date()) || value.minuteCutoff() == null || value.publishedAt() == null
                || value.minuteCutoff().isAfter(value.publishedAt())
                || !JonggaPrecloseScanner.isPrecloseStatus(value.status())
                || value.signals() == null || value.signals().size() > 3) return Optional.empty();
            var publication = value.publishedAt().atZone(KST);
            if (!day.equals(publication.toLocalDate()) || !publication.toLocalTime().isBefore(LocalTime.of(15, 20))) {
                return Optional.empty();
            }
            return Optional.of(value);
        } catch (IOException | RuntimeException invalid) {
            log.debug("[JonggaPreclose] invalid snapshot skipped");
            return Optional.empty();
        }
    }
}
