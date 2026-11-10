package com.grara.beat.core;

/** 채보의 노트 하나. */
public final class Note {
    public static final int TAP = 0, SPIN = 1, HOLD = 2;

    /** 트릭: 날아오는 모습/규칙을 바꾸는 효과 */
    public static final int TR_NONE = 0, TR_HIDDEN = 1, TR_SUDDEN = 2, TR_ACCEL = 3, TR_DECEL = 4,
            TR_SPIRAL = 5, TR_WOBBLE = 6, TR_BOMB = 7, TR_COUNT = 8;
    public static final String[] TRICK_NAMES = {"없음", "숨김", "깜짝", "가속", "감속", "회오리", "흔들", "폭탄"};

    public final double time;   // 초 (오디오 시작 기준)
    /** HOLD(줄) 노트의 끝 시각. 다른 노트는 time 과 같음 */
    public final double end;
    public final int type;
    /** SPIN 방향: 0 = 아무 방향, 1 = 시계, -1 = 반시계 */
    public final int dir;
    /** 원형 화면에서 날아오는 각도 (도) */
    public final float angle;
    /** 트릭 (TR_*) */
    public final int trick;

    /** 판정 결과 (-1 = 아직). 게임 쪽에서 사용. */
    public int result = -1;
    /** HOLD 끝 판정 (-1 = 아직) */
    public int tailResult = -1;

    public Note(double time, int type, int dir, float angle) {
        this(time, time, type, dir, angle);
    }

    public Note(double time, double end, int type, int dir, float angle) {
        this(time, end, type, dir, angle, TR_NONE);
    }

    public Note(double time, double end, int type, int dir, float angle, int trick) {
        this.time = time;
        this.end = end;
        this.type = type;
        this.dir = dir;
        this.angle = angle;
        this.trick = trick;
    }

    /** 이 노트 종류에 쓸 수 있는 트릭인지 (폭탄은 탭만, 회오리/흔들은 탭·줄만) */
    public static boolean trickAllowed(int type, int trick) {
        if (trick == TR_BOMB) return type == TAP;
        if (trick == TR_SPIRAL || trick == TR_WOBBLE) return type != SPIN;
        return trick >= 0 && trick < TR_COUNT;
    }
}
