package com.gakdo.trace.capture.captureset.domain;

/** 면. codes.yaml capture.face 순서 그대로(빠진 면 목록도 이 순서). TOP 상판 · BOT 바닥 · SF·SB·SL·SR 옆면 · KB 키보드 · SCR0 화면 꺼짐 · SCR1 화면 흰색 */
public enum Face {
    /** 상판(덮개 바깥 윗면) */
    TOP,
    /** 바닥(본체 아랫면) */
    BOT,
    /** 앞 옆면(Side Front, 사람이 앉는 쪽) */
    SF,
    /** 뒤 옆면(Side Back, 힌지 쪽) */
    SB,
    /** 왼쪽 옆면(Side Left) */
    SL,
    /** 오른쪽 옆면(Side Right) */
    SR,
    /** 키보드(덮개를 연 본체 윗면). 부스 전체 검사에만 있고 폰 촬영에는 없음 */
    KB,
    /** 화면 꺼짐(SCReen 0): 꺼진 검은 화면에서 흠집이 잘 보임. 필수 */
    SCR0,
    /** 화면 흰색(SCReen 1): 흰 화면을 띄워 눌림·멍을 봄. 화면이 안 켜지면 사유와 함께 건너뛸 수 있는 선택 면 */
    SCR1
}
