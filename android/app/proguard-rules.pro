# Chaquopy, AndroidX, and Play Services all ship their own consumer R8 rules
# bundled in their AARs, so no manual keep rules should be needed here in the
# common case. Add project-specific rules below only if a release build
# crashes with a stripped/missing-class error that debug builds don't show.

# WorkManager crashed on first launch of the R8-minified release build:
# "NoSuchMethodException: androidx.work.impl.WorkDatabase_Impl.<init>". Room
# generates WorkDatabase_Impl at compile time and WorkManager instantiates it
# reflectively; WorkManager's bundled consumer rules don't fully cover this
# under R8 full mode, so it needs an explicit keep here.
-keep class androidx.work.impl.** { *; }
