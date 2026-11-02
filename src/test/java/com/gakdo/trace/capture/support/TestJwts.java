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

    public static JwtRequestPostProcessor capture() {
        return capture(CHECKPOINT, RENTAL);
    }

    public static JwtRequestPostProcessor booth(String boothId) {
        return jwt().jwt(j -> j.subject(boothId).claim("role", "BOOTH").claim("scope", boothId))
                .authorities(new SimpleGrantedAuthority("ROLE_BOOTH"));
    }

    public static JwtRequestPostProcessor booth() {
        return booth(BOOTH);
    }
}
