# R8 规则。
#
# 绝大部分都不用写：
#  - 四大组件（Activity / Service / Receiver）由 AGP 从 AndroidManifest 自动生成 keep；
#  - XML 里引用的自定义 View（PieChartView）由 proguard-android-optimize.txt 的
#    「保留 (Context, AttributeSet) 构造器」规则覆盖；
#  - org.json 是框架自带的，不参与裁剪。
#
# 这个应用没有反射、没有基于注解的序列化、没有 JNI，所以下面只留必要的两条。

# 崩溃栈要能还原成源码行号。混淆后行号会被打乱，没有这两行就只能看到
# a.a.a(Unknown Source)——自用应用没有崩溃上报，全靠 logcat 看，这个必须留。
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
