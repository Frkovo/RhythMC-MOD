package cn.frkovo.rhythmcv2.rhythmcMod.client.audio;

import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import javazoom.spi.vorbis.sampled.convert.VorbisFormatConversionProvider;
import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader;
import org.jflac.sound.spi.FlacAudioFileReader;
import org.jflac.sound.spi.FlacFormatConversionProvider;
import org.jflac.FLACDecoder;
import org.jflac.PCMProcessor;
import org.jflac.metadata.StreamInfo;
import org.jflac.util.ByteData;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 解码链对齐 ChartMaker AudioStreamHelper（AudioSystem 优先，显式 vorbis/mp3/flac SPI 兜底；
 * wav/aiff/au 走 JDK 内建）。输出统一为 PCM_SIGNED 16-bit LE。
 */
final class AudioStreamHelper {
    private static final List<AudioFileReader> FALLBACK_READERS = List.of(
            new MpegAudioFileReader(),
            new VorbisAudioFileReader(),
            new FlacAudioFileReader()
    );
    private static final List<FormatConversionProvider> FALLBACK_CONVERTERS = List.of(
            new MpegFormatConversionProvider(),
            new VorbisFormatConversionProvider(),
            new FlacFormatConversionProvider()
    );

    private AudioStreamHelper() {
    }

    static AudioInputStream openDecodedStream(Path path) throws IOException, UnsupportedAudioFileException {
        AudioInputStream source = openSourceStream(path);
        try {
            AudioFormat sourceFormat = source.getFormat();
            AudioFormat targetFormat = targetFormat(sourceFormat);
            if (isTargetFormat(sourceFormat, targetFormat)) {
                return source;
            }
            return convert(source, sourceFormat, targetFormat);
        } catch (RuntimeException | UnsupportedAudioFileException exception) {
            try {
                source.close();
            } catch (IOException ignored) {
            }
            if ("flac".equals(extensionOf(path))) {
                return decodeFlacToPcm16(path);
            }
            throw exception;
        }
    }

    private static AudioInputStream openSourceStream(Path path) throws IOException, UnsupportedAudioFileException {
        try {
            return AudioSystem.getAudioInputStream(path.toFile());
        } catch (UnsupportedAudioFileException exception) {
            for (AudioFileReader reader : FALLBACK_READERS) {
                try {
                    return reader.getAudioInputStream(path.toFile());
                } catch (UnsupportedAudioFileException ignored) {
                }
            }
            throw new UnsupportedAudioFileException(extensionOf(path) + " is not supported by the bundled audio decoders");
        }
    }

    private static AudioInputStream convert(AudioInputStream source, AudioFormat sourceFormat,
                                            AudioFormat targetFormat) throws UnsupportedAudioFileException {
        if (AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
            return AudioSystem.getAudioInputStream(targetFormat, source);
        }
        for (FormatConversionProvider provider : FALLBACK_CONVERTERS) {
            if (provider.isConversionSupported(targetFormat, sourceFormat)) {
                return provider.getAudioInputStream(targetFormat, source);
            }
        }
        throw new UnsupportedAudioFileException("No PCM decoder for " + sourceFormat.getEncoding());
    }

    private static AudioFormat targetFormat(AudioFormat sourceFormat) {
        int channels = Math.max(1, sourceFormat.getChannels());
        return new AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.getSampleRate(),
                16,
                channels,
                channels * 2,
                sourceFormat.getSampleRate(),
                false
        );
    }

    private static boolean isTargetFormat(AudioFormat sourceFormat, AudioFormat targetFormat) {
        return AudioFormat.Encoding.PCM_SIGNED.equals(sourceFormat.getEncoding())
                && sourceFormat.getSampleRate() == targetFormat.getSampleRate()
                && sourceFormat.getSampleSizeInBits() == 16
                && sourceFormat.getChannels() == targetFormat.getChannels()
                && sourceFormat.isBigEndian() == targetFormat.isBigEndian();
    }

    /**
     * jflac 的 SPI 读取器/转换器对 24-bit FLAC 是直通的，这里直接用底层 FLACDecoder 解出 PCM，
     * 再统一转成 16-bit LE（字节布局见 org.jflac.FLACDecoder#decodeFrame：8-bit 无符号、16/24-bit LE）。
     */
    private static AudioInputStream decodeFlacToPcm16(Path path) throws IOException, UnsupportedAudioFileException {
        try (InputStream in = Files.newInputStream(path)) {
            FLACDecoder decoder = new FLACDecoder(in);
            decoder.readMetadata();
            StreamInfo info = decoder.getStreamInfo();
            if (info == null) {
                throw new UnsupportedAudioFileException("FLAC stream info missing");
            }
            int bits = info.getBitsPerSample();
            if (bits != 8 && bits != 16 && bits != 24) {
                throw new UnsupportedAudioFileException("FLAC bit depth " + bits + " is not supported");
            }
            int sampleBytes = bits / 8;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            decoder.addPCMProcessor(new PCMProcessor() {
                @Override
                public void processStreamInfo(StreamInfo streamInfo) {
                }

                @Override
                public void processPCM(ByteData data) {
                    byte[] raw = data.getData();
                    int length = data.getLen();
                    for (int i = 0; i + sampleBytes <= length; i += sampleBytes) {
                        int sample;
                        if (bits == 8) {
                            sample = ((raw[i] & 0xFF) - 0x80) << 8;
                        } else if (bits == 16) {
                            sample = (short) ((raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8));
                        } else {
                            int value = (raw[i] & 0xFF) | ((raw[i + 1] & 0xFF) << 8) | ((raw[i + 2] & 0xFF) << 16);
                            sample = ((value << 8) >> 8) >> 8;
                        }
                        pcm.write(sample & 0xFF);
                        pcm.write((sample >> 8) & 0xFF);
                    }
                }
            });
            decoder.decodeFrames();
            byte[] data = pcm.toByteArray();
            int channels = Math.max(1, info.getChannels());
            float rate = info.getSampleRate();
            if (data.length == 0) {
                throw new UnsupportedAudioFileException("FLAC decoded to no samples");
            }
            AudioFormat format = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16,
                    channels, channels * 2, rate, false);
            return new AudioInputStream(new ByteArrayInputStream(data), format,
                    data.length / (long) (channels * 2));
        }
    }

    private static String extensionOf(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }
}
