package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JonggaV2SearchServiceTest {

    @TempDir
    Path dataDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void latestAndSearchReadJonggaV2DataFromConfiguredDataDir() throws Exception {
        writeJson("jongga_v2_latest.json", """
            {
              "date": "2026-05-29",
              "total_candidates": 2,
              "filtered_count": 2,
              "signals": [
                {
                  "stock_code": "005930",
                  "stock_name": "삼성전자",
                  "market": "KOSPI",
                  "grade": "S",
                  "themes": ["반도체"],
                  "news_items": [{"title": "반도체 수급 개선", "source": "테스트"}],
                  "entry_price": 80000,
                  "target_price": 84000
                },
                {
                  "stock_code": "000660",
                  "stock_name": "SK하이닉스",
                  "market": "KOSPI",
                  "grade": "A",
                  "themes": ["메모리"],
                  "news_items": [{"title": "메모리 업황 회복", "source": "테스트"}],
                  "entry_price": 180000,
                  "target_price": 189000
                }
              ]
            }
            """);

        JonggaV2SearchService service = new JonggaV2SearchService(mapper, dataDir.toString());

        assertThat(service.latest().path("filtered_count").asInt()).isEqualTo(2);

        ObjectNode search = service.search("반도체", null, "S", 10);

        assertThat(search.path("total_matches").asInt()).isEqualTo(1);
        assertThat(search.path("signals").get(0).path("stock_code").asText()).isEqualTo("005930");
    }

    @Test
    void datesIncludeSnapshotDatesAndHistoryFilesNewestFirst() throws Exception {
        writeJson("kr-jongga-v2-dates.json", """
            { "dates": ["20260520", "20260524"], "count": 2 }
            """);
        writeJson("jongga_v2_results_20260529.json", historyPayload("2026-05-29"));
        writeJson("jongga_v2_results_20260528.json", historyPayload("2026-05-28"));

        JonggaV2SearchService service = new JonggaV2SearchService(mapper, dataDir.toString());

        ObjectNode dates = service.dates();
        List<String> dateValues = new ArrayList<>();
        dates.path("dates").forEach(node -> dateValues.add(node.asText()));

        assertThat(dateValues).startsWith("20260529", "20260528", "20260524", "20260520");
    }

    private void writeJson(String fileName, String content) throws Exception {
        Files.writeString(dataDir.resolve(fileName), content);
    }

    private String historyPayload(String date) {
        return """
            {
              "date": "%s",
              "total_candidates": 1,
              "filtered_count": 1,
              "signals": [
                {
                  "stock_code": "005930",
                  "stock_name": "삼성전자",
                  "market": "KOSPI",
                  "grade": "S",
                  "themes": ["반도체"],
                  "news_items": [{"title": "반도체 수급 개선", "source": "테스트"}],
                  "entry_price": 80000,
                  "target_price": 84000,
                  "score": {
                    "total": 12,
                    "llm_reason": "테스트 데이터가 최소 파일 크기 필터를 통과하도록 충분히 긴 설명을 넣습니다. 종가매매 후보의 뉴스, 수급, 차트, 리스크를 함께 검증하는 저장 결과입니다."
                  }
                }
              ]
            }
            """.formatted(date);
    }
}
