package com.grara.beat.core;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTimestamp;
import android.media.AudioTrack;

/**
 * 생성된 PCM 을 스트리밍 재생하고, 실제 스피커로 나가는 시점 기준의 곡 시간을 알려준다.
 * (AudioTimestamp 사용 → 블루투스 지연도 상당 부분 반영됨)
 */
public final class AudioPlayer {
    private final AudioTrack track;
    private final short[] pcm;
    private final AudioTimestamp ts = new AudioTimestamp();
    private volatile boolean stop;
    private volatile int pos;
    private Thread writer;
    private long startNanos;
    private double lastT = -1;

    public AudioPlayer(short[] pcm) {
        this.pcm = pcm;
        int min = AudioTrack.getMinBufferSize(Song.SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(Song.SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(Math.max(min * 2, Song.SR / 5 * 2))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    public void start() {
        int pre = Math.min(pcm.length, track.getBufferSizeInFrames());
        int w = track.write(pcm, 0, pre);
        pos = Math.max(0, w);
        track.play();
        startNanos = System.nanoTime();
        writer = new Thread(() -> {
            try {
                while (!stop && pos < pcm.length) {
                    int n = Math.min(2048, pcm.length - pos);
                    int w2 = track.write(pcm, pos, n);
                    if (w2 <= 0) break;
                    pos += w2;
                }
            } catch (Exception ignored) {
            }
        }, "beat-audio");
        writer.start();
    }

    /** 현재 들리고 있는 곡 위치(초). */
    public double time() {
        long now = System.nanoTime();
        double t;
        boolean ok = false;
        try {
            ok = track.getTimestamp(ts) && ts.framePosition > 0;
        } catch (Exception ignored) {
        }
        if (ok) {
            t = (ts.framePosition + (now - ts.nanoTime) * (double) Song.SR / 1e9) / Song.SR;
        } else {
            t = (now - startNanos) / 1e9 - 0.08;
        }
        if (t < lastT && lastT - t < 0.03) t = lastT; // 작은 역행 방지
        lastT = t;
        return t;
    }

    public double length() {
        return pcm.length / (double) Song.SR;
    }

    public void release() {
        stop = true;
        try {
            track.pause();
            track.flush();
            track.stop();
        } catch (Exception ignored) {
        }
        try {
            if (writer != null) writer.join(300);
        } catch (InterruptedException ignored) {
        }
        track.release();
    }
}
