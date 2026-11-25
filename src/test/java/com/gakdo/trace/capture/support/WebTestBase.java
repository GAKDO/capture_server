// 테스트 도구(테스트 자체는 아님): 웹 요청 시험(WebMvc)들이 물려받는 바탕.
// 운영과 같은 보안 규칙(누가 어떤 주소를 부를 수 있는지)과 같은 오류 응답 형식(Problem Details)을 쓰게 한다.

package com.gakdo.trace.capture.support;

import org.springframework.context.annotation.Import;

import com.gakdo.trace.capture.common.api.ProblemAdvice;
import com.gakdo.trace.capture.common.infra.SecurityConfig;

/** WebMvc 시험이 운영과 같은 보안 규칙·오류 형식을 쓰게 한다. */
@Import({SecurityConfig.class, ProblemAdvice.class})
public abstract class WebTestBase {
}
