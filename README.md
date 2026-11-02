# Gboard 数字布局半角标点

作者：DeepSeek
许可证：[WTFPL](LICENSE)

一个针对 **Gboard 中文拼音九宫格** 的 LSPosed 模块：

- 普通九宫格保持全角 `，` / `。`
- 按右下角上箭头进入数字页后，逗号/句号按键**输出**半角 `,` / `.`
- 数字页的**键面字符**同步显示为 `,` / `.`，返回普通九宫格后恢复全角
- 其他键盘、其他语言、其他符号不干预

已在以下环境实机验证通过：

- Android 16（SDK 36），OnePlus CPH2769
- Gboard `18.5.4.993608717-beta-arm64-v8a`
- LSPosed（传统 Xposed API）
- 中文拼音九宫格

## 行为边界

| 场景 | 逗号 | 句号 |
| --- | --- | --- |
| 中文九宫格普通布局 | `，` | `。` |
| 中文九宫格数字布局（右下角上箭头） | `,` | `.` |
| 其他键盘 / 其他语言 / 其他符号 | 不干预 | 不干预 |

模块只修改最终提交的文本和 Gboard 自身的键面标签，不修改键盘布局资源，也不处理输入框中已有文本。

## 实现原理

### 1. 数字页识别

Gboard 的中文九宫格不是“同一个键盘换一套按键”那么简单，上箭头也不会创建新的
Keyboard 对象。中文九宫格由命名类
`com.google.android.apps.inputmethod.latin.keyboard.LatinKeyboardWithNumberMode`
渲染，数字页只是同一个键盘对象上的状态：

```text
普通九宫格  0x4201808400
数字页      0x64201808400   （新增 CAPS | CAPS_LOCK_MASK = 0x60000000000）
返回普通    0x4201808400
```

模块通过 `Keyboard.e()` / `Keyboard.f()` 跟踪当前激活的键盘对象，读取其状态字段
`F`；并 Hook `Keyboard.cF()` 监听状态变化。数字页判定条件为：

```text
激活键盘类 == LatinKeyboardWithNumberMode
且 (state & 0x60000000000) == 0x60000000000
```

### 2. 提交拦截

模块使用两条提交路径：

1. **主路径**：在 `InputMethodService.getCurrentInputConnection()` 返回时，运行时
   发现实际的 InputConnection 实现类，Hook 其 `commitText()`。方法名属于 Android
   稳定 API，不依赖 Gboard 的混淆类名。
2. **兜底路径**：Gboard 18.5.4 的 `aekp.q()`（内部
   `InputConnectionWrapper.commitText`）。这是本版本实机验证时实际生效的路径，
   位于框架 `commitText()` 之前。

转换条件必须同时满足：

- 当前键盘是中文九宫格数字页；
- 当前键盘名看起来是中文输入法（例如 `简体中文 (9 键)`）；
- 整段待提交文本恰好是单个 `，`（U+FF0C）或 `。`（U+3002）。

精确映射：`，` → `,`，`。` → `.`。不使用全角区间转换，因为中文句号 U+3002 不在
U+FF01–U+FF5E 区间内。

Android 16 的 `InputMethodService` 没有可用的 `getCurrentInputMethodSubtype()`，
因此中文判断使用 Gboard 键盘对象自己的本地化名称。

### 3. 键面标签同步

Gboard 在切换数字页时不会重新设置逗号/句号键的标签，键面会继续显示全角字符。
模块在初始化时跟踪 `SoftKeyView` 实例；进入/离开数字页时遍历其文本标签：

- 进入数字页：把恰好为 `，` / `。` 的标签改为 `,` / `.`，并记录原值；
- 离开数字页：把之前改过的标签恢复为全角。

只处理完整的单字符标签，不改候选词、弹窗或其他文本。

## 工程结构

```text
app/src/main/java/dev/ria/gboardtweak/
├── MainHook.kt                 LSPosed 入口
├── Constants.kt                包名、类名、状态掩码、调试开关
├── DebugLog.kt                 日志封装
├── PunctuationTransformer.kt   精确字符映射
├── KeyboardStateTracker.kt     跟踪激活键盘和数字页状态
├── ImeState.kt                 中文键盘判断
├── InputConnectionHooks.kt     提交拦截（主路径 + Gboard 兜底）
└── KeyLabelHooks.kt            数字页键面标签同步与恢复
```

模块清单使用传统 Xposed API，`assets/xposed_init` 指向 `MainHook`，并通过
`xposedscope` 预声明 Gboard 作用域。

## 构建

依赖：JDK 21、Android SDK Platform 36、Build-Tools 36.0.0、Gradle 8.13（项目自带
wrapper）。

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 启用

1. 打开 LSPosed 管理器 → 模块。
2. 启用 **Gboard 数字布局半角标点**。
3. 作用域勾选 **Gboard**（`com.google.android.inputmethod.latin`）。
4. 强制停止 Gboard，或重启手机。

## 验证清单

- [x] 普通九宫格按 `，`、`。`：保持全角
- [x] 上箭头进入数字页：输出 `,`、`.`
- [x] 数字页键面同步显示 `,`、`.`
- [x] 返回普通九宫格：键面和输出恢复 `，`、`。`
- [x] 英文键盘不受影响

## 已知限制

- 状态位、Keyboard 类名和 `aekp` 兜底均针对 Gboard 18.5.4 验证；Gboard 更新后
  可能需要重新适配。主 InputConnection Hook 相对更稳定。
- 只处理恰好为单个 `，` 或 `。` 的提交，不处理包含在更长字符串中的标点。
- 仅覆盖中文九宫格上箭头数字页；独立数字键盘（`LatinDigitsKeyboard`）不在范围内。
- 中文判断依赖 Gboard 键盘名中的语言标记词；若系统语言或 Gboard 版本改变了键盘名，
  需要调整 `ImeState` 中的标记。
- `Constants.DEBUG` 默认关闭；排查问题时改为 `true` 可看到键盘状态与转换日志
  （`adb logcat -s GboardTweak`）。

## 许可证

本项目采用 WTFPL（Do What The Fuck You Want To Public License）发布，详见
[LICENSE](LICENSE)。
