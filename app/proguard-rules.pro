# media3 / exoplayer
-dontwarn androidx.media3.**
-keep class androidx.media3.** { *; }

# 反射创建的扩展模块（HLS/DASH/解码器）
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.extractor.** { *; }
-keep class androidx.media3.decoder.** { *; }

# 应用自身
-keep class com.ying.iptv.** { *; }

# 保留注解与内部类
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
