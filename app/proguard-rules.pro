# VoiceClear ProGuard rules
# Model engine adapters are loaded reflectively in later phases; keep their entry points.
-keep class com.voiceclear.app.engine.** { *; }
