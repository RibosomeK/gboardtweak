# Gboard 数字布局半角标点

一个只针对 **Gboard 中文九宫格** 的 LSPosed 模块：在九宫格的数字布局下，把左右两侧的逗号、句号提交为半角 `,` 和 `.`；普通九宫格、其他键盘和其他语言保持原样。

目标环境：

- Android 16（SDK 36）
- Gboard `18.5.4.993608717-beta-arm64-v8a`
- LSPosed（传统 Xposed API）
- 中文拼音九宫格

## 行为边界

| 场景 | 逗号 | 句号 |
| --- | --- | --- |
| 中文九宫格普通布局 | `，` | `。` |
| 中文九宫格数字布局（按右下角上箭头） | `,` | `.` |
| 其他键盘 / 其他语言 / 其他符号 | 不干预 | 不干预 |

模块只改最终提交的字符，不改键盘外观、不改布局资源、不处理输入框中已有文本。

## 对原开发方案的修正

原方案的核心方向（LSPosed + 拦截提交 + 判断数字布局）是对的，但有几处可以显著简化或修正：

1. **不需要逆向上箭头对应的按键事件和状态变量。**
   逆向 APK 后确认，中文九宫格的数字页是独立的 Keyboard 定义（IME 资源中的 `type="digit"`），由命名类
   `com.google.android.apps.inputmethod.latin.keyboard.LatinDigitsKeyboard` 渲染。直接跟踪
   `Keyboard.onActivate/onDeactivate`（方法名 `e` / `f`）即可准确知道当前是否在数字键盘，无需猜测混淆字段。

2. **不需要只 Hook `RemoteInputConnection` 的多个重载来“碰运气”。**
   主路径改为在 `InputMethodService.getCurrentInputConnection()` 返回时，动态发现当前 InputConnection 的具体类，
   再 Hook 它的 `commitText` / `setComposingText`。这类方法的名称属于 Android 稳定 API，跨 Gboard 版本更可靠。
   同时保留 Gboard 18.5.4 的 `aekp.q` / `aemg.b` 作为版本专用兜底。

3. **中文语言判断用 InputMethodSubtype 的 language tag。**
   数字键盘不只是中文九宫格会用，日文等语言也可能用数字布局。转换条件收紧为：
   当前激活键盘是 `LatinDigitsKeyboard` + 当前 IME subtype 为中文 + 提交文本恰好是单个 `，` 或 `。`。

4. **精确字符映射，而不是全角区间转换。**
   `，` 是 U+FF0C，`。` 是 U+3002；中文句号不在 U+FF01–U+FF5E 区间内。只做两个精确映射，避免误伤。

5. **状态重置放在键盘生命周期里。**
   键盘隐藏、切换输入法、切换应用时由键盘的 deactivate 事件清理状态；不做基于输入框内容的猜测。

## 逆向结论（Gboard 18.5.4.993608717）

- 中文九宫格 IME 定义：`res/yn1.xml`（`ime_zh_cn_9key`）
- 数字键盘定义：`res/EfW.xml` → `res/rb7.xml`
  - 类：`com.google.android.apps.inputmethod.latin.keyboard.LatinDigitsKeyboard`
- 数字页中的逗号/句号键：`res/LHK.xml`（`COMMA` / `PERIOD`，data 为 `,` / `.`），中文 IME 处理后以全角提交
- 键盘基类：`com.google.android.libraries.inputmethod.keyboard.impl.Keyboard`
  - `e(EditorInfo, Object)`：激活
  - `f()`：停用
- Gboard 18.5.4 的提交包装：
  - `aekp.q(CharSequence, int)`：`InputConnectionWrapper.commitText`
  - `aemg.b(aemw, CharSequence, int)`：实际入队到 `InputConnection.commitText`

## 工程结构

```
app/src/main/java/dev/ria/gboardtweak/
├── MainHook.kt                  LSPosed 入口
├── Constants.kt                 包名、类名、调试开关
├── DebugLog.kt                  日志
├── PunctuationTransformer.kt    精确字符映射
├── KeyboardStateTracker.kt      跟踪当前激活 Keyboard
├── ImeState.kt                  当前 IME subtype / 语言
└── InputConnectionHooks.kt      提交路径 Hook 与转换
```

构建工具链：JDK 21、Android SDK Platform 36、Build-Tools 36.0.0、Gradle 8.13。

## 构建与安装

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## LSPosed 启用

1. 打开 LSPosed 管理器 → 模块。
2. 启用 **Gboard 数字布局半角标点**。
3. 作用域勾选 **Gboard**（`com.google.android.inputmethod.latin`）。
4. 强制停止 Gboard，或重启手机。

模块清单已通过 `xposedscope` 预声明 Gboard 作用域；部分 LSPosed 版本仍需手动确认。

## 测试清单

准备：在任意应用中调出 Gboard → 中文 → 拼音九宫格。

- [ ] 普通九宫格按 `，`：仍输出 `，`
- [ ] 普通九宫格按 `。`：仍输出 `。`
- [ ] 按右下角上箭头进入数字布局
- [ ] 数字布局左侧逗号：输出 `,`
- [ ] 数字布局右侧句号：输出 `.`
- [ ] 再按返回普通九宫格：恢复 `，` / `。`
- [ ] 切到英文或日文输入：行为不变
- [ ] 隐藏键盘后重新打开：行为仍正确

抓日志：

```bash
adb logcat -c
adb logcat -s GboardTweak
```

日志会记录键盘激活、数字键盘判定、中文 IME 判定，以及目标标点的提交路径。

## 已知限制

- 主 Hook（InputConnection 动态发现）跨 Gboard 版本相对稳定；`aekp/aemg` 兜底仅针对 18.5.4，Gboard 更新后可能失效，但不会导致崩溃。
- 只转换恰好为单个 `，` 或 `。` 的提交，不处理包含多个字符的字符串。
- 语言 tag 读取失败时按“允许”处理，仍受“数字键盘 + 精确字符”双重限制。
- `analysis/` 目录保存本地 APK 和反编译结果，不纳入 git。
