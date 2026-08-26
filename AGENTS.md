# AutoSkip 项目协作指南

## 项目概览

- 本项目是基于 Shizuku 授权和 Android `UiAutomation` 的自动跳过启动广告工具。
- 项目使用 Groovy Gradle、Kotlin 1.5.30、Android Gradle Plugin 7.0.2、Gradle 7.0.2 和 JDK 11。
- Android 配置为 `compileSdk 30`、`targetSdk 30`、`minSdk 23`；除非任务明确要求，不要顺手升级 Gradle、Kotlin、SDK 或依赖版本。

## 仓库结构

- `app/`：Android 应用模块，包含界面、ViewModel、开机自启、统计与测试页面。
- `automator/`：运行在 Shizuku 特权进程中的自动化服务、AIDL 接口、结果模型与记录持久化。
- `automator/hidden-apis/`：编译期使用的 Android 隐藏 API 声明；不要把它当作普通运行时实现。
- `app/src/main/res/`：布局、字符串、主题、菜单及图片资源。
- `.github/workflows/android.yml`：JDK 11 下执行 `assembleDebug` 的 CI 构建流程。

## 修改原则

- 先定位最小影响范围，保持现有包名、模块边界和 Kotlin/Java 8 兼容性。
- Kotlin 代码沿用现有 4 空格缩进和邻近代码风格；优先使用已有工具函数、资源和生命周期组件。
- 面向用户的文字放入 `app/src/main/res/values/strings.xml`，不要在界面代码中新增可本地化的硬编码文案。
- 修改 AIDL、Parcelable 模型或 Binder 方法时，同步检查 `.aidl` 声明、Kotlin 实现、客户端调用和混淆规则。
- 修改 `UiAutomation`、隐藏 API、Shizuku 服务绑定、输入注入或开机自启逻辑时，明确考虑 Android 6（API 23）及不同系统版本的兼容分支。
- 不提交密钥库、密码、真实联系信息或其他凭据；`sign.properties` 已被忽略。

## 本地构建准备

应用模块依赖未跟踪的常量文件。构建前创建：

`app/src/main/java/top/xjunz/automator/Constants.kt`

```kotlin
package top.xjunz.automator

const val ALIPAY_DONATE_URL = "xxx"
const val EMAIL_ADDRESS = "xxx"
const val APP_DOWNLOAD_URL = "xxx"
const val FEEDBACK_GROUP_URL = "xxx"
```

未提供 `sign.properties` 时，`sign.gradle` 会回退到默认 debug 签名。需要自定义签名时，按 `README.md` 配置本地文件，禁止提交真实签名材料。

## 构建与验证

在 Windows PowerShell 中使用仓库自带 Wrapper：

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat testDebugUnitTest
.\gradlew.bat connectedDebugAndroidTest
```

- 根据改动范围运行最小相关任务；提交前至少执行可用的单元测试或 `assembleDebug`。
- `connectedDebugAndroidTest` 需要已连接的设备或模拟器。Shizuku 授权、服务常驻、开机自启、广告识别和模拟点击必须在真实设备上验收，不能仅凭编译通过宣称功能正常。
- Gradle 配置会调用 `incrementRefine()`，每次构建都可能改写已跟踪的 `custom.properties`。构建后检查 `git diff -- custom.properties`；除非任务需要发布版本号，否则不要把该自动递增混入功能改动。
- 最后运行 `git diff --check`，并检查 `git status --short`，避免提交构建产物、本地配置或无关改动。

## 测试约定

- JVM 单元测试放在对应模块的 `src/test/`，设备测试放在 `src/androidTest/`。
- 自动跳过规则变更应覆盖：候选文本、节点可见性/可编辑性、横竖屏位置与尺寸、可点击父节点、输入事件回退，以及重复计数行为。
- 修复兼容性问题时，记录受影响的 Android 版本、Shizuku/Sui 环境和人工复现步骤。

## Git 与交付

- 保留用户已有的未提交改动，不覆盖或回退与当前任务无关的文件。
- 提交应聚焦单一主题；不要使用 `git add .` 或 `git add -A`，应显式暂存本次修改的文件。
- 交付说明需列出修改内容、已执行的检查，以及仍需用户在 Android Studio、模拟器或真机完成的验证。
