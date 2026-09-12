package cn.frkovo.rhythmcv2.rhythmcMod.client.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;
import java.nio.file.Path;

/**
 * 本地音频引擎：PCM 常驻内存 + SourceDataLine 流式播放。
 * 变速 = 重采样变调（诚实折衷，§10.2）：line 以 origRate×speed 打开，字节原样写入 → 时钟缩放。
 * seek/loop/A-B 由引擎内部保证；positionMs 由已写帧数推算。
 */
public final class CharterAudioEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger("RhythMC-Charter");

    private volatile byte[] pcm = new byte[0];
    private volatile float origRate = 44100f;
    private volatile int channels = 2;
    private volatile long lengthMs;
    private volatile String loadedSha1 = "";
    /** 时间轴 HUD 高分辨率峰值缓存（全曲固定桶数），窗口视图按比例采样。 */
    private volatile float[] hiResPeaks = new float[0];
    private static final int HI_RES_BUCKETS = 16384;

    private final Object lock = new Object();
    private SourceDataLine line;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean playing;

    private volatile float speed = 1.0f;
    private volatile long framesWritten;
    private volatile int pcmOffset;
    /** A-B 循环（ms）；bMs < 0 = 无循环。 */
    private volatile long loopAMs = -1;
    private volatile long loopBMs = -1;

    // ---- 加载 ----

    public boolean load(Path file, String sha1) {
        stopInternal();
        try (AudioInputStream decoded = AudioStreamHelper.openDecodedStream(file)) {
            AudioFormat fmt = decoded.getFormat();
            byte[] data = decoded.readAllBytes();
            synchronized (lock) {
                this.pcm = data;
                this.origRate = fmt.getSampleRate();
                this.channels = Math.max(1, fmt.getChannels());
                this.lengthMs = (long) (data.length / (double) (2 * channels) / origRate * 1000.0);
                this.loadedSha1 = sha1 == null ? "" : sha1;
                this.pcmOffset = 0;
                this.framesWritten = 0;
                this.hiResPeaks = new float[0];
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("音频解码失败: {}", file, e);
            return false;
        }
    }

    public boolean isLoaded() {
        return lengthMs > 0;
    }

    public long lengthMs() {
        return lengthMs;
    }

    public String loadedSha1() {
        return loadedSha1;
    }

    // ---- Transport ----

    public void play(long fromMs, float speed) {
        if (!isLoaded()) {
            return;
        }
        setSpeedInternal(speed);
        synchronized (lock) {
            this.pcmOffset = framesToBytes(msToFrames(fromMs));
            this.framesWritten = msToFrames(fromMs);
        }
        startWorker();
    }

    public void pause() {
        playing = false;
    }

    public void seek(long toMs) {
        if (!isLoaded()) {
            return;
        }
        synchronized (lock) {
            this.pcmOffset = framesToBytes(msToFrames(toMs));
            this.framesWritten = msToFrames(toMs);
        }
    }

    public void stop() {
        playing = false;
        synchronized (lock) {
            this.pcmOffset = 0;
            this.framesWritten = 0;
        }
    }

    public void setLoop(long aMs, long bMs) {
        this.loopAMs = aMs;
        this.loopBMs = bMs;
    }

    public void setSpeed(float newSpeed) {
        boolean wasPlaying = playing;
        long pos = positionMs();
        setSpeedInternal(newSpeed);
        if (wasPlaying) {
            // 重建 line 以新速率继续
            synchronized (lock) {
                this.framesWritten = msToFrames(pos);
                this.pcmOffset = framesToBytes(msToFrames(pos));
            }
            startWorker();
        }
    }

    private void setSpeedInternal(float newSpeed) {
        this.speed = Math.max(0.5f, Math.min(2.0f, newSpeed));
    }

    public long positionMs() {
        if (!isLoaded()) {
            return 0;
        }
        double framesPerMs = (origRate * speed) / 1000.0;
        return (long) (framesWritten / framesPerMs);
    }

    public boolean isPlaying() {
        return playing;
    }

    public float speed() {
        return speed;
    }

    public long loopAMs() {
        return loopAMs;
    }

    public long loopBMs() {
        return loopBMs;
    }

    /**
     * 时间轴 HUD 波形峰值（0..1）：全曲按桶取第一声道的 max|sample|。
     */
    public float[] waveformPeaks(int buckets) {
        return waveformWindow(buckets, 0d, 1d);
    }

    /** 波形窗口：给定 [fromFraction, toFraction] 区间，从高分辨率缓存按比例采样。 */
    public float[] waveformWindow(int buckets, double fromFraction, double toFraction) {
        int n = Math.max(1, buckets);
        float[] hi = hiResPeaks();
        if (hi.length == 0) {
            return new float[0];
        }
        double from = Math.max(0d, Math.min(1d, fromFraction));
        double to = Math.max(from + 1.0E-9d, Math.min(1d, toFraction));
        float[] result = new float[n];
        for (int i = 0; i < n; i++) {
            int i0 = (int) Math.floor((from + (to - from) * i / n) * hi.length);
            int i1 = (int) Math.ceil((from + (to - from) * (i + 1) / n) * hi.length);
            i0 = Math.max(0, Math.min(hi.length - 1, i0));
            i1 = Math.max(i0 + 1, Math.min(hi.length, i1));
            float peak = 0f;
            for (int k = i0; k < i1; k++) {
                if (hi[k] > peak) {
                    peak = hi[k];
                }
            }
            result[i] = peak;
        }
        return result;
    }

    private float[] hiResPeaks() {
        float[] cached = hiResPeaks;
        if (cached.length == HI_RES_BUCKETS) {
            return cached;
        }
        byte[] data = pcm;
        if (data.length == 0 || lengthMs <= 0 || channels <= 0) {
            return new float[0];
        }
        int frameBytes = 2 * channels;
        int frames = data.length / frameBytes;
        if (frames <= 0) {
            return new float[0];
        }
        float[] computed = computePeaks(0, frames, HI_RES_BUCKETS);
        hiResPeaks = computed;
        return computed;
    }

    /** 按帧区间 [firstFrame, lastFrame) 计算 buckets 个峰值（第一声道）。 */
    private float[] computePeaks(int firstFrame, int lastFrame, int buckets) {
        byte[] data = pcm;
        int frameBytes = 2 * channels;
        int frames = data.length / frameBytes;
        int from = Math.max(0, Math.min(frames - 1, firstFrame));
        int to = Math.max(from + 1, Math.min(frames, lastFrame));
        int span = to - from;
        float[] result = new float[buckets];
        for (int i = 0; i < buckets; i++) {
            int f0 = from + (int) ((long) span * i / buckets);
            int f1 = from + (int) ((long) span * (i + 1) / buckets);
            if (f1 <= f0) {
                f1 = f0 + 1;
            }
            float peak = 0f;
            for (int f = f0; f < f1 && f < frames; f++) {
                int idx = f * frameBytes;
                short sample = (short) (((data[idx + 1] & 0xFF) << 8) | (data[idx] & 0xFF));
                float value = Math.abs(sample) / 32768f;
                if (value > peak) {
                    peak = value;
                }
            }
            result[i] = peak;
        }
        return result;
    }

    // ---- 内部 ----

    private long msToFrames(long ms) {
        return (long) (ms * (origRate * speed) / 1000.0);
    }

    private int framesToBytes(long frames) {
        return (int) (frames * 2 * channels);
    }

    private void startWorker() {
        synchronized (lock) {
            playing = true;
            if (worker != null && worker.isAlive()) {
                return;
            }
            running = true;
            worker = new Thread(this::runLoop, "CharterAudioEngine");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void runLoop() {
        while (running) {
            if (!playing) {
                sleep(20);
                continue;
            }
            SourceDataLine current;
            synchronized (lock) {
                current = ensureLine();
                if (current == null) {
                    playing = false;
                    continue;
                }
            }
            // A-B 循环
            if (loopBMs >= 0 && positionMs() >= loopBMs) {
                synchronized (lock) {
                    pcmOffset = framesToBytes(msToFrames(loopAMs));
                    framesWritten = msToFrames(loopAMs);
                }
            }
            int frameBytes = 2 * channels;
            int chunk = 4096 * frameBytes;
            byte[] buf = new byte[chunk];
            int wrote = 0;
            synchronized (lock) {
                int available = Math.min(chunk, pcm.length - pcmOffset);
                if (available <= 0) {
                    // 播完
                    if (loopBMs >= 0 && loopAMs >= 0) {
                        pcmOffset = framesToBytes(msToFrames(loopAMs));
                        framesWritten = msToFrames(loopAMs);
                    } else {
                        playing = false;
                    }
                } else {
                    System.arraycopy(pcm, pcmOffset, buf, 0, available);
                    pcmOffset += available;
                    wrote = current.write(buf, 0, available);
                    framesWritten += wrote / frameBytes;
                }
            }
            if (wrote == 0) {
                sleep(10);
            }
        }
        closeLine();
    }

    private SourceDataLine ensureLine() {
        if (line != null && line.isOpen()
                && Math.abs(line.getFormat().getSampleRate() - origRate * speed) < 0.5f) {
            if (!line.isRunning()) {
                line.start();
            }
            return line;
        }
        closeLine();
        try {
            AudioFormat fmt = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED,
                    origRate * speed, 16, channels, channels * 2, origRate * speed, false);
            line = AudioSystem.getSourceDataLine(fmt);
            line.open(fmt, fmt.getFrameSize() * 8192);
            line.start();
            return line;
        } catch (Exception e) {
            line = null;
            return null;
        }
    }

    private void closeLine() {
        synchronized (lock) {
            if (line != null) {
                try {
                    line.stop();
                    line.close();
                } catch (Exception ignored) {
                }
                line = null;
            }
        }
    }

    private void stopInternal() {
        running = false;
        playing = false;
        Thread w = worker;
        if (w != null) {
            w.interrupt();
            worker = null;
        }
        closeLine();
        synchronized (lock) {
            pcm = new byte[0];
            lengthMs = 0;
            loadedSha1 = "";
            pcmOffset = 0;
            framesWritten = 0;
            loopAMs = -1;
            loopBMs = -1;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
