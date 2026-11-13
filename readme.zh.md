# SVGAPlayer

## 介绍

`SVGAPlayer` 是一个轻量的动画渲染库。你可以使用[工具](http://svga.io/designer.html)从 `Adobe Animate CC` 或者 `Adobe After Effects` 中导出动画文件，然后使用 `SVGAPlayer` 在移动设备上渲染并播放。

`SVGAPlayer-Android` 使用原生 Android Canvas 库渲染动画，为你提供高性能、低开销的动画体验。

如果你想要了解更多细节，请访问[官方网站](http://svga.io/)。

## 用法

### 本地构建

使用 JDK 21，Android Studio 中的 Gradle JDK 也选择 JDK 21。项目通过 Gradle Wrapper 使用 Gradle 8.7、Android Gradle Plugin 8.6.1 和 Kotlin 2.1.21。

Android SDK 需要安装 Android 15（API 35）和 Build Tools 34.0.0，并在项目根目录的 `local.properties` 中配置 `sdk.dir`。compileSdk 和 targetSdk 均为 35，最低支持 Android 5.0（API 21）。

```sh
./gradlew :app:assembleDebug :library:assembleRelease
```

示例 APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`，库 AAR 输出到 `library/build/outputs/aar/library-release.aar`。Gradle 使用 JDK 21 运行，Java/Kotlin 编译目标为 Java 8。

### 生命周期感知播放

需要随界面显示、隐藏自动播放或暂停时，使用 `LifecycleSVGAImageView`：

```xml
<com.opensource.svgaplayer.LifecycleSVGAImageView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="200dp"
    android:layout_height="200dp"
    app:source="rose_2.0.0.svga"
    app:autoPlay="true" />
```

View 会优先使用显式设置的 `lifecycleOwner`，其次查找 View 树的 LifecycleOwner，最后查找 Context 中的 LifecycleOwner。找到 owner 后，只有生命周期至少为 `STARTED`、View 已挂载且自身、父容器、窗口均可见时才播放。没有 owner 的普通 Activity 中，仍根据 View 和窗口可见性暂停、恢复。库通过 AndroidX Lifecycle Runtime 2.6.2 提供这些能力。

在 Fragment 中，通常会自动找到 View 树的 owner；自定义容器没有设置 owner 时，可以显式绑定：

```kotlin
animationView.lifecycleOwner = viewLifecycleOwner
animationView.setVideoItem(videoItem) // 默认自动播放；暂时不可见时保持暂停
```

ViewPager2 等会将缓存页面保留在 `STARTED` 状态的容器中，可以设置 `animationView.minActiveState = Lifecycle.State.RESUMED`，仅让当前活跃页面播放。

示例应用首页选择「LifecycleSVGAImageView 生命周期测试」，可查看实时生命周期、播放状态、当前帧与最近暂停帧。页面提供隐藏父容器、手动暂停/恢复、移除/重建 View 和打开另一页的操作；移除后会检查 View 是否已清空素材。也可以按 Home 再返回，观察后台暂停及恢复。

界面隐藏或收到 `ON_STOP` 时，暂停动画和音频并保留进度；再次可见时继续原有播放区间、方向及循环进度。主动调用 `pauseAnimation()` 后不会自动恢复，需要调用 `resumeAnimation()`；`stopAnimation()` 停止后需要重新 `startAnimation()`。设置 `app:autoPlay="false"` 可关闭新素材自动播放，手动开始的动画仍受可见性和生命周期控制。有限次数动画正常结束后，不会因重新显示而重播。

View 移出窗口或 owner 收到 `ON_DESTROY` 时，会取消 View 自己发起的加载、释放视频/动态资源/绘制缓存、清理业务回调并移除生命周期观察者。这个新 View 始终在 detach 时释放资源，即使将继承的 `clearsAfterDetached` 设为 `false`。RecyclerView 中复用或重新挂载后，需要重新绑定素材；也可以在 `onViewRecycled` 中显式调用 `clear()`。原有 `SVGAImageView` 继续支持手动控制，并新增保留进度的暂停和恢复接口。

### 大素材与资源释放

位图始终先读取尺寸，再按显示尺寸和内存预算采样，保留透明通道。默认单张贴图最多占 16 MiB，单个视频的贴图总量最多 32 MiB；低内存设备还会分别收紧到应用堆上限的 1/16、1/8。单边最多 4096 像素。超大素材可能降低分辨率，无法容纳的素材通过 `ParseCompletion.onError()` 报错。动态网络图片同样有单张与总量预算，调用方直接传入的 Bitmap 仍由调用方控制大小。

下载上限为 32 MiB（同时受堆上限的 1/8 约束），解压数据上限为 64 MiB（同时受堆上限的 1/4 约束）。默认最多同时执行 2 个后台任务，等待队列最多 32 个任务。重复音频事件复用采样，每个视频最多加载 32 个不同音频片段。音频准备最多等待 30 秒，超时后继续交付视频，避免无限保留解析回调。

结构预算在解码过程中检查，而不是等对象全部创建后再检查。每份素材最多包含 `min(50,000, 应用堆上限字节数 / 2048)` 个 Protobuf 消息或 JSON 容器，字段/值数量最多为该额度的 8 倍，嵌套深度最多 64 层；JSON 使用流式读取构造对象。帧、图层、形状等累计超限时，通过 `onError()` 拒绝素材，即使压缩文件很小也一样。示例 `mp3_to_long.svga` 的帧实体数量超过预算，会被拒绝。

遮罩按可见裁剪区域绘制，颜色缓冲与透明度缓冲合计最多占 `min(16 MiB, 应用堆上限 / 16)`，同时检查实际分配量。超出预算或分配失败时继续降采样，再放大到目标区域；大画布上的遮罩内容可能降低分辨率。绘制过程不再为遮罩创建目标画布大小的 `saveLayer()`。

所有 Parser 实例合计最多保留 2 个尚未交付的视频，覆盖解析、音频准备及主线程待交付阶段，即使替换线程池也生效。额度不足时后台会在读取素材前等待；交付、失败或取消清理完成后归还额度。取消会移除待交付消息并释放其素材，无完成回调的结果直接清理。已交付的视频由调用方管理，不计入此限制。

`SVGAImageView.clearsAfterDetached` 默认改为 `true`，移出窗口时释放素材并取消加载。需要保留播放器供重新挂载时，可显式设为 `false`，并在最终不再使用时调用 `clear()`。替换视频或通过 `setImageDrawable`、`setImageBitmap`、`setImageResource`、`setImageURI` 设置图片时，会停止旧动画、释放旧 Drawable 并取消待完成的加载；共享同一个视频或动态实体时，仍须协调多个播放器的释放时机。

`clear()` 是最终释放操作：停止动画、断开回调、清理素材和绘制缓存。动态图片清理或同 key 替换时，会移除待交付消息，并立即回收尚未发布的 Bitmap；正在解码的请求保留预算到工作线程退出，防止提前归还额度导致并发超额分配。已经发布或由调用方传入的 Bitmap 不会被强制 `recycle()`，以保护共享使用。原始 `movieItem` 在准备完成前即从视频实体中移除。全局音频不再使用时调用 `SVGASoundManager.release()`，该方法会真正释放 SoundPool，可再次 `init()`。

回归测试：`./gradlew :library:testDebugUnitTest`，其中遮罩和 PNG 解码使用 Robolectric 原生图形模式。

真机压力测试使用独立测试 APK，不修改示例 APK。将下列 `SERIAL` 替换为 `adb devices` 中的设备编号：

```sh
./gradlew :library:assembleDebugAndroidTest
adb -s SERIAL install -r -t library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r com.opensource.svgaplayer.test/com.opensource.svgaplayer.SVGAMemoryStressInstrumentation
adb -s SERIAL exec-out run-as com.opensource.svgaplayer.test cat files/svga-memory-stress.json > library/build/reports/svga-memory-stress.json
```

测试在预热后执行 240 次加载/绘制/View 分离、600 次解析取消、600 次 4 MiB 动态图片待交付取消、360 帧最大 4096×2160 遮罩绘制，以及 200 次超限结构拒绝。报告包含阶段耗时、PSS/Java/Native 堆采样、遮罩实际分配量，以及 GC 后 View/视频实体的弱引用存活数。GC 后样本用于观察持续增长；采样峰值不等于瞬时峰值，也不能替代其他机型和长期运行验证。测试 Activity 仅存在于测试 APK，通过显式 shell 启动以兼容后台启动限制。

我们在这里介绍 `SVGAPlayer-Android` 的用法。想要知道如何导出动画，点击[这里](http://svga.io/designer.html)。

### 使用 Gradle 安装

此 fork 的 AAR 发布到 JitPack。将仓库添加到工程的依赖仓库中（新工程通常在 `settings.gradle`）：

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

然后，在应用 `build.gradle` 中添加依赖。

```groovy
implementation 'com.github.TooWhiteT:SVGAPlayer-Android-fix:v1.0.0'
```

[![](https://jitpack.io/v/TooWhiteT/SVGAPlayer-Android-fix.svg)](https://jitpack.io/#TooWhiteT/SVGAPlayer-Android-fix/v1.0.0)

仓库通过 `jitpack.yml` 使用 JDK 17，仅发布 `library` 的 release AAR、源码 JAR 和依赖元数据。版本号来自 JitPack 的标签/提交版本；本地可通过 `-PreleaseVersion` 指定：

```sh
./gradlew :library:publishReleasePublicationToMavenLocal -PreleaseVersion=v1.0.0
```

### Parser 单例支持
SVGAParser 单例需要在使用之前初始化，
否则会上报错误信息：
`Log.e("SVGAParser", "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")`


### 遮罩支持
请参阅此处 [Dynamic · Matte Layer](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-%C2%B7-Matte-Layer)

### 混淆规则

```
-keep class com.squareup.wire.** { *; }
-keep class com.opensource.svgaplayer.proto.** { *; }
```

### 放置 svga 文件

SVGAPlayer 可以从本地 `assets` 目录，或者远端服务器上加载动画文件。

### 使用 XML

你可以使用 `layout.xml` 添加一个 `SVGAImageView`。

```xml
<?xml version="1.0" encoding="utf-8"?>
<RelativeLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:orientation="vertical"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <com.opensource.svgaplayer.SVGAImageView
        android:layout_height="match_parent"
        android:layout_width="match_parent"
        app:source="posche.svga"
        app:autoPlay="true"
        android:background="#000" />

</RelativeLayout>
```

在 XML 中，允许定义以下这些标签：

#### source: String
用于表示 svga 文件的路径，提供一个在 `assets` 目录下的文件名，或者提供一个 http url 地址。

#### autoPlay: Boolean
默认为 `true`，当动画加载完成后，自动播放。

#### loopCount: Int
默认为 `0`，设置动画的循环次数，0 表示无限循环。

#### ~~clearsAfterStop: Boolean~~
默认为 `false`，当动画播放完成后，是否清空画布，以及 SVGAVideoEntity 内部数据。
不再推荐使用，开发者可以通过 clearAfterDetached 控制资源释放，或者手动通过 SVGAVideoEntity#clear 控制资源释放

#### clearsAfterDetached: Boolean
默认为 `true`，当 SVGAImageView 触发 onDetachedFromWindow 方法时，是否释放素材。`LifecycleSVGAImageView` 始终在 detach 时释放。

#### fillMode: String

默认为 `Forward`，可以是 `Forward`、 `Backward`、 `Clear`。

`Forward` 表示动画结束后，将停留在最后一帧。

`Backward` 表示动画结束后，将停留在第一帧。

`Clear` 表示动画播放完后，清空所有画布内容，但仅仅是画布，不涉及 SVGAVideoEntity 内部数据。

### 使用代码

也可以使用代码添加 `SVGAImageView`。

#### 创建一个 `SVGAImageView` 实例

```kotlin
SVGAImageView imageView = new SVGAImageView(this);
```

#### 声明一个 `SVGAParser` 单例.

```kotlin
parser = SVGAParser.shareParser()
```

#### 初始化 `SVGAParser` 单例

必须在使用 `SVGAParser` 单例前初始化，
```
SVGAParser.shareParser().init(this);
```

否则会上报错误信息：
`Log.e("SVGAParser", "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")`

你也可以自行创建 `SVGAParser` 实例。

#### 创建一个 `SVGAParser` 实例，加载 assets 中的动画。

```kotlin
parser = new SVGAParser(this);
// 第三个为可缺省参数，默认为 null，如果设置该方法，则内部不在处理音频的解析以及播放，会通过 PlayCallback 把音频 File 实例回传给开发者，有开发者自行控制音频的播放与停止。
parser.decodeFromAssets("posche.svga", object : SVGAParser.ParseCompletion {
    // ...
}, object : SVGAParser.PlayCallback {
    // The default is null, can not be set
})
```

#### 创建一个 `SVGAParser` 实例，加载远端服务器中的动画。

```kotlin
parser = new SVGAParser(this);
// 第三个为可缺省参数，默认为 null，如果设置该方法，则内部不在处理音频的解析以及播放，会通过 PlayCallback 把音频 File 实例回传给开发者，有开发者自行控制音频的播放与停止。
parser.decodeFromURL(new URL("https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true"), new SVGAParser.ParseCompletion() {
    // ...
}, object : SVGAParser.PlayCallback {
    // The default is null, can not be set
})
```

#### 创建一个 `SVGADrawable` 实例，并赋值给 `SVGAImageView`，然后播放动画。

```kotlin
parser = new SVGAParser(this);
parser.decodeFromURL(..., new SVGAParser.ParseCompletion() {
    @Override
    public void onComplete(@NotNull SVGAVideoEntity videoItem) {
        SVGADrawable drawable = new SVGADrawable(videoItem);
        imageView.setImageDrawable(drawable);
        imageView.startAnimation();
    }
    @Override
    public void onError() {

    }
});
```

### 缓存

`SVGAParser` 不会管理缓存，你需要自行实现缓存器。

#### 设置 HttpResponseCache

`SVGAParser` 依赖 `URLConnection`, `URLConnection` 使用 `HttpResponseCache` 处理缓存。

添加代码至 `Application.java:onCreate` 以设置缓存。

```kotlin
val cacheDir = File(context.applicationContext.cacheDir, "http")
HttpResponseCache.install(cacheDir, 1024 * 1024 * 128)
```

### SVGALogger
更新了内部 log 输出，可通过 SVGALogger 去管理和控制，默认是未启用 log 输出，开发者们也可以实现 ILogger 接口，做到外部捕获收集 log，方便排查问题。
通过 `setLogEnabled` 方法设置日志是否开启。
通过 `injectSVGALoggerImp` 方法注入自定义 ILogger 实现类。

```kotlin

// 默认情况下，SVGA 内部不会输出任何 log，所以需要手动设置为 true
SVGALogger.setLogEnabled(true)

// 如果希望收集 SVGA 内部输出的日志，则可通过下面方式获取
SVGALogger.injectSVGALoggerImp(object: ILogger {
// 实现相关接口进行接收 log
})
```

### SVGASoundManager
新增 SVGASoundManager 控制 SVGA 音频，需要手动调用 init 方法进行初始化，否则按照默认的音频加载逻辑。
另外通过 SVGASoundManager#setVolume 可控制 SVGA 播放时的音量大小，范围值在 [0f, 1f]，默认控制所有 SVGA 播放时的音量，
而且该方法可设置第二个可缺省参数：SVGAVideoEntity，表示仅控制当前 SVGA 的音量大小，其他 SVGA 的音量保持不变。

```kotlin
// 初始化音频管理器，方便管理音频播放
// 如果没有初始化，则默认按照原有方式加载音频
SVGASoundManager.init()

// 释放音频资源
SVGASoundManager.release()

/**
* 设置音量大小，entity 默认为空
* 当 entity 为空，则控制所有通过 SVGASoundManager 加载的音频音量大小，即包括当前正在播放的音频以及后续加载的音频
* 当 entity 不为空，则仅控制该实例的 SVGA 音频音量大小，其他则不受影响
* 
* @param volume 取值范围为 [0f, 1f]
* @param entity 即 SVGAParser 回调回来的实例
*/
SVGASoundManager.setVolume(volume, entity)
```


## 功能示例

* [使用位图替换指定元素。](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Image)
* [在指定元素上绘制文本。](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Text)
* [在指定元素上绘制富文本。](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Text-Layout)
* [隐藏指定元素。](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Hidden)
* [在指定元素上自由绘制。](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Drawer)

## APIs

请参阅此处 [https://github.com/yyued/SVGAPlayer-Android/wiki/APIs](https://github.com/yyued/SVGAPlayer-Android/wiki/APIs)

## CHANGELOG

请参阅此处 [CHANGELOG](./CHANGELOG.md)
