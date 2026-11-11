package com.gakdo.trace.capture.common.infra;

import java.util.List;

import org.springframework.security.oauth2.jwt.Jwt;

import com.gakdo.trace.capture.common.domain.DomainException;
import com.gakdo.trace.capture.common.domain.ErrorCode;

/**
 * 요청한 토큰(auth-server AU1 클레임). 범위 판단은 여기 한 곳에서 한다.
 * <ul>
 * <li>BOOTH: scope = boothId(= sub)</li>
 * <li>촬영 JWT: role CUSTOMER + scope = checkpointId + rentalId</li>
 * <li>일반 고객: rentals = 본인 렌탈 목록(검사 결과 조회, CA2)</li>
 * <li>ADMIN: 제한 없음(조회만)</li>
 * </ul>
 */
public record Caller(String role, String scope, String rentalId, List<String> rentals) {

    public Caller {
        rentals = rentals == null ? List.of() : List.copyOf(rentals);
    }

    public static Caller from(Jwt jwt) {
        return new Caller(jwt.getClaimAsString("role"), jwt.getClaimAsString("scope"), jwt.getClaimAsString("rentalId"),
                jwt.getClaimAsStringList("rentals"));
    }

    /** 일반 고객 토큰(촬영 JWT 아님)이 그 렌탈을 가졌나(rentals 클레임, OWN_RENTAL 범위). */
    public boolean ownsRental(String rental) {
        return "CUSTOMER".equals(role) && !isCaptureToken() && rentals.contains(rental);
    }

    public boolean isAdmin() {
        return "ADMIN".equals(role);
    }

    public boolean isBooth() {
        return "BOOTH".equals(role) && scope != null && !scope.isBlank();
    }

    /** 고객 폰 촬영 JWT(QR 교환으로 받은 토큰). 일반 고객 토큰에는 scope가 없다. */
    public boolean isCaptureToken() {
        return "CUSTOMER".equals(role) && PublicIds.isValid(scope);
    }

    /** 촬영 경로는 부스 토큰이나 촬영 JWT만. 일반 고객 토큰·scope 없는 부스 토큰은 403 FORBIDDEN. */
    public void requireCapturer() {
        if (!isBooth() && !isCaptureToken()) {
            throw new DomainException(ErrorCode.FORBIDDEN, "부스 토큰이나 촬영 링크로 받은 토큰만 쓸 수 있습니다.");
        }
    }

    public static DomainException outOfScope() {
        return new DomainException(ErrorCode.OUT_OF_SCOPE, null);
    }
}
