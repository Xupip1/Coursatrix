# Coursatrix 本机构建验证

以下仅记录实际执行的检查，不代表真机验收或应用商店发行认证。最新版本在前。

## 1.0.0（2026-10-20）

### 变更

- 首页移除「到期计划」区域（计划仍在「计划」页）。
- 第一个正式版：使用自有 RSA 4096 发布密钥签名（`signing/`，已忽略，不随源码发布）。代码压缩（R8）暂未开启，待真机测试后再启用。

### 安装包

- `dist/Coursatrix-1.0.0.apk`，20,137,412 字节；versionCode `6`；非 debuggable。
- SHA-256：`4c6c84395890f8a4ad9075775e04dada1fb148e7c154e927fca8e8416511392b`
- 签名证书 `CN=Coursatrix, O=Coursatrix`，证书 SHA-256：`315cb3cc4a7c4d76f7e38e9cc58797cad106d84c40599ecb83dcdd7690cfa04f`；`apksigner verify` 退出码 0。
- 与之前 Debug 版签名不同，**不能覆盖安装 Debug 版**：需先导出备份、卸载旧版、安装后恢复。
- 权限：通知、精确闹钟、开机重新排程；没有网络权限。

### 检查结果

| 检查 | 实际结果 |
|---|---|
| Kotlin 核心单元测试 | 33 项，0 failures，1 项跳过（私有 xlsx 样本不存在） |
| Android Lint（debug）与发布构建自带的 lintVitalRelease | 0 errors；3 warnings 均为第三方 bouncycastle |
| 真机安装与功能验收 | **未执行**：无已连接设备/模拟器 |

## 0.5.0（2026-10-20）

### 变更

- 首页日程增加「后天」。
- 新应用图标：自适应图标（青绿渐变背景 + 白色「C」与三条时间格），含 Android 13+ 主题图标用的单色层；旧的单层图标已删除。图标改动后重新导出的 APK SHA-256：`bb546e67bb50b1307fe023a3e8d687ca24566f5013fd504ebbb9e5bef24d56ad`。
- 移除课表截图导入：删除 ML Kit 依赖与图片识别代码、相册入口；扫描版 PDF 改为明确提示不支持。不再需要只保留 ARM 架构。
- 外观与显示：5 种主题色 + 跟随壁纸（Android 12+）、课程卡片浅色/实色、可选显示教室/教师/每节时间/表格线。存入数据库并包含在备份中；数据库版本 2 → 3（`semester` 表新增 6 列，带迁移）。
- 主要页面的说明性文字移入「我的 → 使用说明与关于」折叠卡片；需要用户处理的状态提示（今天不在学期内、通知被关闭等）仍保留在原处。

### 安装包

- `dist/Coursatrix-0.5.0-debug.apk`，27,089,306 字节（约 25.8 MiB；0.4.0 为 46.8 MiB）。
- SHA-256：`da9a39c830b9c9fee86df9617a9cac18a30cfb765a4b13f74add098f3a2650ef`；`apksigner verify` 退出码 0。
- 权限：通知、精确闹钟、开机重新排程；**没有网络权限**。

### 检查结果

| 检查 | 实际结果 |
|---|---|
| Kotlin 核心单元测试 | 33 项（CoreTest 18 + LayoutImportTest 6 + XlsImportTest 9），0 failures；1 项跳过（私有 xlsx 样本不存在）；真实 PDF 与 XLS 一致性测试仍通过 |
| Room schema | 生成 `3.json`，新列默认值与迁移 SQL 一致 |
| Android Lint | 0 errors，3 warnings，均为第三方 bouncycastle jar 内的 `TrustAllX509TrustManager`（应用无网络权限） |
| 设备测试、1→2→3 迁移、主题色与卡片样式的真机显示 | **未执行**：无已连接设备/模拟器 |

## 0.4.0（2026-10-20）

### 变更

- PDF 课表导入（pdfbox-android 读取文字坐标和表格线；扫描件渲染后 OCR）。
- 课表截图导入（ML Kit 内置中文模型，本机识别；连堂按色块高度判断）。
- 新的「核对识别结果」界面：逐条修改、标红疑问、默认周次、截断课名自动匹配已有课程。
- 课程单元格解析抽为共用的 `CourseCellParser`，Excel 导入也改用它（行为不变，仍严格报错）。

### 安装包

- `dist/Coursatrix-0.4.0-debug.apk`，49,032,389 字节（约 46.8 MiB；0.3.0 为 17.1 MiB）。增量主要来自 OCR 原生库（arm64 约 10.6 MiB、armeabi-v7a 约 6.5 MiB）和 pdfbox-android 及其依赖 bouncycastle。已用 `abiFilters` 去掉仅模拟器使用的 x86/x86_64（否则约 66 MiB）。
- SHA-256：`210179607e07c0fc812c16c9db56df2b1b439bbcee338bdd3ae242e42e8b503a`；`apksigner verify` 退出码 0。
- 权限：与 0.3.0 相同。ML Kit 自带的 `INTERNET`、`ACCESS_NETWORK_STATE` 已在清单中移除，`aapt dump permissions` 确认**没有网络权限**。

### 检查结果

| 检查 | 实际结果 |
|---|---|
| Kotlin 核心单元测试 | 34 项（CoreTest 18 + LayoutImportTest 7 + XlsImportTest 9），0 failures；1 项跳过（私有 xlsx 样本不存在） |
| 真实 PDF `Import/xskb.pdf` | 用桌面版 PDFBox 按与应用相同的提取逻辑读取，重建结果 18 条安排与 `xskb.xls` 完全一致（课程名、教师、地点、星期、节次、周次），无待核对条目 |
| 真实截图 | 用截图**真实像素**检测色块，12 处课程的星期与连堂节次全部正确；文字位置为按图片手工标注的近似值，**ML Kit 实际识别效果未在设备上验证** |
| Android Lint | 0 errors，4 warnings：`ChromeOsAbiSupport`（有意只保留 ARM）；`TrustAllX509TrustManager` ×3 位于第三方 bouncycastle jar 内部，应用无网络权限，不会建立 TLS 连接。未禁用检查 |
| 设备测试、真机 OCR、PDF 渲染、核对界面 | **未执行**：无已连接设备/模拟器 |

设备验收补充：用 `Import/` 中的截图和 PDF 各导入一次（先导出备份），核对识别结果后选择「合并」或「替换」；另测一张高分辨率原始截图和一份扫描 PDF。

## 0.3.0（2026-10-20）

### 变更

- 课表：单指任意方向拖动（带惯性），双指缩放 40%–300%；表头（星期/日期）和左侧时间栏固定，放大后滚到右侧也能看到时间。
- 左下角缩放按钮：缩小、当前比例（菜单中可「将当前大小设为初始大小」「适应屏幕宽度」「恢复 100%」）、放大、恢复初始大小。初始大小只保存在本机。
- 应用内导入 `.xlsx`（自写读取器，用系统自带的 ZIP 与 SAX 解析，无第三方库）。

### 安装包

- `dist/Coursatrix-0.3.0-debug.apk`，17,884,862 字节；versionCode `3`；Debug 签名，`apksigner verify` 退出码 0。
- SHA-256：`86102ed11fcded0d61eda887cc323b0e88da25c3d9a23a42646044b78ddf6147`
- 权限与 0.2.0 相同；没有网络或存储权限。

### 检查结果

| 检查 | 实际结果 |
|---|---|
| Kotlin 核心单元测试 | 27 项（CoreTest 18 + XlsImportTest 9），0 failures，0 errors，0 skipped |
| `.xlsx` 读取 | 合成 xlsx（共享字符串、富文本、注音、`_x000D_`、内联字符串、公式缓存值、合并单元格、带前缀的命名空间）与 xls 结果一致；拒绝非 Excel 压缩包、DOCTYPE、截断文件 |
| 真实样本 | 用 openpyxl 把 `Import/xskb.xls` 临时另存为 `.xlsx`，解析为 10 门课程、18 条安排、12 节作息，与 `.xls` 一致；临时文件已删除 |
| Android Lint | `No issues found.` |
| 主 APK / 设备测试 APK | 编译成功 |
| 真机手势、缩放、通知、小组件、数据库迁移 | **未执行**：无已连接设备/模拟器 |

## 0.2.0（2026-10-19）

### 变更

- 应用内直接导入教务系统导出的 `.xls` 网格课表（自写 BIFF8/OLE2 读取器，无第三方库，不执行宏/公式）。
- 课表页「设置当前周」：选择今天是第几教学周，自动推算第一周周一；导入 XLS 时同样在预览中选择。
- 课表空位中灰色显示非本周课程（可在「我的」关闭）。
- 课前提醒通知（本机定时，不联网）与桌面小组件「今日课程」。
- 数据库版本 1 → 2：`semester` 表新增 `showInactive` 列，带迁移，旧数据保留。

## 安装包

- 工程内导出位置：`dist/Coursatrix-0.2.0-debug.apk`。
- 大小：17,835,710 字节（约 17.01 MiB）。
- 包名：`com.coursatrix.app`；版本 `0.2.0`，versionCode `2`。
- 最低系统：Android 8.0 / API 26；targetSdk 为 35。
- Debug 签名，不是正式发行签名；`apksigner verify` 退出码 0。
- 导出文件与 `D:/CoursatrixBuild/app/outputs/apk/debug/app-debug.apk` 的 SHA-256 一致：

```text
095f4281fc2d1db8c317123d12da656f8a1dce3015b9a42a8a28ac2e623ea548
```

`aapt dump permissions`：`POST_NOTIFICATIONS`、`SCHEDULE_EXACT_ALARM`、`RECEIVE_BOOT_COMPLETED`（均用于课前提醒），以及 AndroidX 引入的应用内签名级权限 `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`。**没有网络或存储权限。**

## 实际构建命令

```powershell
.\gradlew.bat -PbuildRoot=D:/CoursatrixBuild :core:test :app:assembleDebug --offline
.\gradlew.bat -PbuildRoot=D:/CoursatrixBuild :app:lintDebug :app:assembleDebugAndroidTest :app:exportDebugApk --offline
```

两次均 `BUILD SUCCESSFUL`。

## 检查结果

| 检查 | 实际结果 |
|---|---|
| Kotlin 核心单元测试 | 24 项（CoreTest 18 + XlsImportTest 6），0 failures，0 errors，0 skipped |
| XLS 读取器 | 合成 XLS（小流/常规流、跨 CONTINUE 记录的字符串、LABEL/NUMBER/RK/MULRK、合并单元格）与损坏文件拒绝 |
| 本机学校样本 `Import/xskb.xls` | 应用内导入器解析为 10 门课程、18 条安排、12 节作息，与 Python 转换器结果一致（文件不存在时该测试自动跳过） |
| Android Lint | `No issues found.` |
| Room schema | 生成 `app/schemas/.../2.json` |
| 主 APK / 设备测试 APK | 编译成功；设备测试 APK 编译成功不等于测试执行通过 |
| 设备测试、真机界面、通知、小组件、数据库 1→2 迁移 | **未执行**：`adb devices -l` 无已连接设备/模拟器 |

## 后续设备验收清单

在测试设备上安装上述 Debug APK 后，重点检查：

1. 课表页：单指斜向拖动、快速滑动后的惯性、双指缩放；放大后滚到周日一列时左侧时间栏仍可见；点击课程仍能打开详情；缩放菜单设置初始大小后离开再回来是否生效。
1. **从旧版本覆盖安装**，确认原有课程、计划仍在（验证数据库迁移）。先导出完整备份！
2. 「我的 → 导入文件」选择学校 `.xls` 和 `.xlsx`（可在 Excel/WPS 中另存一份），在预览中选择今天是第几周，确认导入；核对连堂节次、单双周、作息时间。
3. 课表页周次菜单 →「设置当前周…」，确认周次与日期表头正确。
4. 浏览不同周，确认非本周课程以灰色显示在空位，点击可查看详情；在「我的」关闭后消失。
5. 开启课前提醒（Android 13+ 会请求通知权限），设为 5 分钟，在一节课前等待通知；重启手机后再次确认。
6. 长按桌面添加「今日课程」小组件，核对内容、深色模式和点击打开应用。
7. 原有检查：备份/恢复、CSV 导入、冲突入口、浅深色与大字号。

需要设备测试时运行 `:app:connectedDebugAndroidTest`。请勿在保存唯一课程数据的设备上直接进行破坏性测试，应先导出备份。
