package com.opensource.svgaplayer.utils

import okio.Buffer
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException

/**
 * SVGA 解析和图片下载共用的 I/O 辅助方法。
 *
 * 主要负责两件事：在读取过程中限制数据量，以及通过线程中断标记响应任务取消。
 * 限制针对实际读取的数据生效，不依赖网络 Content-Length 或 ZIP 条目声明的大小，
 * 因而也适用于长度未知、分块传输或解压后显著膨胀的输入。
 *
 * 本工具不决定资源所有权：除非调用方显式关闭 [LimitedInputStream]，否则不会主动关闭
 * 底层输入流。网络连接的断开、读取超时以及任务取消仍由创建这些资源的代码负责。
 */
internal object SVGAIO {
    /**
     * 在读取、解码或遍历素材的循环中检查当前任务是否已被中断。
     *
     * 使用 isInterrupted 查询标记，不像 Thread.interrupted() 那样清除标记，
     * 以便上层流程也能识别取消状态。此方法属于协作式取消检查，不能自行唤醒一个
     * 已经阻塞的网络 read()；网络任务还需要配合读取超时和连接断开。
     *
     * @throws InterruptedIOException 当前线程已被中断，应停止处理并释放本次任务的资源。
     */
    fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("SVGA request cancelled")
    }

    /**
     * 将输入流从当前位置读取到 EOF，并限制本次读取的总字节数。
     *
     * 适用于需要完整字节数组的压缩素材或图片数据。使用 Okio 的分段 Buffer 累积内容，
     * 由 readFrom() 直接将输入读入分段，避免连续大数组扩容时的反复复制，以及中间
     * 搬运数组到累积缓冲的复制。最后 readByteArray() 仍会生成完整结果数组，
     * 分段与结果在转换期间可能同时存在，因此调用方仍必须传入合适的上限。
     * 已支持流式解析的解压数据应直接使用 [LimitedInputStream]，避免额外的整块副本。
     *
     * 此方法无论成功还是失败都不关闭 [input]，调用方应根据所有权通过 use/finally 关闭。
     * 恰好读取 [limit] 字节且随后到达 EOF 时正常返回；只有确实读到额外数据才报错。
     *
     * @param input 从当前位置开始读取的输入流。
     * @param limit 本次允许读取的最大字节数，单位为 byte，调用方须传入非负值。
     * @return 本次读取的完整数据，不包含调用前已经读走的内容。
     * @throws IOException 数据超出上限，或底层流读取失败。
     * @throws InterruptedIOException 读取期间检测到线程中断。
     */
    fun readBytes(input: InputStream, limit: Long): ByteArray {
        val buffer = Buffer()
        // 包装但不关闭输入流，保持关闭责任属于调用方。
        val limited = LimitedInputStream(input, limit)
        try {
            // 继续通过限流包装器执行实际字节计数、中断检查和超限探测。
            buffer.readFrom(limited)
            return buffer.readByteArray()
        } finally {
            // Buffer.close() 不会清空内容；异常时也需显式释放本次持有的分段。
            // clear() 仅清理累积缓冲，不会关闭调用方的输入流。
            buffer.clear()
        }
    }

    /**
     * 带累计字节数限制和中断检查的输入流装饰器，不会预先读取完整输入。
     *
     * read()、批量 read() 和 skip() 都会消耗同一份额度，避免通过跳过数据绕过限制。
     * 计数从包装器创建时的底层流位置开始，使用 Long，防止大输入累计计数溢出 Int。
     * 本类面向单向消费：调用方不应依赖 mark/reset 回退额度，底层流回退不会重置计数。
     * 包装器也不用于多线程并发读取。
     *
     * 调用继承的 close() 会关闭底层流；达到上限或抛出异常本身不会自动关闭流。
     * 一旦发生超限异常，调用方应结束本次解析，而不是忽略异常继续读取。
     *
     * @param input 被包装的输入流，可以是网络流、文件流或解压流。
     * @param limit 最多允许累计读取和跳过的字节数，调用方须传入非负值。
     */
    class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
        /** 已成功从底层流读取或跳过的字节数；不计入 EOF 和零长度结果。 */
        private var count = 0L

        /** 先记录实际读取量，再检查上限；原样返回读取结果，保留 EOF 的 -1 语义。 */
        private fun record(size: Int): Int {
            if (size > 0) count += size
            if (count > limit) throw IOException("SVGA data exceeds $limit bytes")
            return size
        }

        /** 单字节读取：返回值是 0..255 或 EOF(-1)，计数增加的是 1，而不是字节的数值。 */
        override fun read(): Int {
            checkInterrupted()
            val value = `in`.read()
            record(if (value < 0) 0 else 1)
            return value
        }

        /** 批量读取只请求“剩余额度 + 1”以内的数据，以最小探测量判断是否超限。 */
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkInterrupted()
            // 多出的 1 字节用于区分“恰好到达额度且 EOF”和“还有超限数据”。
            // 额度用尽时仍允许探测一次，避免直接抛错而误拒绝大小恰好的合法输入。
            // 若读到了该探测字节，record() 会先抛出异常，不会把它交给正常解析流程。
            val allowed = minOf(length.toLong(), (limit - count + 1).coerceAtLeast(1)).toInt()
            // 直接委托底层流，避免经由本包装器其他 read() 重复累计同一批字节。
            return record(`in`.read(buffer, offset, allowed))
        }

        /** skip() 同样受额度限制；按底层实际跳过的数量累计，而非调用方请求的数量。 */
        override fun skip(size: Long): Long {
            checkInterrupted()
            // 与批量读取一致，最多额外探测 1 字节；超限后立即终止本次操作。
            val skipped = `in`.skip(minOf(size, (limit - count + 1).coerceAtLeast(1)))
            count += skipped
            if (count > limit) throw IOException("SVGA data exceeds $limit bytes")
            return skipped
        }
    }
}
