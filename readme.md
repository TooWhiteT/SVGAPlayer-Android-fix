# Archived
本仓库已经停止维护，你仍然继续阅读源码及创建分叉，但本仓库不会继续更新，也不会回答任何 issue。

This repo has stopped maintenance, you can still continue to read the source code and create forks, but this repo will not continue to be updated, nor will it answer any issues.

# SVGAPlayer

[简体中文](./readme.zh.md)

## 支持本项目

1. 轻点 GitHub Star，让更多人看到该项目。

## Introduce

SVGAPlayer is a light-weight animation renderer. You use [tools](http://svga.io/designer.html) to export `svga` file from `Adobe Animate CC` or `Adobe After Effects`, and then use SVGAPlayer to render animation on mobile application.

`SVGAPlayer-Android` render animation natively via Android Canvas Library, brings you a high-performance, low-cost animation experience.

If wonder more information, go to this [website](http://svga.io/).

## Usage

### Build locally

Use JDK 21 and select it as the Gradle JDK in Android Studio. The Gradle Wrapper uses Gradle 8.7, Android Gradle Plugin 8.6.1, and Kotlin 2.1.21.

Install Android SDK Platform 35 and Build Tools 34.0.0, and set `sdk.dir` in the root `local.properties` file. Both compileSdk and targetSdk are 35; the minimum supported Android version is 5.0 (API 21).

```sh
./gradlew :app:assembleDebug :library:assembleRelease
```

The sample APK is generated at `app/build/outputs/apk/debug/app-debug.apk`, and the library AAR at `library/build/outputs/aar/library-release.aar`. Gradle runs on JDK 21; Java and Kotlin sources target Java 8.

### Lifecycle-aware playback

Use `LifecycleSVGAImageView` to pause and resume with screen visibility:

```xml
<com.opensource.svgaplayer.LifecycleSVGAImageView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="200dp"
    android:layout_height="200dp"
    app:source="rose_2.0.0.svga"
    app:autoPlay="true" />
```

The View resolves an explicitly assigned `lifecycleOwner`, then the View tree owner, then a Context owner. Playback requires an attached, visible View, visible ancestors/window, and an owner state of at least `STARTED`. Without an owner, View/window visibility still controls playback. This feature adds AndroidX Lifecycle Runtime 2.6.2.

Fragments normally supply their View tree owner automatically. For custom containers without an owner, bind the Fragment's View lifecycle explicitly:

```kotlin
animationView.lifecycleOwner = viewLifecycleOwner
animationView.setVideoItem(videoItem) // Autoplays when visible; otherwise stays paused.
```

For containers such as ViewPager2 that keep cached pages `STARTED`, set `animationView.minActiveState = Lifecycle.State.RESUMED` to play only in the active page.

Open “LifecycleSVGAImageView 生命周期测试” in the sample app to inspect lifecycle/playback state, current frame and the last paused frame. Controls hide the parent, manually pause/resume, remove/recreate the View, and open another page. Removing the View checks whether its drawable was cleared. Pressing Home and returning also exercises background pause/resume.

Hiding the View or receiving `ON_STOP` pauses both animation and audio. Becoming visible resumes the same position, range, direction and loop progress. An explicit `pauseAnimation()` remains paused until `resumeAnimation()`; after `stopAnimation()`, call `startAnimation()` again. `app:autoPlay="false"` disables autoplay for newly assigned videos; manually started animations still follow visibility/lifecycle changes. Completed finite animations do not restart when shown again.

Detaching or receiving `ON_DESTROY` cancels View-owned loads, releases video/dynamic resources and drawing caches, clears callbacks, and removes the lifecycle observer. This new View always releases on detach, including when the inherited `clearsAfterDetached` is false. Bind a new video when reusing a recycled or reattached View; `clear()` may also be called explicitly in `onViewRecycled`. The original `SVGAImageView` retains manual control and gains position-preserving pause/resume methods.

### Large assets and cleanup

Images are decoded with bounds checks, alpha preservation, and sampling. A bitmap is limited to 16 MiB (or 1/16 of the app heap), and a video's textures to 32 MiB (or 1/8 of the heap), whichever is smaller; maximum decoded dimension is 4096 pixels. Large assets can lose resolution. Network replacement images have equivalent per-image and aggregate limits; callers remain responsible for the size of directly supplied Bitmaps. Unloadable video assets report `ParseCompletion.onError()`.

Downloads are limited to 32 MiB or 1/8 of the heap, and expanded data to 64 MiB or 1/4 of the heap. The default executor runs two tasks with a queue of 32. Repeated audio events share samples; each video supports up to 32 distinct audio segments. Audio preparation has a 30-second fallback to deliver the video instead of retaining completion callbacks indefinitely.

Structural limits are enforced during decoding, before allocating the full object tree. Each asset allows at most `min(50,000, max heap bytes / 2048)` Protobuf messages or JSON containers, eight times that many fields/values, and 64 nesting levels. JSON is built from a streaming reader. Assets exceeding the cumulative budget for frames, sprites, shapes, or other structures report `onError()`, regardless of compressed file size. The sample `mp3_to_long.svga` exceeds this frame-object budget and is rejected.

Matte rendering uses the visible clip bounds. The color and alpha buffers share a budget of `min(16 MiB, max heap / 16)`, verified against their actual allocations. Large regions and allocation failures trigger further sampling; the result is scaled back to its destination, so large matte content can lose resolution. Matte rendering no longer creates a destination-sized `saveLayer()`.

All parser instances share a limit of two undelivered videos, covering parsing, audio preparation, and main-thread delivery, even with a custom executor. Workers wait for capacity before reading assets; delivery, failure, or completed cancellation cleanup returns capacity. Cancellation removes pending delivery messages and clears their videos. Results without completion callbacks are cleared immediately. Delivered videos belong to the caller and are outside this limit.

For a potentially blocking stream, use `val cancel = parser.decodeFromInputStreamWithCancel(input, key, callback, closeInputStream = true)` and call `cancel()` when leaving the screen. Both stream APIs take ownership when `closeInputStream = true`. If a read stalls for 20 seconds, the parser closes the stream and reports `onError()`. A custom stream must support waking a blocked `read()` through `close()`. Explicit cancellation closes the owned stream and removes pending callbacks. With the default `false`, the caller owns the stream and must configure a read timeout or close it to unblock a read that ignores interruption.

`SVGAImageView.clearsAfterDetached` now defaults to `true`. Set it to `false` only when retaining a player for reattachment, and call `clear()` when finally disposing it. Replacing video or calling `setImageDrawable`, `setImageBitmap`, `setImageResource`, or `setImageURI` stops the old animation, releases the old drawable, and cancels pending loads. Coordinate cleanup when sharing video/dynamic entities between players. `clear()` stops animation and drops callbacks and caches. Clearing dynamic images or replacing the same key removes queued deliveries and immediately recycles unpublished Bitmaps. Active decoders retain their reservations until the worker exits, preventing premature refunds from exceeding the memory budget. Published/caller-supplied Bitmaps are not forcibly recycled. The original `movieItem` is dropped during preparation. Call `SVGASoundManager.release()` when global audio is no longer needed; it releases the native pool and supports later initialization.

Run regression checks with `./gradlew :library:testDebugUnitTest`; bitmap/mask tests use Robolectric native graphics.

Device stress tests run in a separate test APK. Replace `SERIAL` with the device ID from `adb devices`:

```sh
./gradlew :library:assembleDebugAndroidTest
adb -s SERIAL install -r -t library/build/outputs/apk/androidTest/debug/library-debug-androidTest.apk
adb -s SERIAL shell am instrument -w -r com.opensource.svgaplayer.test/com.opensource.svgaplayer.SVGAMemoryStressInstrumentation
adb -s SERIAL exec-out run-as com.opensource.svgaplayer.test cat files/svga-memory-stress.json > library/build/reports/svga-memory-stress.json
```

After warmup, the runner performs 240 load/render/detach cycles, 600 parse cancellations, 600 cancellations of queued 4 MiB dynamic images, 360 matte frames up to 4096×2160, and 200 oversized-structure rejections. Its JSON report records phase durations, PSS/Java/native heap samples, actual matte allocations, and weak-reference survivors after GC. Post-GC samples help identify persistent growth; sampled peaks are not instantaneous peaks or a substitute for longer runs and other devices. The Activity exists only in the test APK and uses an explicit shell launch to support devices restricting background starts.

Here introduce `SVGAPlayer-Android` usage. Wonder exporting usage? Click [here](http://svga.io/designer.html).

### Install Via Gradle

This fork publishes its AAR on JitPack. Add the repository to your dependency repositories (usually in `settings.gradle` for new projects):

```groovy
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

Then, add dependency to app `build.gradle`.

```groovy
implementation 'com.github.TooWhiteT:SVGAPlayer-Android-fix:v1.0.1'
```

[![](https://jitpack.io/v/TooWhiteT/SVGAPlayer-Android-fix.svg)](https://jitpack.io/#TooWhiteT/SVGAPlayer-Android-fix/v1.0.1)

`jitpack.yml` selects JDK 17 and publishes only the library's release AAR, sources JAR and dependency metadata. The version comes from JitPack's tag/commit version; override it locally with `-PreleaseVersion`:

```sh
./gradlew :library:publishReleasePublicationToMavenLocal -PreleaseVersion=v1.0.1
```

### Static Parser Support
Perser#shareParser should be init(context) in Application or other Activity.
Otherwise it will report an error:
`Log.e("SVGAParser", "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")`


### Matte Support
Head on over to [Dynamic · Matte Layer](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-%C2%B7-Matte-Layer)

### Proguard-rules

```
-keep class com.squareup.wire.** { *; }
-keep class com.opensource.svgaplayer.proto.** { *; }
```

### Locate files

SVGAPlayer could load svga file from Android `assets` directory or remote server.

### Using XML

You may use `layout.xml` to add a `SVGAImageView`.

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

The following attributes is allowable:

#### source: String

The svga file path, provide a path relative to Android assets directory, or provide a http url.

#### autoPlay: Boolean

Defaults to `true`.

After animation parsed, plays animation automatically.

#### loopCount: Int

Defaults to `0`.

How many times should animation loops. `0` means Infinity Loop.

#### ~~clearsAfterStop: Boolean~~

Defaults to `false`.When the animation is finished, whether to clear the canvas and the internal data of SVGAVideoEntity.
It is no longer recommended. Developers can control resource release through clearAfterDetached, or manually control resource release through SVGAVideoEntity#clear

#### clearsAfterDetached: Boolean

Defaults to `true`. Releases the video resources after SVGAImageView detaches. `LifecycleSVGAImageView` always releases on detach.

#### fillMode: String

Defaults to `Forward`. Could be `Forward`, `Backward`, `Clear`.

`Forward` means animation will pause on last frame after finished.

`Backward` means animation will pause on first frame after finished.

`Clear` after the animation is played, all the canvas content is cleared, but it is only the canvas and does not involve the internal data of SVGAVideoEntity.

### Using code

You may use code to add `SVGAImageView` either.

#### Create a `SVGAImageView` instance.

```kotlin
SVGAImageView imageView = new SVGAImageView(this);
```

#### Declare a static Parser instance.

```kotlin
parser = SVGAParser.shareParser()
```

#### Init parser instance 

You should initialize the parser instance with context before usage.
```
SVGAParser.shareParser().init(this);
```

Otherwise it will report an error:
`Log.e("SVGAParser", "在配置 SVGAParser context 前, 无法解析 SVGA 文件。")`

You can also create `SVGAParser` instance by yourself.

#### Create a `SVGAParser` instance, parse from assets like this.

```kotlin
parser = new SVGAParser(this);
// The third parameter is a default parameter, which is null by default. If this method is set, the audio parsing and playback will not be processed internally. The audio File instance will be sent back to the developer through PlayCallback, and the developer will control the audio playback and playback. stop
parser.decodeFromAssets("posche.svga", object : SVGAParser.ParseCompletion {
    // ...
}, object : SVGAParser.PlayCallback {
    // The default is null, can not be set
})
```

#### Create a `SVGAParser` instance, parse from remote server like this.

```kotlin
parser = new SVGAParser(this);
// The third parameter is a default parameter, which is null by default. If this method is set, the audio parsing and playback will not be processed internally. The audio File instance will be sent back to the developer through PlayCallback, and the developer will control the audio playback and playback. stop
parser.decodeFromURL(new URL("https://github.com/yyued/SVGA-Samples/blob/master/posche.svga?raw=true"), new SVGAParser.ParseCompletion() {
    // ...
}, object : SVGAParser.PlayCallback {
    // The default is null, can not be set
})
```

#### Create a `SVGADrawable` instance then set to `SVGAImageView`, play it as you want.

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

### Cache

`SVGAParser` will not manage any cache, you need to setup cacher by yourself.

#### Setup HttpResponseCache

`SVGAParser` depends on `URLConnection`, `URLConnection` uses `HttpResponseCache` to cache things.

Add codes to `Application.java:onCreate` to setup cacher.

```kotlin
val cacheDir = File(context.applicationContext.cacheDir, "http")
HttpResponseCache.install(cacheDir, 1024 * 1024 * 128)
```

### SVGALogger
Updated the internal log output, which can be managed and controlled through SVGALogger. It is not activated by default. Developers can also implement the ILogger interface to capture and collect logs externally to facilitate troubleshooting
Set whether the log is enabled through the `setLogEnabled` method
Inject a custom ILogger implementation class through the `injectSVGALoggerImp` method


```kotlin

// By default, SVGA will not output any log, so you need to manually set it to true
SVGALogger.setLogEnabled(true)

// If you want to collect the output log of SVGA, you can obtain it in the following way
SVGALogger.injectSVGALoggerImp(object: ILogger {
// Implement related interfaces to receive log
})
```

### SVGASoundManager
Added SVGASoundManager to control SVGA audio, you need to manually call the init method to initialize, otherwise follow the default audio loading logic.
In addition, through SVGASoundManager#setVolume, you can control the volume of SVGA playback. The range is [0f, 1f]. By default, the volume of all SVGA playbacks is controlled.
And this method can set a second default parameter: SVGAVideoEntity, which means that only the current SVGA volume is controlled, and the volume of other SVGAs remains unchanged.

```kotlin
// Initialize the audio manager for easy management of audio playback
// If it is not initialized, the audio will be loaded in the original way by default
SVGASoundManager.init()

// Release audio resources
SVGASoundManager.release()

/**
* Set the volume level, entity is null by default
* When entity is null, it controls the volume of all audio loaded through SVGASoundManager, which includes the currently playing audio and subsequent loaded audio
* When entity is not null, only the SVGA audio volume of the instance is controlled, and the others are not affected
* 
* @param volume The value range is [0f, 1f]
* @param entity That is, the instance of SVGAParser callback
*/
SVGASoundManager.setVolume(volume, entity)
```

## Features

Here are many feature samples.

* [Replace an element with Bitmap.](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Image)
* [Add text above an element.](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Text)
* [Add static layout text above an element.](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Text-Layout)
* [Hides an element dynamicaly.](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Hidden)
* [Use a custom drawer for element.](https://github.com/yyued/SVGAPlayer-Android/wiki/Dynamic-Drawer)

## APIs

Head on over to [https://github.com/yyued/SVGAPlayer-Android/wiki/APIs](https://github.com/yyued/SVGAPlayer-Android/wiki/APIs)

## CHANGELOG

Head on over to [CHANGELOG](./CHANGELOG.md)

## Credits

### Contributors

This project exists thanks to all the people who contribute. [[Contribute](CONTRIBUTING.md)].

<a href="https://github.com/yyued/SVGAPlayer-Android/graphs/contributors"><img src="https://opencollective.com/SVGAPlayer-Android/contributors.svg?width=890&button=false" /></a>

### Backers

Thank you to all our backers! 🙏 [[Become a backer](https://opencollective.com/SVGAPlayer-Android#backer)]

<a href="https://opencollective.com/SVGAPlayer-Android#backers" target="_blank"><img src="https://opencollective.com/SVGAPlayer-Android/backers.svg?width=890"></a>

### Sponsors

Support this project by becoming a sponsor. Your logo will show up here with a link to your website. [[Become a sponsor](https://opencollective.com/SVGAPlayer-Android#sponsor)]

<a href="https://opencollective.com/SVGAPlayer-Android/sponsor/0/website" target="_blank"><img src="https://opencollective.com/SVGAPlayer-Android/sponsor/0/avatar.svg"></a>

