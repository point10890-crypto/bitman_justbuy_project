package com.bitman.justbuy.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class KiwoomSupplementPolicyTest {
    @Test void concurrentRequestsAlwaysIncludeAllGroupsForTheFirstThreeValidDistinctCodes() {
        var result=KiwoomSupplementPolicy.concurrentParts(List.of("bad","005930","005930","000660","035420","068270"));
        assertThat(result.keySet()).containsExactly("005930","000660","035420");
        assertThat(result.values()).allSatisfy(parts->assertThat(parts).containsExactly("quote","valuation","technical","investorDaily"));
        assertThat(KiwoomSupplementPolicy.concurrentParts(null)).isEmpty();
    }
    Map<String,Object> primary(Map<String,Object> states) { return Map.of("status","PARTIAL","sourceStates",states); }
    @Test void absentPrimaryKeepsAllGroupsMissingWithoutRequestingQuotes() {
        assertThat(KiwoomSupplementPolicy.missingParts(List.of("005930"),null))
            .containsEntry("005930",List.of("technical","valuation","investorDaily"));
        assertThat(KiwoomSupplementPolicy.missingParts(null,null)).isEmpty();
    }
    @Test void onlyFirstThreeValidDistinctNumericCodesAreConsidered() {
        var result=KiwoomSupplementPolicy.missingParts(List.of("ABCDEF","005930","005930","000660","035420","068270","12345","1234567"),Map.of());
        assertThat(result.keySet()).containsExactly("005930","000660","035420");
        assertThat(result.values()).allSatisfy(parts->assertThat(parts).containsExactly("technical","valuation","investorDaily"));
    }
    @Test void confirmedMainGroupsAreSkippedWhileUnavailableGroupsRemainMissing() {
        var result=KiwoomSupplementPolicy.missingParts(List.of("005930"),primary(Map.of("005930",Map.of("technical","AVAILABLE","valuation","PARTIAL_COVERAGE"))));
        assertThat(result).containsEntry("005930",List.of("valuation","investorDaily"));
    }
    @Test void undatedEstimateNeverSubstitutesForConfirmedDailyInvestorFlow() {
        var result=KiwoomSupplementPolicy.missingParts(List.of("005930"),primary(Map.of("005930",Map.of("technical","AVAILABLE","valuation","AVAILABLE","investorEstimate","ESTIMATE_UNDATED","investorDaily","AVAILABLE"))));
        assertThat(result).containsEntry("005930",List.of("investorDaily"));
    }
    @Test void codeWithAllThreeConfirmedGroupsIsExcludedFromSupplementRequests() {
        var result=KiwoomSupplementPolicy.missingParts(List.of("005930","000660"),primary(Map.of(
            "005930",Map.of("technical","AVAILABLE","valuation","AVAILABLE"),
            "005930Supplemental",Map.of("investorDaily","AVAILABLE"))));
        assertThat(result).doesNotContainKey("005930").containsEntry("000660",List.of("technical","valuation","investorDaily"));
    }
    @Test void disabledOrUnavailablePrimaryCannotReuseConflictingGroupAvailability() {
        for(String status:List.of("DISABLED","UNAVAILABLE")) {
            var metadata=Map.<String,Object>of("status",status,"sourceStates",Map.of("005930",Map.of("technical","AVAILABLE","valuation","AVAILABLE"),"005930Supplemental",Map.of("investorDaily","AVAILABLE")));
            assertThat(KiwoomSupplementPolicy.missingParts(List.of("005930"),metadata))
                .containsEntry("005930",List.of("technical","valuation","investorDaily"));
        }
        assertThat(KiwoomSupplementPolicy.missingParts(List.of("005930"),Map.of("sourceStates","malformed")))
            .containsEntry("005930",List.of("technical","valuation","investorDaily"));
        var malformed=new java.util.LinkedHashMap<String,Object>();
        malformed.put("status",null); malformed.put("sourceStates","malformed");
        assertThat(KiwoomSupplementPolicy.missingParts(List.of("005930"),malformed))
            .containsEntry("005930",List.of("technical","valuation","investorDaily"));
    }
}
