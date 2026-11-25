# 保留行号便于阅读堆栈；凭据不写入代码，故无需额外 keep 规则。

# BouncyCastle 的 SCrypt 通过类名直接引用；保留其实现，避免 R8 误删。
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
# Tink/security-crypto 的编译期注解，不在运行时依赖
-dontwarn com.google.errorprone.annotations.**
