package com.bitman.justbuy.dto.condition;

import java.util.List;

/**
 * OpenDART 공시검색(list.json) 단일 공시 이벤트.
 *
 * 테마주(CATALYST_BURST) 섹션의 미래 후보 수집을 위한 휴면 기반 데이터.
 * report_nm 키워드 매칭으로 부여된 {@code tags}를 포함한다(없으면 빈 리스트).
 *
 * @param corpCode    DART corp_code (8자리)
 * @param stockCode   종목코드 (6자리, 상장사만 존재)
 * @param corpName    회사명
 * @param reportName  공시 보고서명 (report_nm)
 * @param receiptNo   접수번호 (rcept_no)
 * @param receiptDate 접수일자 (rcept_dt, yyyyMMdd)
 * @param tags        키워드 매칭으로 부여된 태그 (없으면 빈 리스트)
 */
public record DisclosureEvent(
    String corpCode,
    String stockCode,
    String corpName,
    String reportName,
    String receiptNo,
    String receiptDate,
    List<String> tags
) {}
