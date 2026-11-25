// 이 파일이 확인하는 것: capture-server가 살아 있는지 묻는 ping 주소.
// 배포 뒤 서버가 떴는지, 어떤 버전·커밋이 돌고 있는지 확인할 때 쓴다.

package com.gakdo.trace.capture.ping;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

import com.gakdo.trace.capture.support.WebTestBase;

/**
 * capture-server의 ping(점검) 주소 시험. 배포 뒤 "서버가 떴는지, 어떤 버전·커밋이 돌고 있는지" 확인할 때 부르는 주소다.
 * 컨트롤러만 띄우는 가벼운 시험(DB·메시지 큐 없음)이며, 커밋 값은 test-sha로 고정해 응답에 그대로 나오는지 본다.
 */
@WebMvcTest(controllers = PingController.class, properties = "app.commit=test-sha")
class PingControllerTest extends WebTestBase {

    @Autowired
    private MockMvc mockMvc;

    // [상황] 로그인 토큰 없이 /api/v1/captures/ping을 부른다.
    // [기대] 200과 함께 서비스 이름(capture-server)·버전·상태 ok·커밋 값을 계약 모양대로 돌려준다.
    //        ※ 응답 코드: 200 = 정상 처리됨
    @Test
    @DisplayName("/api/v1/captures/ping은 토큰 없이 200, 계약 PingResponse(service·version·commit)")
    void ping() throws Exception {
        mockMvc.perform(get("/api/v1/captures/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.service").value("capture-server"))
                .andExpect(jsonPath("$.version").isNotEmpty())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.commit").value("test-sha"));
    }
}
