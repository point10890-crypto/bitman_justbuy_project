package com.bitman.justbuy.service;

import com.bitman.justbuy.dto.AnalysisResponse;
import com.bitman.justbuy.dto.StockPick;
import com.bitman.justbuy.dto.condition.ConditionSignalDto;
import com.bitman.justbuy.dto.condition.MainConditionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 미구독자 미리보기 — <b>서버에서</b> 가려지는지 검증한다.
 *
 * <p>클라이언트에서만 마스킹하면 응답 본문에 원본이 남아 개발자도구로 그대로 읽힌다.
 * 즉 페이월이 아니라 눈속임이 된다. 그래서 응답 자체에 원본이 없어야 한다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MainConditionPreviewTest {

    @Mock ConditionSearchPipeline pipeline;

    private MainConditionService service() {
        when(pipeline.getPrecomputed("BREAKOUT")).thenReturn(response("BREAKOUT",
            new StockPick("삼성전자", "005930", "78,100", "82,000", "75,000", "주목", "단타 후보")));
        when(pipeline.getPrecomputed("REVERSAL_EDGE")).thenReturn(response("REVERSAL_EDGE"));
        when(pipeline.getPrecomputed("FLOW_LEADER")).thenReturn(response("FLOW_LEADER"));
        when(pipeline.getPrecomputed("CATALYST_BURST")).thenReturn(response("CATALYST_BURST"));
        return new MainConditionService(pipeline);
    }

    @Test
    void subscriberResponseIsNotMarkedAsPreview() {
        MainConditionResponse full = service().getMain();

        assertThat(full.preview()).isFalse();
        assertThat(full.tier()).isEqualTo("ACTIVE");
        assertThat(full.sections().shortTerm().signals())
            .extracting(ConditionSignalDto::stockName)
            .contains("삼성전자");
    }

    @Test
    void previewHidesStockIdentityAndPrices() {
        MainConditionResponse preview = service().getMainPreview("NONE");

        assertThat(preview.preview()).isTrue();
        assertThat(preview.tier()).isEqualTo("NONE");

        ConditionSignalDto row = preview.sections().shortTerm().signals().get(0);
        assertThat(row.stockName()).isEqualTo("삼○○○");
        assertThat(row.stockName()).doesNotContain("성전자");
        assertThat(row.stockCode()).isEmpty();
        assertThat(row.capturePrice()).doesNotContain("78");
        assertThat(row.currentPrice()).doesNotContain("78");
    }

    @Test
    void previewKeepsValueSignalsThatDriveConversion() {
        MainConditionResponse preview = service().getMainPreview("NONE");
        ConditionSignalDto row = preview.sections().shortTerm().signals().get(0);

        // 구성·순위·포착시각·성과 요약은 남겨야 "살 만한 서비스"인지 판단할 수 있다.
        assertThat(preview.sections().shortTerm().title()).isEqualTo("단타");
        assertThat(row.rank()).isEqualTo(1);
        assertThat(row.capturedAt()).isNotBlank();
        assertThat(preview.trackRecord()).isNotNull();
        assertThat(row.riskFlags()).isNotEmpty();
    }

    @Test
    void previewResponseBodyContainsNoOriginalStockIdentifiers() {
        // 전체 응답을 훑어 원본 종목명/코드가 어디에도 남지 않았는지 확인
        String rendered = service().getMainPreview("NONE").toString();

        assertThat(rendered).doesNotContain("삼성전자");
        assertThat(rendered).doesNotContain("005930");
        assertThat(rendered).doesNotContain("78,100");
    }

    private static AnalysisResponse response(String mode, StockPick... picks) {
        return new AnalysisResponse(
            mode, mode + " query", List.of(), null, mode + " content",
            List.of(picks), null, "2026-07-28T09:10:00+09:00", true,
            new AnalysisResponse.Metadata(100, 1, 1));
    }
}
