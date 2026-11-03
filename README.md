# 面试博弈助手 · Android 工程（第一轮：最小音频诊断 APK）

依据：《面试博弈助手_01_APK构建与FindN3真机测试规范》V1.0
本工程为 **P0 第一步「最小 Android 音频诊断 APK」**，仅包含音频诊断（Test A–D），**不接入讯飞 / DeepSeek / 知识库，不含任何 API Key**。

## 构建

```bash
export JAVA_HOME=/Users/macbook/DoubaoWork/tools/jdk-21.0.13+9/Contents/Home
export ANDROID_HOME=/Users/macbook/DoubaoWork/tools/android-sdk
export PATH=$JAVA_HOME/bin:$PATH
./gradlew assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`

## 诊断项（规范 01 §6）

| 测试 | 内容 | 说明 |
|---|---|---|
| A | 麦克风录音 10s → 保存私有目录 → AudioTrack 回放 | 权限/输入设备/RMS/回放人工确认 |
| B | 本机 TextToSpeech 中文播报 | 初始化/中文可用性/回调打点 |
| C | 同时录音 + TTS | 录音中断/路由观察 |
| D | 稳定性 2/5/10/30 分钟 | 中断/崩溃/内存 |

## 版本记录

- v0.1.0：第一轮诊断版（本 commit）
- 已知限制：Activity 前台模式（onPause 自动停止录音）；后台持续录音需前台服务（正式版 P0 迭代）
