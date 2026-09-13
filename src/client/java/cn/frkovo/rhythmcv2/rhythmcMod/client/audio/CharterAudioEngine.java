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
    /** 波形缓存级别：每桶样本数（第 0 级最细，逐级 ×8）。 */
    private static final int[] WAVE_STEPS = {32, 256, 2048, 16384};
    /** 多级 min/max 缓存（[级别][桶]），双声道混取的极值。 */
    private volatile short[][] waveMin = new short[0][];
    private volatile short[][] waveMax = new short[0][];
    /** 多级能量缓存（[级别][桶]）：sumSq + 样本数，用于 RMS 主体。 */
    private volatile long[][] waveSumSq = new long[0][];
    private volatile int[][] waveCount = new int[0][];
    /** 整曲峰值（绝对样本值），用于把包络归一化到 -1..1。 */
    private volatile int wavePeak;

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
                resetWaveCache();
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

    /** 时间轴 HUD 波形窗口：每像素的 min/max（峰值包络）与 rms（能量主体），已归一化。 */
    public record WaveformWindow(float[] min, float[] max, float[] rms) {
        public boolean isEmpty() {
            return min.length == 0 || max.length == 0 || rms.length == 0;
        }
    }

    /**
     * 波形窗口：给定像素数与时间区间 [fromMs, toMs)，返回每像素的 min/max 包络与 RMS。
     * 多级缓存按“每像素 1..8 桶”选级，缩到全曲不丢鼓点。
     */
    public WaveformWindow waveformWindow(int pixels, long fromMs, long toMs) {
        int n = Math.max(1, pixels);
        ensureWaveCache();
        short[][] mins = waveMin;
        short[][] maxs = waveMax;
        long[][] sums = waveSumSq;
        int[][] counts = waveCount;
        if (mins.length == 0 || maxs.length == 0 || sums.length == 0 || lengthMs <= 0) {
            return new WaveformWindow(new float[0], new float[0], new float[0]);
        }
        long from = Math.max(0L, Math.min(lengthMs, fromMs));
        long to = Math.max(from + 1L, Math.min(lengthMs, toMs));
        double framesPerMs = origRate / 1000.0;
        double samplesPerPx = Math.max(1.0, (to - from) * framesPerMs / n);
        int level = 0;
        for (int i = WAVE_STEPS.length - 1; i >= 0; i--) {
            if (WAVE_STEPS[i] <= samplesPerPx) {
                level = i;
                break;
            }
        }
        int step = WAVE_STEPS[level];
        short[] minLevel = mins[level];
        short[] maxLevel = maxs[level];
        long[] sumLevel = sums[level];
        int[] countLevel = counts[level];
        int bucketCount = minLevel.length;
        float scale = wavePeak <= 0 ? 0f : 1f / wavePeak;
        float[] outMin = new float[n];
        float[] outMax = new float[n];
        float[] outRms = new float[n];
        for (int i = 0; i < n; i++) {
            double f0 = (from + (to - from) * (double) i / n) * framesPerMs;
            double f1 = (from + (to - from) * (double) (i + 1) / n) * framesPerMs;
            int b0 = (int) (f0 / step);
            int b1 = (int) Math.ceil(f1 / step);
            b0 = Math.max(0, Math.min(bucketCount - 1, b0));
            b1 = Math.max(b0 + 1, Math.min(bucketCount, b1));
            short lo = Short.MAX_VALUE;
            short hi = Short.MIN_VALUE;
            long sumSq = 0L;
            long count = 0L;
            for (int b = b0; b < b1; b++) {
                if (minLevel[b] < lo) {
                    lo = minLevel[b];
                }
                if (maxLevel[b] > hi) {
                    hi = maxLevel[b];
                }
                sumSq += sumLevel[b];
                count += countLevel[b];
            }
            if (lo == Short.MAX_VALUE) {
                lo = 0;
            }
            if (hi == Short.MIN_VALUE) {
                hi = 0;
            }
            outMin[i] = lo * scale;
            outMax[i] = hi * scale;
            outRms[i] = count <= 0 ? 0f : (float) (Math.sqrt(sumSq / (double) count) * scale);
        }
        return new WaveformWindow(outMin, outMax, outRms);
    }

    private void resetWaveCache() {
        waveMin = new short[0][];
        waveMax = new short[0][];
        waveSumSq = new long[0][];
        waveCount = new int[0][];
        wavePeak = 0;
    }

    /** 预构建波形缓存（可在后台线程调用；避免首帧绘制卡顿）。 */
    public void prepareWaveform() {
        ensureWaveCache();
    }

    /** 懒构建多级 min/max 缓存：第 0 级扫 PCM，其余级逐级合并。 */
    private void ensureWaveCache() {
        if (waveMin.length == WAVE_STEPS.length) {
            return;
        }
        byte[] data = pcm;
        int frameBytes = 2 * channels;
        int frames = data.length / frameBytes;
        if (frames <= 0) {
            return;
        }
        short[][] mins = new short[WAVE_STEPS.length][];
        short[][] maxs = new short[WAVE_STEPS.length][];
        long[][] sums = new long[WAVE_STEPS.length][];
        int[][] counts = new int[WAVE_STEPS.length][];
        int step0 = WAVE_STEPS[0];
        int buckets = (frames + step0 - 1) / step0;
        short[] min0 = new short[buckets];
        short[] max0 = new short[buckets];
        long[] sum0 = new long[buckets];
        int[] count0 = new int[buckets];
        int peak = 0;
        int frame = 0;
        for (int b = 0; b < buckets; b++) {
            short lo = Short.MAX_VALUE;
            short hi = Short.MIN_VALUE;
            long sumSq = 0L;
            int count = 0;
            int end = Math.min(frames, frame + step0);
            for (; frame < end; frame++) {
                int idx = frame * frameBytes;
                for (int c = 0; c < channels; c++) {
                    short sample = (short) (((data[idx + 1] & 0xFF) << 8) | (data[idx] & 0xFF));
                    idx += 2;
                    if (sample < lo) {
                        lo = sample;
                    }
                    if (sample > hi) {
                        hi = sample;
                    }
                    sumSq += (long) sample * sample;
                    count++;
                }
            }
            if (lo == Short.MAX_VALUE) {
                lo = 0;
            }
            if (hi == Short.MIN_VALUE) {
                hi = 0;
            }
            min0[b] = lo;
            max0[b] = hi;
            sum0[b] = sumSq;
            count0[b] = Math.max(0, count);
            int abs = Math.max(Math.abs((int) lo), Math.abs((int) hi));
            if (abs > peak) {
                peak = abs;
            }
        }
        mins[0] = min0;
        maxs[0] = max0;
        sums[0] = sum0;
        counts[0] = count0;
        for (int level = 1; level < WAVE_STEPS.length; level++) {
            int ratio = WAVE_STEPS[level] / WAVE_STEPS[level - 1];
            short[] prevMin = mins[level - 1];
            short[] prevMax = maxs[level - 1];
            long[] prevSum = sums[level - 1];
            int[] prevCount = counts[level - 1];
            int count = (prevMin.length + ratio - 1) / ratio;
            short[] lo = new short[count];
            short[] hi = new short[count];
            long[] sum = new long[count];
            int[] cnt = new int[count];
            for (int b = 0; b < count; b++) {
                int from = b * ratio;
                int to = Math.min(prevMin.length, from + ratio);
                short l = Short.MAX_VALUE;
                short h = Short.MIN_VALUE;
                long s = 0L;
                int c = 0;
                for (int k = from; k < to; k++) {
                    if (prevMin[k] < l) {
                        l = prevMin[k];
                    }
                    if (prevMax[k] > h) {
                        h = prevMax[k];
                    }
                    s += prevSum[k];
                    c += prevCount[k];
                }
                if (l == Short.MAX_VALUE) {
                    l = 0;
                }
                if (h == Short.MIN_VALUE) {
                    h = 0;
                }
                lo[b] = l;
                hi[b] = h;
                sum[b] = s;
                cnt[b] = c;
            }
            mins[level] = lo;
            maxs[level] = hi;
            sums[level] = sum;
            counts[level] = cnt;
        }
        this.wavePeak = peak;
        this.waveMin = mins;
        this.waveMax = maxs;
        this.waveSumSq = sums;
        this.waveCount = counts;
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
            resetWaveCache();
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
