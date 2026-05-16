package com.bitman.justbuy.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * H2 DB 자동 백업 — H2 native 온라인 BACKUP TO 사용 (라이브 락 우회).
 *
 * 매일 03:00 KST 실행. 30일 retention.
 * 출력: {projectRoot}/backups/justbuy-db-YYYYMMDD-HHmmss.zip
 *
 * v1.0 (2026-04-26) — Stage 1 Critical fix: 백업 0건 상태 해소.
 *
 * 비고: 분석 캐시 JSON·.env·.jwt-secret 백업은 별도 PowerShell 스크립트(scripts/backup-h2.ps1)가 담당.
 */
@Component
@ConditionalOnProperty(name = "bitman.backup.enabled", havingValue = "true", matchIfMissing = true)
public class BackupService {

    private static final Logger log = LoggerFactory.getLogger(BackupService.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int RETENTION_DAYS = 30;

    private final DataSource dataSource;
    private final Path backupDir;

    public BackupService(DataSource dataSource,
                         @Value("${bitman.backup.dir:./backups}") String backupDir) {
        this.dataSource = dataSource;
        this.backupDir = Path.of(backupDir).toAbsolutePath();
    }

    /** 매일 03:00 KST — 트래픽 가장 적은 시간대 */
    @Scheduled(cron = "0 0 3 * * *", zone = "Asia/Seoul")
    public void scheduledBackup() {
        try {
            Path zip = runBackup();
            log.info("[Backup] ✅ 자동 백업 완료: {} ({} KB)",
                zip.getFileName(), Files.size(zip) / 1024);
            cleanupOldBackups();
        } catch (Exception e) {
            log.error("[Backup] ❌ 자동 백업 실패: {}", e.getMessage(), e);
        }
    }

    /**
     * H2 BACKUP TO 'path.zip' 실행. 라이브 트랜잭션 중에도 안전 (consistent snapshot).
     *
     * @return 생성된 zip 파일 경로
     */
    public Path runBackup() throws Exception {
        Files.createDirectories(backupDir);

        String stamp = LocalDateTime.now().format(STAMP);
        Path zipPath = backupDir.resolve("justbuy-db-" + stamp + ".zip");

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            // H2 SQL: BACKUP TO 'file.zip' — atomic snapshot of entire DB
            // 작은 따옴표 escape: H2가 파일명을 SQL string으로 받음
            String escaped = zipPath.toString().replace("\\", "\\\\").replace("'", "''");
            stmt.execute("BACKUP TO '" + escaped + "'");
        }

        return zipPath;
    }

    /** 30일 초과 백업 삭제 */
    private void cleanupOldBackups() {
        if (!Files.exists(backupDir)) return;

        LocalDate cutoff = LocalDate.now().minusDays(RETENTION_DAYS);
        int[] deleted = {0};

        try (Stream<Path> files = Files.list(backupDir)) {
            files
                .filter(p -> p.getFileName().toString().startsWith("justbuy-db-"))
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .filter(p -> {
                    try {
                        return Files.getLastModifiedTime(p).toInstant()
                            .isBefore(cutoff.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
                    } catch (IOException e) {
                        return false;
                    }
                })
                .forEach(p -> {
                    try {
                        Files.delete(p);
                        deleted[0]++;
                    } catch (IOException e) {
                        log.warn("[Backup] 오래된 백업 삭제 실패: {}", p);
                    }
                });
        } catch (IOException e) {
            log.warn("[Backup] retention 정리 실패: {}", e.getMessage());
        }

        if (deleted[0] > 0) {
            log.info("[Backup] 오래된 백업 {}건 삭제 ({}일 초과)", deleted[0], RETENTION_DAYS);
        }
    }
}
