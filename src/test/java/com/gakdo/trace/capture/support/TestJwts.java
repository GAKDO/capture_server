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

    // 테스트용 기본 렌탈 공개 ID (21자)
    public static final String RENTAL = "Ws0HLv6QRbDILPWOcz5I2";
    // 테스트용 기본 시점 공개 ID
    public static final String CHECKPOINT = "IPv6mcQOlTkKj6mOecAQt";
    // 권한 밖 시험에 쓸 다른 시점 ID
    public static final String OTHER_CHECKPOINT = "h40b9AN9xMQhXRHrOyJWB";
    // 권한 밖 시험에 쓸 다른 렌탈 ID
    public static final String OTHER_RENTAL = "Z4U03wInx137yk13jDsmn";
    // 테스트용 부스 ID
    public static final String BOOTH = "booth-01";

    private TestJwts() {
    }

    // 테스트 도구: 관리자 토큰.
    public static JwtRequestPostProcessor admin() {
        // (도구) jwt() = 진짜 로그인 없이 "이 토큰을 가진 사용자"로 요청을 보내게 해 주는 Spring Security 테스트 도구
        // sub=관리자 ID, role=ADMIN을 넣고 서버가 권한 검사에 쓰는 ROLE_ADMIN 권한을 붙인다
        return jwt().jwt(j -> j.subject("admin0000000000000001").claim("role", "ADMIN"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** 일반 고객 토큰(scope 없음): 촬영 경로를 쓰지 못한다. */
    public static JwtRequestPostProcessor customer() {
        // sub=고객 ID, role=CUSTOMER, 볼 수 있는 렌탈 목록(rentals)을 넣고
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                .claim("rentals", List.of(RENTAL)))
                // ROLE_CUSTOMER 권한을 붙인다 (scope가 없어 촬영은 못 함)
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    /** QR 교환으로 받은 고객 촬영 JWT(CUSTOMER + scope=checkpointId + rentalId). */
    public static JwtRequestPostProcessor capture(String checkpointId, String rentalId) {
        // sub=고객 ID, role=CUSTOMER에 더해
        return jwt().jwt(j -> j.subject("cust00000000000000001").claim("role", "CUSTOMER")
                // scope=찍을 시점 ID, rentalId=렌탈 ID를 넣는다 (이 시점만 찍을 수 있는 토큰)
                .claim("scope", checkpointId).claim("rentalId", rentalId))
                // ROLE_CUSTOMER 권한을 붙인다
                .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"));
    }

    // 테스트 도구: 기본 렌탈·시점으로 만든 고객 촬영 토큰.
    public static JwtRequestPostProcessor capture() {
        // 기본 시점·렌탈로 위 촬영 토큰을 만든다
        return capture(CHECKPOINT, RENTAL);
    }

    // 테스트 도구: 부스 토큰(부스 ID가 곧 권한 범위).
    public static JwtRequestPostProcessor booth(String boothId) {
        // sub=부스 ID, role=BOOTH, scope=부스 ID를 넣고 ROLE_BOOTH 권한을 붙인다
        return jwt().jwt(j -> j.subject(boothId).claim("role", "BOOTH").claim("scope", boothId))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }

    // 테스트 도구: 기본 부스 ID(booth-01)로 만든 부스 토큰
    public static JwtRequestPostProcessor booth() {
        // 기본 부스 ID로 위 부스 토큰을 만든다
        return booth(BOOTH);
    }
}
