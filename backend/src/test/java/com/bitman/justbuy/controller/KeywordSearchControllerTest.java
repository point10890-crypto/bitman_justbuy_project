package com.bitman.justbuy.controller;

import com.bitman.justbuy.dto.KeywordSearchResponse;
import com.bitman.justbuy.service.KeywordConditionSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeywordSearchControllerTest {
    final KeywordConditionSearchService service=mock(KeywordConditionSearchService.class);
    final SubscriptionAccessGuard guard=mock(SubscriptionAccessGuard.class);
    final KeywordSearchController controller=new KeywordSearchController(service,guard);
    @Test void expiredSubscriberCannotRunADataSearch() {
        var id=UUID.randomUUID();
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"구독 만료",SubscriptionAccessGuard.CODE_EXPIRED)).when(guard).requirePro(id);
        assertThatThrownBy(() -> controller.search(id,new KeywordSearchController.Request("거래대금 상위"))).isInstanceOf(ApiException.class);
        verifyNoInteractions(service);
    }
    @Test void successfulSearchRetainsOriginalQueryAndEmptyOutcome() {
        var id=UUID.randomUUID();
        var expected=new KeywordSearchResponse("현재가 1만원 이하","EMPTY",List.of("현재가 ≤ 10000원"),List.of(),
            new KeywordSearchResponse.Scope("실데이터 범위",10,10,0,false),null,"COMPLETE",List.of(),List.of());
        when(service.search(expected.query(),id)).thenReturn(expected);
        assertThat(controller.search(id,new KeywordSearchController.Request(expected.query()))).isEqualTo(expected);
        verify(guard).requirePro(id);
    }
    @Test void progressRequiresTheSameProAccessAndPassesOnlyTheAuthenticatedOwner() {
        var id=UUID.randomUUID();var progressId=UUID.randomUUID().toString();
        var expected=new KeywordSearchResponse("최근 3거래일 외국인 순매수","EMPTY",List.of(),List.of(),
            new KeywordSearchResponse.Scope("고정 후보",1,1,0,false),null,"COMPLETE",List.of(),List.of());
        when(service.progress(id,progressId)).thenReturn(expected);
        assertThat(controller.progress(id,new KeywordSearchController.ProgressRequest(progressId))).isSameAs(expected);
        verify(guard).requirePro(id);verify(service).progress(id,progressId);
    }
    @Test void expiredSubscriptionCannotReadAnotherProgressSnapshot() {
        var id=UUID.randomUUID();doThrow(new ApiException(HttpStatus.FORBIDDEN,"구독 만료",SubscriptionAccessGuard.CODE_EXPIRED)).when(guard).requirePro(id);
        assertThatThrownBy(()->controller.progress(id,new KeywordSearchController.ProgressRequest(UUID.randomUUID().toString()))).isInstanceOf(ApiException.class);
        verifyNoInteractions(service);
    }
}
