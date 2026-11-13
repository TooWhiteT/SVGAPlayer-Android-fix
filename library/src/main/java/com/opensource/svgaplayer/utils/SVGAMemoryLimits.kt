package com.opensource.svgaplayer.utils

/**
 * SVGA 素材处理使用的默认内存和资源预算，集中供解码器、解析器及绘制缓存使用。
 *
 * 字节预算统一取“固定上限”和“应用堆上限的一定比例”中的较小值：
 * 固定上限约束高内存设备上的单个大素材，堆比例则进一步收紧低内存设备上的开销。
 * Runtime.maxMemory() 表示当前应用进程的最大 Java 堆容量，不是设备物理内存或当前
 * 剩余可用内存。各 val 在本对象首次初始化时计算，之后不会随堆占用动态变化。
 *
 * 这些值是不同处理阶段的上限，不会预分配内存，也不代表整个播放器的总占用上限。
 * 原始数据、解码贴图、动态图片、文字缓存及渲染层可能同时存在；多个播放器也会叠加。
 * 调用方仍需累计实际占用、限制并发，并在分配失败时降采样或中止处理。
 */
internal object SVGAMemoryLimits {
    /** 二进制容量单位：1 MiB = 1,048,576 bytes；使用 Long 参与预算运算。 */
    private const val MIB = 1024L * 1024L

    /**
     * 单张解码位图的字节上限：min(16 MiB, 应用堆上限 / 16)。
     *
     * 采样计算按 ARGB_8888 的 4 bytes/pixel 保守估算，解码后再检查实际分配量。
     * 此预算也用于动态网络图片的单图解码，以及绘制器文字位图缓存的容量限制。
     * 它限制解码后的像素内存，与 PNG/JPEG 文件的压缩大小不同。
     */
    val bitmapBytes = minOf(16 * MIB, Runtime.getRuntime().maxMemory() / 16)

    /**
     * 单个视频实体的贴图总预算：min(32 MiB, 应用堆上限 / 8)。
     *
     * 视频加载时会根据剩余额度和剩余图片数分配每张贴图的预算。
     * 动态实体也独立使用此上限，限制网络图片与待交付结果的累计占用；
     * 两份预算分别记账，不是静态贴图和动态图片合计只能占用这一份额度。
     * 调用方直接传入的 Bitmap 不在解码阶段重新采样，调用方仍须控制其大小。
     */
    val videoBitmapBytes = minOf(32 * MIB, Runtime.getRuntime().maxMemory() / 8)

    /**
     * 单次原始输入的字节上限：min(32 MiB, 应用堆上限 / 8)。
     *
     * 用于素材下载、压缩 SVGA 输入以及动态图片下载。按实际读取字节执行限制，
     * 防止长度未知或声明不准确的输入使内存缓冲无限增长；不等同于像素内存预算。
     */
    val inputBytes = minOf(32 * MIB, Runtime.getRuntime().maxMemory() / 8)

    /**
     * 解压或读取展开后素材数据的上限：min(64 MiB, 应用堆上限 / 4)。
     *
     * 约束解压后的 Protobuf、JSON 数据，以及 ZIP 各输出文件的累计展开字节数，
     * 避免体积很小的压缩包在展开后占用过多资源。图片随后解码成像素时，
     * 还需另外遵守 [bitmapBytes] 和 [videoBitmapBytes]，不能只检查此处的文件大小。
     */
    val inflatedBytes = minOf(64 * MIB, Runtime.getRuntime().maxMemory() / 4)

    /**
     * 解码后位图任一边的最大像素数，与字节预算同时生效。
     * 可约束长宽比例极端的图片；超出此尺寸的原图会继续采样，而非直接按原尺寸分配。
     */
    const val MAX_BITMAP_DIMENSION = 4096

    /**
     * 每个视频最多加载的不同音频片段数，用于限制 SoundPool 的原生采样资源。
     *
     * 相同 audioKey、startTime、totalTime 的重复播放事件会复用片段，不重复计数。
     * 此限制针对已加载片段数量，与 SoundPool 同时播放的音频流数量不同。
     */
    const val MAX_SOUND_SAMPLES = 32

    /**
     * 所有 Parser 实例合计最多保留的解析中、音频准备中或主线程待交付视频数。
     * 额度在开始读取前申请，交付、失败或取消清理后归还，防止后台不断产生待交付大对象。
     * 已交付给调用方的视频不计入此额度，仍需调用方及时释放；此数量上限不是进程总内存预算。
     */
    const val MAX_PENDING_VIDEOS = 2
}
