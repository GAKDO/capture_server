// 테스트 도구(테스트 자체는 아님): 시험용 로그인 토큰(JWT) 만들기.
// auth-server가 실제로 넣어 주는 정보(역할 role, 권한 범위 scope, 렌탈 목록 rentals·rentalId)를 흉내 낸다.
// 관리자(ADMIN), 일반 고객, QR로 받은 고객 촬영 토큰, 부스(BOOTH) 토큰을 만들 수 있다. 진짜 비밀값은 없다.

package com.gakdo.trace.capture.support;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.List;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** MockMvc용 JWT. auth-server AU1 클레임(role·scope·rentals·rentalId)을 흉내 낸다. */
public final class TestJwts {

    public static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    public static final String CHECKPOINT = "IPv6mcQOlTkKj6mOecAQt";
    public static final String OTHER_CHECKPOINT = "h40b9AN9xMQhXRHrOyJWB";
    public static final String OTHER_RENTAL = "Z4U03wInx137yk13jDsmn";
    public static final String BOOTH = "booth-01";

    private TestJwts() {
    }

    // 테스트 도구: 관리자 토큰.
    public static JwtRequestPostProcessor admin() {
        return jwt().jwt(j -> j.subject("admin0000000000000001").claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** 일반 고객 토큰(scope 없음): 촬영 경로를 쓰지 못한다. */
    public static JwtRequestPostProcessor customer() {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(RENTAL)))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** QR 교환으로 받은 고객 촬영 JWT(CUSTOMER + scope=checkpointId + rentalId). */
    public static JwtRequestPostProcessor capture(String checkpointId, String rentalId) {
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("scope", checkpointId).claim("rentalId", rentalId))
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // 테스트 도구: 기본 렌탈·시점으로 만든 고객 촬영 토큰.
    public static JwtRequestPostProcessor capture() {
        return capture(CHECKPOINT, RENTAL);
    }

    // 테스트 도구: 부스 토큰(부스 ID가 곧 권한 범위).
    public static JwtRequestPostProcessor booth(String boothId) {
        return jwt().jwt(j -> j.subject(boothId).claim("role", "BOOTH").claim("scope", boothId))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }

    public static JwtRequestPostProcessor booth() {
        return booth(BOOTH);
    }
}
