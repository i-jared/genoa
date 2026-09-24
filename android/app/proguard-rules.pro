-keepattributes Signature,InnerClasses,EnclosingMethod
# Crashlytics: keep line numbers (deobfuscated with the uploaded mapping) and exception names.
-keepattributes SourceFile,LineNumberTable
-keep public class * extends java.lang.Exception
