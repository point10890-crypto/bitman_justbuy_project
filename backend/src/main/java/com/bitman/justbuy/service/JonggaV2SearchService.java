package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class JonggaV2SearchService {

    private static final String LATEST_FILE = "jongga_v2_latest.json";
    private static final String RESULT_PREFIX = "jongga_v2_results_";
    private static final String RESULT_SUFFIX = ".json";
    private static final int MIN_DATA_FILE_BYTES = 500;
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final ObjectMapper mapper;
    private final List<Path> dataDirs;

    @Autowired
    public JonggaV2SearchService(ObjectMapper mapper,
                                 @Value("${bitman.storage.data-dir:./data}") String dataDir,
                                 @Value("${bitman.jongga-v2.extra-data-dirs:C:/bitman_marketfloww/data}") String extraDataDirs) {
        this.mapper = mapper;
        this.dataDirs = buildDataDirs(dataDir, extraDataDirs);
    }

    /**
     * 테스트 전용. 주어진 디렉토리 하나만 본다.
     * 프로덕션 생성자와 달리 {@code ../data} 등 폴백 경로를 붙이지 않으므로,
     * 테스트가 실행 환경의 실제 데이터 디렉토리에 영향받지 않는다.
     */
    JonggaV2SearchService(ObjectMapper mapper, String dataDir) {
        this.mapper = mapper;
        this.dataDirs = List.of(Path.of(dataDir).toAbsolutePath().normalize());
    }

    public JsonNode latest() {
        Path latest = firstExisting(LATEST_FILE, "kr-jongga-v2-latest.json");
        if (latest == null) {
            latest = latestHistoryFile();
        }
        if (latest == null) {
            ObjectNode empty = mapper.createObjectNode();
            empty.put("date", LocalDate.now().toString());
            empty.put("total_candidates", 0);
            empty.put("filtered_count", 0);
            empty.set("signals", mapper.createArrayNode());
            empty.put("message", "No data available");
            return empty;
        }
        return readJson(latest);
    }

    public ObjectNode dates() {
        ArrayNode dates = mapper.createArrayNode();
        availableDates().forEach(dates::add);

        ObjectNode result = mapper.createObjectNode();
        result.set("dates", dates);
        result.put("count", dates.size());
        return result;
    }

    public JsonNode history(String date) {
        String cleanDate = normalizeDate(date);
        Path file = firstExisting(RESULT_PREFIX + cleanDate + RESULT_SUFFIX);
        if (file == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Data not found for " + date);
        }
        return readJson(file);
    }

    public ObjectNode search(String query, String date, String grade, Integer limit) {
        String normalizedQuery = normalize(query);
        String normalizedGrade = normalizeGrade(grade);
        int cappedLimit = capLimit(limit);
        JsonNode source = date == null || date.isBlank() ? latest() : history(date);
        ArrayNode matches = mapper.createArrayNode();

        for (JsonNode signal : source.path("signals")) {
            if (!matchesQuery(signal, normalizedQuery)) continue;
            if (!matchesGrade(signal, normalizedGrade)) continue;
            matches.add(signal);
            if (matches.size() >= cappedLimit) break;
        }

        ObjectNode result = mapper.createObjectNode();
        result.put("date", source.path("date").asText(date == null ? "" : date));
        result.put("query", query == null ? "" : query);
        if (normalizedGrade != null) result.put("grade", normalizedGrade);
        result.put("total_matches", matches.size());
        result.put("limit", cappedLimit);
        result.set("signals", matches);
        return result;
    }

    private boolean matchesQuery(JsonNode signal, String query) {
        if (query == null || query.isBlank()) return true;
        return contains(signal.path("stock_code"), query)
            || contains(signal.path("stock_name"), query)
            || contains(signal.path("market"), query)
            || arrayContains(signal.path("themes"), query)
            || newsContains(signal.path("news_items"), query);
    }

    private boolean matchesGrade(JsonNode signal, String grade) {
        if (grade == null || grade.isBlank()) return true;
        return grade.equalsIgnoreCase(signal.path("grade").asText(""));
    }

    private boolean contains(JsonNode node, String query) {
        return normalize(node.asText("")).contains(query);
    }

    private boolean arrayContains(JsonNode values, String query) {
        if (!values.isArray()) return false;
        for (JsonNode value : values) {
            if (contains(value, query)) return true;
        }
        return false;
    }

    private boolean newsContains(JsonNode newsItems, String query) {
        if (!newsItems.isArray()) return false;
        for (JsonNode news : newsItems) {
            if (contains(news.path("title"), query) || contains(news.path("source"), query)) {
                return true;
            }
        }
        return false;
    }

    private List<String> availableDates() {
        Set<String> dates = new LinkedHashSet<>();
        for (Path dir : dataDirs) {
            Path resolved = dir.toAbsolutePath().normalize();
            if (!Files.isDirectory(resolved)) continue;

            Path snapshotDates = resolved.resolve("kr-jongga-v2-dates.json");
            if (Files.exists(snapshotDates)) {
                JsonNode snapshot = readJson(snapshotDates);
                snapshot.path("dates").forEach(date -> dates.add(date.asText()));
            }

            try (Stream<Path> files = Files.list(resolved)) {
                files
                    .filter(Files::isRegularFile)
                    .filter(this::isUsableHistoryFile)
                    .map(path -> path.getFileName().toString())
                    .map(this::dateFromHistoryFilename)
                    .filter(value -> value != null && !value.isBlank())
                    .forEach(dates::add);
            } catch (IOException ignored) {
                // Ignore unreadable fallback directories and keep checking others.
            }
        }

        return dates.stream()
            .sorted(Comparator.reverseOrder())
            .toList();
    }

    private Path latestHistoryFile() {
        return availableDates().stream()
            .map(date -> firstExisting(RESULT_PREFIX + normalizeDate(date) + RESULT_SUFFIX))
            .filter(path -> path != null)
            .findFirst()
            .orElse(null);
    }

    private Path firstExisting(String... fileNames) {
        for (Path dir : dataDirs) {
            Path resolvedDir = dir.toAbsolutePath().normalize();
            for (String fileName : fileNames) {
                Path candidate = resolvedDir.resolve(fileName).normalize();
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private JsonNode readJson(Path path) {
        try {
            return mapper.readTree(path.toFile());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                "Failed to read Jongga V2 data: " + path.getFileName(), e);
        }
    }

    private boolean isUsableHistoryFile(Path path) {
        String name = path.getFileName().toString();
        if (!name.startsWith(RESULT_PREFIX) || !name.endsWith(RESULT_SUFFIX)) return false;
        try {
            return Files.size(path) >= MIN_DATA_FILE_BYTES;
        } catch (IOException ignored) {
            return false;
        }
    }

    private String dateFromHistoryFilename(String filename) {
        if (!filename.startsWith(RESULT_PREFIX) || !filename.endsWith(RESULT_SUFFIX)) return null;
        String date = filename.substring(RESULT_PREFIX.length(), filename.length() - RESULT_SUFFIX.length());
        return date.matches("\\d{8}") ? date : null;
    }

    private static String normalizeDate(String date) {
        String cleanDate = date == null ? "" : date.replace("-", "").trim();
        if (!cleanDate.matches("\\d{8}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "date must be YYYYMMDD or YYYY-MM-DD");
        }
        return cleanDate;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeGrade(String grade) {
        if (grade == null || grade.isBlank()) return null;
        return grade.trim().toUpperCase(Locale.ROOT);
    }

    private static int capLimit(Integer limit) {
        if (limit == null || limit <= 0) return DEFAULT_LIMIT;
        return Math.min(limit, MAX_LIMIT);
    }

    private static List<Path> buildDataDirs(String dataDir, String extraDataDirs) {
        List<Path> candidates = new ArrayList<>();
        addExtraDataDirs(candidates, extraDataDirs);
        candidates.add(Path.of(dataDir));
        candidates.add(Path.of("..", "data"));
        candidates.add(Path.of("data"));
        candidates.add(Path.of("..", "frontend", "data-snapshot"));
        candidates.add(Path.of("frontend", "data-snapshot"));

        LinkedHashSet<Path> unique = new LinkedHashSet<>();
        for (Path candidate : candidates) {
            if (candidate == null) continue;
            unique.add(candidate.toAbsolutePath().normalize());
        }
        return List.copyOf(unique);
    }

    private static void addExtraDataDirs(List<Path> candidates, String extraDataDirs) {
        if (extraDataDirs == null || extraDataDirs.isBlank()) return;
        for (String value : extraDataDirs.split("[,;]")) {
            String trimmed = value.trim();
            if (!trimmed.isBlank()) {
                candidates.add(Path.of(trimmed));
            }
        }
    }
}
