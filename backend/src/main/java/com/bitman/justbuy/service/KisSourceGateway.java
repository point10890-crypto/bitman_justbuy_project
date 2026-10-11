package com.bitman.justbuy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Map;

/** New sources share the existing token, throttle and bounded read client. */
@Service
public class KisSourceGateway {
    public record Snapshot(String status, Instant fetchedAt, JsonNode body) {}
    private final KisApiService kis;
    private final boolean requested;
    private final boolean licensed;
    private final String htsId;
    public KisSourceGateway(KisApiService kis,
            @Value("${bitman.kis.source-expansion.enabled:false}") boolean requested,
            @Value("${bitman.kis.source-expansion.licensed:false}") boolean licensed,
            @Value("${bitman.kis.source-expansion.hts-id:}") String htsId) {
        this.kis=kis; this.requested=requested; this.licensed=licensed;
        this.htsId=htsId==null?"":htsId.trim();
    }
    public boolean isEnabled() { return requested && licensed && kis.isAvailable(); }
    public String htsId() { return htsId; }
    public Snapshot fetch(String path,String trId,Map<String,String> params,long deadlineNanos) {
        if (!requested || !licensed || !kis.isAvailable()) return new Snapshot(!requested?"DISABLED":!licensed?"RIGHTS_UNCONFIRMED":"NOT_CONFIGURED",Instant.now(),MissingNode.getInstance());
        var result=kis.fetchVerifiedReadOnly(path,trId,params,deadlineNanos);
        return new Snapshot(result.status(),result.fetchedAt(),result.body());
    }
    public String wsApproval(long deadlineNanos) { return isEnabled()?kis.fetchReadOnlyWsApproval(deadlineNanos):null; }
    public Map<String,Object> readiness() {
        return Map.of("requested",requested,"licensed",licensed,"configured",kis.isAvailable(),
            "enabled",isEnabled(),"htsConfigured",!htsId.isBlank(),"status",
            !requested?"DISABLED":!licensed?"RIGHTS_UNCONFIRMED":!kis.isAvailable()?"NOT_CONFIGURED":"READY");
    }
}
