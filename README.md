# Coursatrix

面向大学生活的原生 Android 课程表基础版。Kotlin · Jetpack Compose · Material 3 · Room。Android 8.0（API 26）及以上。

## 功能

- **日程**：今日/明日课程、下一节/正在上的课、当天到期计划。
- **课表**：单指自由拖动、双指缩放（可设初始大小，一键恢复）、表头和时间栏固定不随滚动消失、切换教学周、「设置当前周」（选今天是第几周即可推算第一周）、非本周课程灰显、周末显示、完整课程详情、冲突时段入口、课程新增/编辑/删除。
- **自定义**：学校与学期名称、第一周周一、总周数、每日节数、逐节开始/结束时间；一门课程可有多条星期、节次、周次、教室不同的安排。教师是课程级字段。
- **计划**：任务、作业、考试，截止日期、课程关联、完成状态。
- **外观**：浅色/深色/跟随系统、5 种主题色与跟随壁纸取色（Android 12+）、浅色/实色课程卡片，可选显示教室、教师、每节时间和表格线。说明文字收在「我的 → 使用说明与关于」。
- **我的**：学期与作息、课前提醒、完整备份、分享课表、CSV 导入/导出、学校 Excel 课表导入（.xls / .xlsx）、文字版 PDF 课表识别导入（导入前逐条核对）。
- **提醒与小组件**：上课前 N 分钟本地通知（重启后自动恢复）；桌面小组件「今日课程」。
- **离线**：无账号/服务器/广告 SDK/网络权限；不申请全盘存储权限，通过系统文件选择器导入导出。仅为提醒申请通知、闹钟和开机重新排程权限。

首次打开不会自动填入参考图片中的课程。初始作息只是可编辑的示例；务必先按自己的学校修改。

## 构建与安装

需要 JDK 17 或 21、Android SDK Platform 35、Build Tools 35.0.0 和可访问 Google Maven/Maven Central 的网络。项目使用 Gradle 8.11.1、AGP 8.9.2、Kotlin 2.1.20。SDK 安装与许可接受由使用者决定。

1. 使用 Android Studio 打开项目根目录，或在 `local.properties` 中配置 `sdk.dir`（不要提交机器路径）。
2. 运行：

```powershell
.\gradlew.bat :core:test :app:assembleDebug :app:lintDebug
```

通常 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。这是个人测试用 Debug APK，不是正式发行签名包。

也可运行 `:app:exportDebugApk`，将安装包统一导出到工程内的 `dist/Coursatrix-<版本>-debug.apk`（`dist/` 已忽略）。

### 正式版（签名）

正式版用自己的签名密钥签名。在工程根目录建立 `signing/`（已忽略，**不要上传**）：

```powershell
keytool -genkeypair -storetype PKCS12 -keystore signing/coursatrix-release.jks -alias coursatrix -keyalg RSA -keysize 4096 -validity 10000
```

再新建 `signing/keystore.properties`：

```properties
storeFile=coursatrix-release.jks
storePassword=你的密码
keyAlias=coursatrix
keyPassword=你的密码
```

运行 `.\gradlew.bat :app:exportReleaseApk` 得到 `dist/Coursatrix-<版本>.apk`。没有 `signing/` 时正式版不签名、无法安装。**密钥和密码丢失后，新版本将无法覆盖安装旧版本**，请妥善备份。

若本机 Java 下载 Gradle 超时，但 Python 可通过系统网络设置访问官方站点，可先缓存**同一份官方 ZIP**，不切换到未知镜像、不关闭校验：

```powershell
python -I tools/cache_gradle.py --downloads-dir .tools/gradle-downloads
```

工具与 Wrapper 都校验固定的官方 SHA-256；完成后继续正常运行 `gradlew`。

### Windows 中文路径

当前目录包含中文，已为 AGP 开启路径检查兼容选项。在部分 Windows JDK 上，Gradle 测试进程的参数文件仍会把中文类路径解码错误，出现 `ClassNotFoundException`。可以使用仅改变构建输出位置的参数，不必移动源码：

```powershell
.\gradlew.bat -PbuildRoot=D:/CoursatrixBuild :core:test :app:exportDebugApk :app:lintDebug
```

该示例会将 APK 导出到工程内的 `dist/Coursatrix-<版本>-debug.apk`，原始构建产物位于 `D:\CoursatrixBuild\app\outputs\apk\debug\app-debug.apk`。`buildRoot` 应为**与源码在同一盘符**的纯英文可写路径，否则 KSP 的相对路径处理可能失败。在其他电脑上请相应替换。也可以把整个工程复制到纯英文目录后正常构建。

本机 2026-10-19 的构建、测试、Lint、APK 签名与权限核对结果见 [验证记录](docs/verification.md)。该记录明确区分编译通过与尚未执行的设备测试。

### 没有 Android SDK 时只测试核心

```powershell
.\gradlew.bat -PcoreOnly :core:test
```

`coreOnly` 不包含 Android 模块；`buildRoot` 可以同时使用。单元测试通过不能替代 Android 编译、Lint 和设备验收。

### 设备测试

```powershell
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:connectedDebugAndroidTest
```

第二条需要已连接且授权的测试设备或模拟器，会安装/运行测试程序。数据库测试使用内存库；导航测试不主动修改课表。**不要在存有唯一课程数据的设备上做破坏性手动验收，先备份。**

## 导入与导出

详细规范见 [docs/transfer-format.md](docs/transfer-format.md)。

| 文件 | 用途 | 范围 |
|---|---|---|
| `.coursatrix` 完整备份 | 备份/迁移/恢复 | 学期、作息、课程、安排、计划、备注和外观。恢复会替换全部本地数据 |
| `.coursatrix` 分享课表 | 给同学分享 | 学期、作息、课程；不含计划/私人备注，可去掉教师和教室 |
| UTF-8 CSV | 批量编辑/导入 | 课程和安排；不携带学期起始日期或作息时间；导出包含课程备注 |
| 学校 `.xls` / `.xlsx` 网格课表 | 仅导入 | 作息时间、课程、教师、教室、周次；不读取学生姓名/学号/班级。预览时选择今天是第几周 |
| 文字版 PDF 课表 | 仅导入 | 识别后进入「核对识别结果」，可逐条修改 |

在「我的」选择导入后，会先预览，再确认写入。CSV 可合并或替换课程，替换不会删除计划，但可能解除课程关联。错误文件不会部分导入。重叠课程不会自动覆盖，而是在课表中提供查看全部安排的入口。

**备份未加密。** 请保存在可信的位置，不要把含备注/计划的完整备份当成脱敏分享文件。卸载应用会清除本机数据库，应用没有自动云备份；请定期导出。

### Excel 与学校原始课表

[samples/courses-template.csv](samples/courses-template.csv) 使用合成示例数据，可在 Excel 中编辑后另存为 **CSV UTF-8**。每行是一条上课安排，同一门课可写多行。不能把任意学校的二维网格 Excel 仅改后缀就当模板导入。

**手机上直接导入学校 Excel 课表：**在「我的 → 导入文件」选择教务系统导出的 `.xls` 或 `.xlsx` 即可（按文件内容识别格式，不看扩展名）。应用内置的读取器只识别「星期为列、节次为行、连堂用合并单元格、单元格为 `课程名 / 教师【周次】/ 教室`」的网格课表，与 `Import/xskb.xls` 同类；遇到无法识别的课程或周次会报出具体行列并拒绝导入，不会猜测。

- 表格里的「时间」列会成为作息时间；没有时间列时沿用当前作息。
- 学校 Excel 课表不含学期开始日期，预览时请选择「今天是第几教学周」。
- 重新导入更新后的课表时，同名同教师的课程会保留原颜色、备注和计划关联。
- 设置了打开密码的表格需先取消密码。

### PDF

- **PDF**：文字型 PDF 读取文字位置和表格线（pdfbox-android），按「星期一…」表头和左侧节次编号重建表格，连堂按表格线判断。扫描版（只有图片）的 PDF 暂不支持。
- 识别结果先进入「核对识别结果」：有疑问的条目标红并展开；被截断的课名（如「大学英语读...」）若与已有课程唯一对应会自动补全；没有周次的课程可统一设置默认周次。之后与 CSV 一样可选择合并或替换。
- 要求：表格上方有星期表头、左侧有 1、2、3… 节次编号。竖排课表、列表式课表暂不支持。
`tools/convert_school_xls.py` 是同一规则的**电脑端转换器**（可选），不上传文件，不执行宏。依赖 Python 3 和 xlrd（本机已有 xlrd 时无需重复安装）：

```powershell
python -m pip install -r tools/requirements.txt
python -I tools/convert_school_xls.py Import/xskb.xls Import/my-courses.csv --weeks 20
python -I tools/test_convert_school_xls.py
```

工具拒绝覆盖已有输出，请使用新文件名。CSV 不包含作息；转换完成会显示识别出的时间，先在应用中配置好再导入。`Import/xskb-converted.csv` 如果存在，是本机从用户样本生成的私有文件，不随工程发布。

若已确认学校**第一教学周周一**，可以同时携带学校作息，转换为专用分享课表：

```powershell
# 下列日期仅为格式示例，请务必换成学校教学日历上的真实日期！
python -I tools/convert_school_xls.py Import/xskb.xls Import/my-timetable.coursatrix --weeks 20 --first-monday 2026-09-07 --name "我的学期"
```

这不会保存原文件中的学生姓名、学号或班级，但仍保留课程、教师、地点和学校名称。导入后逐项核对连堂节次、单双周和自选周次。

## 工程结构

- `core/`：纯 Kotlin 模型、教学周与冲突规则、校验、JSON/CSV、单元测试。
- `app/`：Compose 界面、ViewModel、Room 数据库、系统文档读写、设备测试。
- `samples/`：可公开的合成示例模板。
- `tools/`：本地 XLS 转换、转换器测试、显式许可确认后的官方 SDK 准备工具。
- `example/`：本地视觉参考图片（含真实课表），已忽略，不随源码发布。
- `Import/`、`.tools/`、`local.properties`：私有输入/工具下载/机器配置，均已忽略，不应随源码公开。

## 第一版暂不包含

教务系统登录抓取、ICS/图片导出、多学期切换、云同步。界面没有将这些项目伪装成已可用功能。

上架应用商店前，还需当前应用商店的 targetSdk 适配、隐私政策、无障碍与多机型测试。

## 许可证

本项目以 [GNU 通用公共许可证 第 3 版（GPL-3.0）](LICENSE) 发布：可以自由使用、修改和再发布，但基于本项目发布的程序也必须以 GPL-3.0 开源。

主要第三方依赖：AndroidX / Jetpack Compose、Room、Kotlin 协程与 kotlinx.serialization（Apache-2.0），[pdfbox-android](https://github.com/TomRoush/PdfBox-Android)（Apache-2.0，含 Bouncy Castle，MIT 风格许可）。Apache-2.0 与 GPL-3.0 兼容。
