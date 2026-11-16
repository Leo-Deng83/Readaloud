package top.readaloud.app

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 把语音引擎合成出来的音频采样点写成 WAV 文件。
 *
 * 为什么需要它？S3 换成 ExoPlayer 之后，播放器只认"文件 / 网址"这类数据源，
 * 不认内存里的一堆数字。所以要把每段音频先存成 WAV，再交给播放器排队播放。
 *
 * WAV 其实就是「44 字节的标签头 + 原始音频数据」，
 * 注意里面的数字必须按**小端序**写（这是 WAV 格式的规定，写反了就是噪音）。
 */
object WavWriter {

    fun write(
        file: File,
        samples: FloatArray,
        sampleRate: Int,
        padStartSec: Float = 0f,
        padEndSec: Float = 0f
    ) {
        val pre = (sampleRate * padStartSec).toInt()
        val post = (sampleRate * padEndSec).toInt()
        val dataSize = (pre + samples.size + post) * 2   // 16 位 = 2 字节/采样点

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(36 + dataSize)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                    // fmt 块大小
        header.putShort(1)                   // 1 = PCM
        header.putShort(1)                   // 单声道
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)        // 每秒字节数
        header.putShort(2)                   // 每个采样点占 2 字节
        header.putShort(16)                  // 16 位
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataSize)

        val body = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN)
        repeat(pre) { body.putShort(0) }     // 开头的静音（章节标题前用）
        for (s in samples) {
            val v = s.coerceIn(-1f, 1f)
            body.putShort((v * 32767f).toInt().toShort())
        }
        repeat(post) { body.putShort(0) }    // 结尾的静音（句间/章节后的停顿）

        FileOutputStream(file).use { out ->
            out.write(header.array())
            out.write(body.array())
        }
    }
}
