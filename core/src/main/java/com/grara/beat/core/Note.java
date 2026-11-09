package com.grara.beat.core;

/** 채보의 노트 하나. */
public final class Note {
    public static final int TAP = 0, SPIN = 1;

    public final double time;   // 초 (오디오 시작 기준)
    public final int type;
    /** SPIN 방향: 0 = 아무 방향, 1 = 시계, -1 = 반시계 */
    public final int dir;
    /** 원형 화면에서 날아오는 각도 (도) */
    public final float angle;

    /** 판정 결과 (-1 = 아직). 게임 쪽에서 사용. */
    public int result = -1;

    public Note(double time, int type, int dir, float angle) {
        this.time = time;
        this.type = type;
        this.dir = dir;
        this.angle = angle;
    }
}
