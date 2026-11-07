# Add project specific ProGuard rules here.
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

-keep class io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel { *; }
-keepnames class io.github.jqssun.gpssetter.ui.viewmodel.MainViewModel.**

-keep class io.github.jqssun.gpssetter.xposed.** { *; }
-keepnames class io.github.jqssun.gpssetter.xposed.** { *; }

-keep class io.github.jqssun.gpssetter.utils.AppLogger { *; }
-keep class io.github.jqssun.gpssetter.utils.LogContentProvider { *; }
-keep class io.github.jqssun.gpssetter.utils.LogBroadcastReceiver { *; }
-keep class io.github.jqssun.gpssetter.utils.PrefManager { *; }

-keep class de.robv.android.xposed.** { *; }
-keepnames class de.robv.android.xposed.** { *; }
