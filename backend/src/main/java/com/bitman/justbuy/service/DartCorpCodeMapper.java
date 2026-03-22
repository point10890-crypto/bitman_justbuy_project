package com.bitman.justbuy.service;

import com.bitman.justbuy.config.DartProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipInputStream;

/**
 * DART corp_code(8자리) ↔ 종목코드(6자리) 매핑 서비스.
 * DART API는 corp_code를 사용하므로 종목코드→corp_code 변환 필요.
 */
@Service
public class DartCorpCodeMapper {

    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeMapper.class);
    private static final String DART_CORPCODE_URL = "https://opendart.fss.or.kr/api/corpCode.xml";

    private final DartProperties dartProperties;
    private final ObjectMapper objectMapper;
    private final Path cacheFilePath;

    private final Map<String, String> stockToCorpCode = new ConcurrentHashMap<>();
    private volatile Instant lastLoaded = Instant.EPOCH;

    public DartCorpCodeMapper(DartProperties dartProperties, ObjectMapper objectMapper,
                               @Value("${bitman.storage.data-dir:./data}") String dataDir) {
        this.dartProperties = dartProperties;
        this.objectMapper = objectMapper;
        this.cacheFilePath = Path.of(dataDir, "dart_corp_codes.json");
    }

    /**
     * 6자리 종목코드 → 8자리 DART corp_code 변환.
     * 매핑이 없으면 null 반환.
     */
    public String getCorpCode(String stockCode) {
        ensureLoaded();
        return stockToCorpCode.get(stockCode);
    }

    public boolean isReady() {
        ensureLoaded();
        return !stockToCorpCode.isEmpty();
    }

    private void ensureLoaded() {
        if (!stockToCorpCode.isEmpty() && lastLoaded.isAfter(Instant.now().minus(7, ChronoUnit.DAYS))) {
            return; // 메모리 캐시 유효
        }

        synchronized (this) {
            if (!stockToCorpCode.isEmpty() && lastLoaded.isAfter(Instant.now().minus(7, ChronoUnit.DAYS))) {
                return;
            }

            // 1) 디스크 캐시에서 로드 시도
            if (loadFromDisk()) {
                return;
            }

            // 2) DART API에서 다운로드
            downloadFromDart();
        }
    }

    private boolean loadFromDisk() {
        try {
            if (!Files.exists(cacheFilePath)) return false;

            Instant fileTime = Files.getLastModifiedTime(cacheFilePath).toInstant();
            if (fileTime.isBefore(Instant.now().minus(7, ChronoUnit.DAYS))) {
                log.info("[DartCorpCode] 디스크 캐시 만료 (7일 초과)");
                return false;
            }

            Map<String, String> loaded = objectMapper.readValue(
                cacheFilePath.toFile(), new TypeReference<Map<String, String>>() {});
            stockToCorpCode.putAll(loaded);
            lastLoaded = Instant.now();
            log.info("[DartCorpCode] 디스크 캐시 로드 완료: {}개 종목", loaded.size());
            return true;
        } catch (Exception e) {
            log.warn("[DartCorpCode] 디스크 캐시 로드 실패: {}", e.getMessage());
            return false;
        }
    }

    private void downloadFromDart() {
        if (dartProperties.apiKey() == null || dartProperties.apiKey().isBlank()) {
            log.warn("[DartCorpCode] DART API 키 미설정");
            return;
        }

        try {
            String url = DART_CORPCODE_URL + "?crtfc_key=" + dartProperties.apiKey();
            log.info("[DartCorpCode] DART에서 corp_code 매핑 다운로드 중...");

            HttpClient client = HttpClient.newBuilder().build();
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                log.error("[DartCorpCode] DART 응답 오류: {}", response.statusCode());
                return;
            }

            // ZIP 해제 → XML 파싱
            Map<String, String> mapping = parseZipXml(response.body());
            if (mapping.isEmpty()) {
                log.error("[DartCorpCode] XML 파싱 결과 비어있음");
                return;
            }

            stockToCorpCode.clear();
            stockToCorpCode.putAll(mapping);
            lastLoaded = Instant.now();

            // 디스크에 저장
            saveToDisk(mapping);

            log.info("[DartCorpCode] DART corp_code 매핑 완료: {}개 종목", mapping.size());
        } catch (Exception e) {
            log.error("[DartCorpCode] DART 다운로드 실패: {}", e.getMessage());
        }
    }

    private Map<String, String> parseZipXml(byte[] zipData) {
        Map<String, String> mapping = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipData))) {
            var entry = zis.getNextEntry();
            if (entry == null) return mapping;

            byte[] xmlBytes = zis.readAllBytes();
            var factory = DocumentBuilderFactory.newInstance();
            var builder = factory.newDocumentBuilder();
            var doc = builder.parse(new ByteArrayInputStream(xmlBytes));
            var lists = doc.getElementsByTagName("list");

            for (int i = 0; i < lists.getLength(); i++) {
                var node = lists.item(i);
                String corpCode = "";
                String stockCode = "";

                var children = node.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    var child = children.item(j);
                    if ("corp_code".equals(child.getNodeName())) {
                        corpCode = child.getTextContent().trim();
                    } else if ("stock_code".equals(child.getNodeName())) {
                        stockCode = child.getTextContent().trim();
                    }
                }

                // 상장 기업만 (stock_code가 비어있지 않은 것)
                if (!stockCode.isBlank() && stockCode.length() == 6 && !corpCode.isBlank()) {
                    mapping.put(stockCode, corpCode);
                }
            }
        } catch (Exception e) {
            log.error("[DartCorpCode] ZIP/XML 파싱 실패: {}", e.getMessage());
        }
        return mapping;
    }

    private void saveToDisk(Map<String, String> mapping) {
        try {
            Files.createDirectories(cacheFilePath.getParent());
            objectMapper.writeValue(cacheFilePath.toFile(), mapping);
            log.info("[DartCorpCode] 디스크 캐시 저장: {}", cacheFilePath);
        } catch (Exception e) {
            log.warn("[DartCorpCode] 디스크 캐시 저장 실패: {}", e.getMessage());
        }
    }
}
