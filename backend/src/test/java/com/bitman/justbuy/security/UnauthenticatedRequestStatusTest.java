package com.bitman.justbuy.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 인증이 없거나 토큰이 무효일 때는 401 이어야 한다.
 *
 * <p>전에는 전부 403 이 나갔다. 그런데 403 은 구독 가드가 "PRO 구독자만 사용 가능"에도
 * 쓰는 코드라, 프론트가 "다시 로그인시켜야 하는 상황"과 "구독을 유도해야 하는 상황"을
 * 구분할 수 없었다. 비밀번호 변경으로 토큰을 끊어도 클라이언트가 로그아웃하지 않고
 * 알 수 없는 오류만 띄우게 된다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UnauthenticatedRequestStatusTest {

    @Autowired MockMvc mockMvc;

    @Test
    void protectedEndpointWithoutATokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void adminEndpointWithoutATokenReturnsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/users"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenReturnsUnauthorizedNotForbidden() throws Exception {
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer not-a-real-token"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void publicEndpointStaysReachable() throws Exception {
        mockMvc.perform(get("/api/health"))
            .andExpect(status().isOk());
    }
}
