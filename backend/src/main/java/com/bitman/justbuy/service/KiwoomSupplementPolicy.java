package com.bitman.justbuy.service;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Pure policy for KIS-primary, Kiwoom-supplement source groups.
 * An AVAILABLE group is retained as primary. Individual missing MA/PER/PBR fields
 * inside that group are not inspected: this is group-level supplementation, not field-level merging.
 * Undated investor estimates never confirm daily flow. Quotes are handled by the caller separately.
 */
public final class KiwoomSupplementPolicy {
    private KiwoomSupplementPolicy() {}
    public static Map<String,List<String>> missingParts(List<String> codes,Map<String,Object> primaryMetadata) {
        if(codes==null || codes.isEmpty()) return Map.of();
        Set<String> selected=new LinkedHashSet<>();
        for(String code:codes) {
            if(code!=null && code.matches("[0-9]{6}")) selected.add(code);
            if(selected.size()==3) break;
        }
        Map<?,?> states=Map.of();
        if(primaryMetadata!=null && !"DISABLED".equals(primaryMetadata.get("status")) && !"UNAVAILABLE".equals(primaryMetadata.get("status"))
            && primaryMetadata.get("sourceStates") instanceof Map<?,?> verifiedStates) states=verifiedStates;
        Map<String,List<String>> result=new LinkedHashMap<>();
        for(String code:selected) {
            Map<?,?> main=states.get(code) instanceof Map<?,?> group?group:Map.of();
            Map<?,?> supplemental=states.get(code+"Supplemental") instanceof Map<?,?> group?group:Map.of();
            List<String> missing=new ArrayList<>(3);
            if(!"AVAILABLE".equals(main.get("technical"))) missing.add("technical");
            if(!"AVAILABLE".equals(main.get("valuation"))) missing.add("valuation");
            if(!"AVAILABLE".equals(supplemental.get("investorDaily"))) missing.add("investorDaily");
            if(!missing.isEmpty()) result.put(code,List.copyOf(missing));
        }
        return Collections.unmodifiableMap(result);
    }
}
