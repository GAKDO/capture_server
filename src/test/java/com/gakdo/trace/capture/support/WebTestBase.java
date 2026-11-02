package com.gakdo.trace.capture.support;

import org.springframework.context.annotation.Import;

import com.gakdo.trace.capture.common.api.ProblemAdvice;
import com.gakdo.trace.capture.common.infra.SecurityConfig;

/** WebMvc 시험이 운영과 같은 보안 규칙·오류 형식을 쓰게 한다. */
@Import({SecurityConfig.class, ProblemAdvice.class})
public abstract class WebTestBase {
}
