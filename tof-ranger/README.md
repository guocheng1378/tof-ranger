# ToF 测距仪

小米 17 Pro Max 专属 ToF 测距 App，读取 ST VL53Lx dToF 传感器数据。

## 功能

- 实时距离显示（mm / cm / m 自动切换）
- 传感器采样率显示
- 支持多区域 ToF 数据
- 降级支持 Proximity 传感器

## 构建

GitHub Actions 自动构建。Push 到 main 分支即可触发。

## 手动构建

```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```
