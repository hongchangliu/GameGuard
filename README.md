# GameGuard 0.18

GameGuard 是一款运行在 Android 手机上的本机应用管理工具，桌面显示名称为 **应用使用管理**。它通过 Android 的设备所有者（Device Owner）权限，将应用黑名单和设备限制交给系统执行；管理员在手机上输入密码后修改策略。

当前版本采用 Kotlin 和原生 Android 界面，无需自建服务器。普通应用可正常安装和使用，命中名称关键字或游戏分类规则的第三方应用在安装后会被禁止运行。

## 功能一览

| 功能 | 当前行为 |
| --- | --- |
| 名称关键字黑名单 | 应用显示名称包含任一关键字时限制运行；可添加、移除关键字 |
| 游戏默认拦截 | 拦截 Android 分类为游戏或带游戏标记的第三方应用 |
| 本机审核放行 | 输入管理员密码后，按具体应用包名保存放行例外 |
| 普通应用安装 | 不启用全局禁装，未命中限制的应用可从应用市场正常安装 |
| 手动安装 APK | 管理员可选择单文件 APK，输入密码后安装；安装完成仍核对拦截规则 |
| 防卸载 | 设备所有者策略阻止卸载本应用；Android 11 及以上尝试限制清除数据和强行停止 |
| 禁止添加用户 | 默认启用；管理员可尝试解除或重新启用，不删除已有用户 |
| 限制恢复出厂设置 | 由管理员手动启用，限制系统设置里的恢复出厂设置入口 |
| 每日使用记录 | 查询第三方应用的前台事件，展示事件次数及首次、末次时间 |
| QQ 邮箱日报 | 本机配置邮箱和 SMTP 授权码，自动发送昨日记录，也支持手动发送今日或昨日记录 |
| 重启后保留 | 系统保存设备管理限制，应用接收开机事件后重新核对应用策略 |

## 黑名单如何工作

### 名称匹配和游戏识别

应用通过 `PackageManager` 枚举当前用户下已安装的第三方应用，跳过系统应用和 GameGuard 自身。匹配条件包括：

1. **名称关键字**：读取应用显示名称，去除首尾空白并转为小写，使用子串包含匹配。关键字长度为 2～40 个 Unicode 码点。例如“快手”可匹配“快手极速版”；同名的不同渠道安装包也可以命中。
2. **游戏类别**：检查 `ApplicationInfo.CATEGORY_GAME` 或 `ApplicationInfo.FLAG_IS_GAME`。被标记为游戏的应用默认限制运行。
3. **旧规则兼容**：旧版包名规则优先迁移为已知名称关键字或已安装应用的名称；无法确定名称的旧规则继续保留，并在界面中供管理员处理。

首次初始化内置关键字：三角洲行动、快手、快手极速版、抖音、抖音极速版、今日头条、今日头条极速版、哔哩哔哩、蛋仔派对、和平精英、无畏契约、王者荣耀、迷你枪战精英。已有安装的迁移结果取决于原有配置。

管理员审核放行时，应用按**包名**保存游戏规则或名称规则的例外。关键字是管理入口，真正调用 Android 系统接口时仍需使用包名。

### 系统执行与自动核对

`PolicyController` 计算本次应限制的应用集合，再调用 `DevicePolicyManager.setPackagesSuspended()` 暂停这些应用；不再命中规则的旧限制会被解除。系统拒绝暂停或解除时，应用会显示失败信息。管理支持文案设置为“请和爸爸联系”，具体弹窗样式由手机系统决定。

```text
安装或更新应用 / 修改规则 / 开机 / 定期核对
                    ↓
         枚举第三方应用并读取名称、分类
                    ↓
        名称规则 + 游戏规则 + 旧规则兼容
                    ↓
             按相应放行例外过滤
                    ↓
       暂停命中应用，解除不再命中的旧限制
```

核对由以下入口触发：

- `ProtectionService`：带常驻通知的前台服务，动态监听应用安装、更新和移除事件。
- `PolicyReceiver`：监听开机、首次解锁后的开机广播、应用自身更新及设备所有者变化等事件。
- `PolicyAuditJobService`：通过 `JobScheduler` 注册约 15 分钟周期的持久任务，作为补充检查。
- 管理界面：打开应用、修改关键字或审核放行时重新应用策略。

本版允许先安装再限制运行，不会提前阻止应用市场下载或安装黑名单应用。服务被终止或系统延迟任务时，新安装应用可能在下次核对之前短暂运行。

## 设备管理与重启

启用 Device Owner 后，`PolicyController` 使用以下系统接口：

| 目的 | 实现方式 |
| --- | --- |
| 防止卸载 GameGuard | `setUninstallBlocked()` |
| 限制清除数据、强行停止 | Android 11 及以上尝试调用 `setUserControlDisabledPackages()` |
| 禁止添加用户 | `addUserRestriction(DISALLOW_ADD_USER)`，默认添加；管理员允许新增用户后保存例外设置 |
| 限制恢复出厂设置 | 管理员操作时添加或清除 `DISALLOW_FACTORY_RESET` |
| 保持普通应用可安装 | 策略核对时清除旧版遗留的 `DISALLOW_INSTALL_APPS` |

正常重启不会清除 Android 已保存的设备所有者身份及上述限制。黑名单和新增用户偏好保存在设备保护存储中，开机接收器会重新核对策略；恢复出厂设置限制依赖系统保存，当前代码不会在每次核对时强制重新启用它。

**限制范围：**

- “禁止添加用户”不删除已有用户，也不等于禁止切换已有用户。厂商隐私空间、应用分身和其他资料类型需要分别验证；系统自带的限制也可能导致管理员无法解除新增用户限制。
- “限制恢复出厂设置”初始未由本应用启用，需要管理员手动打开。它针对系统设置入口，不能保证阻止 Recovery 恢复模式、刷机或其他外部清除方式。
- 真正恢复出厂设置后，应用、本地配置及设备所有者状态会被清除。本项目没有清空后自动重新注册管理的机制。

## 管理员密码和数据存储

管理员密码至少 8 位，用于修改策略、审核放行和邮件相关管理操作。密码不以明文保存：`AdminCredentials` 使用 16 字节随机盐和 PBKDF2-HMAC-SHA256（200,000 次迭代、256 位结果）保存校验值，并使用恒定时间比较。累计失败达到 5 次后，后续失败会触发 1 分钟等待；验证成功清零。

密码校验信息位于凭据加密存储中，首次解锁前不可用。当前没有管理员密码找回或应用内重置入口。应用禁用 Android 自动备份。

## 使用记录和每日邮件

### 记录范围

用户需在系统设置中为“应用使用管理”授予**使用情况访问权限**。`UsageReportStore` 查询 `UsageStatsManager` 的 `ACTIVITY_RESUMED` 事件，按手机本地时区汇总某一天的第三方应用使用情况，输出：

- 应用名称和包名。
- 前台事件次数。
- 首次与末次前台事件时间。

这些事件不等于准确的启动次数，也不提供使用总时长、后台进程列表、聊天内容或应用内浏览内容。报告是在查看、保存或发送时从系统事件记录生成，并非独立、连续的全量日志；可追溯范围取决于系统保留的事件。

保存的文本报告位于应用私有目录 `files/usage_reports/`，最多保留最新 30 份日文件。

### 发送方式与时间

在手机内配置 QQ 邮箱地址和 **SMTP 授权码**（不是 QQ 登录密码）。`DailyMail` 直接连接 `smtp.qq.com:465`，使用 TLS 并校验服务器主机名；发件人和收件人均为配置的同一邮箱。

授权码经 Android Keystore 管理的密钥使用 AES-GCM 加密后保存。不要把授权码、管理员密码或个人报告提交到仓库。

`DailyReportJobService` 使用 `JobScheduler` 注册 **24 小时周期、1 小时弹性窗口、需要联网**的持久任务，因此**没有固定每天几点发送的设置**。省电、待机及网络条件可能推迟执行。任务发送前一天报告，并依据最后成功发送日期尝试补发最近 3 天内的遗漏日期；这不是保证送达或精确一次投递机制。

没有使用情况访问权限时跳过自动报告；未配置邮件时，任务仅尝试保存昨日记录。管理员也可手动发送今日截至当前或昨日的记录。“停止每日邮件”会删除保存的授权码和加密参数，保留邮箱地址。

## 安装与配置

最低声明支持 Android 10（API 29），编译和目标 SDK 为 35。已在荣耀 Magic5 Pro / MagicOS 10 上验证 0.18 覆盖安装、设备所有者身份，以及 `no_add_user`、`no_factory_reset` 的有效状态。完整的重启流程、多用户隔离、厂商分身功能和其他 Android 版本尚未完成验证；最低 SDK 声明不代表所有系统版本已通过兼容性测试。

### 构建

项目使用 Android Gradle Plugin 8.7.3、Kotlin 2.0.21。安装 Android SDK 35，使用兼容的 JDK 和 Gradle；本地已使用 JDK 21、Gradle 8.9 构建成功。仓库目前不含 Gradle Wrapper，可在 Android Studio 中配置 Gradle，或使用已安装的 Gradle：

```sh
gradle --no-daemon assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

虽然构建类型名为 `debug`，当前已设置 `isDebuggable = false`，清单也未声明 `android:testOnly`。这不代表使用了独立的生产签名；部署时应自行管理并保管签名密钥，后续覆盖更新必须保持签名一致。

### 激活设备所有者

普通安装 APK 不会自动取得 Device Owner 权限。请先在模拟器或专用测试设备上验证；激活前设备通常需满足无冲突账号、其他用户、工作资料和已有设备所有者等条件，具体以系统返回结果为准。

```sh
adb install app/build/outputs/apk/debug/app-debug.apk
adb shell dpm set-device-owner com.gameguard/.GameGuardAdmin
adb shell dpm list-owners
```

安装和命令执行可通过 USB 或已配对的无线调试连接进行。激活失败时先检查系统错误信息，不要为排查直接清空日常设备。

激活后打开“应用使用管理”：

1. 设置管理员密码，检查设备所有者状态。
2. 检查关键字和游戏拦截结果，按需审核放行。
3. 确认“添加新用户”限制；如需限制系统设置里的恢复出厂设置，输入密码手动启用。
4. 如需日报，授予使用情况访问权限，配置 QQ 邮箱和 SMTP 授权码，并手动发送一次验证。

### 更新与管理员入口

使用同一签名 APK 覆盖更新：

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

更新无需先解除设备所有者身份。当前版本没有测试管理员移除入口，不能按 `testOnly` 测试应用的方式移除设备所有者。

默认通过桌面的“应用使用管理”进入。应用自身不实现隐身；若使用手机系统提供的隐藏图标功能，应保留管理员可访问的入口。系统管理页面和前台服务通知仍可能显示本应用。

旧安装迁移时可通过构建环境变量 `GAMEGUARD_MAIL_ADDRESS` 提供默认邮箱地址；该地址会进入构建产物并首次写入本地配置。通常直接在手机上配置即可，不要把个人地址或授权码写入源码。

## 代码导航

| 文件 | 职责 |
| --- | --- |
| [MainActivity.kt](app/src/main/java/com/gameguard/MainActivity.kt) | 管理界面、密码确认、关键字维护、APK 安装与报告操作 |
| [PolicyController.kt](app/src/main/java/com/gameguard/PolicyController.kt) | 规则迁移、匹配、放行例外、应用暂停及设备限制 |
| [GameGuardAdmin.kt](app/src/main/java/com/gameguard/GameGuardAdmin.kt) | 设备管理员接收器 |
| [ProtectionService.kt](app/src/main/java/com/gameguard/ProtectionService.kt) | 常驻通知、动态监听安装与卸载事件 |
| [PolicyReceiver.kt](app/src/main/java/com/gameguard/PolicyReceiver.kt) | 开机、更新、策略恢复及安装结果处理 |
| [PolicyAuditJobService.kt](app/src/main/java/com/gameguard/PolicyAuditJobService.kt) | 定期重新核对策略 |
| [AdminCredentials.kt](app/src/main/java/com/gameguard/AdminCredentials.kt) | 管理员密码派生校验与失败等待 |
| [UsageReportStore.kt](app/src/main/java/com/gameguard/UsageReportStore.kt) | 查询前台事件、生成并保存日报 |
| [DailyReportJobService.kt](app/src/main/java/com/gameguard/DailyReportJobService.kt) | 日报调度、补发和成功日期记录 |
| [DailyMail.kt](app/src/main/java/com/gameguard/DailyMail.kt) | 邮箱配置、授权码加密及 SMTP 发送 |
| [AndroidManifest.xml](app/src/main/AndroidManifest.xml) | 权限、服务、接收器及应用配置 |

## 已知边界

- 游戏识别依赖应用声明的分类；未声明或错误分类的游戏可能漏拦，应补充名称关键字。
- 名称子串匹配可能误拦名字相近的正常应用；应用更名也可能避开原关键字。
- 规则枚举的是当前运行用户下的应用，不能据此承诺覆盖所有用户、工作资料、隐私空间或分身。
- 系统限制接口可能拒绝某些包；应查看界面错误并实际尝试启动目标应用确认结果。
- 定期任务和前台服务受到系统调度与厂商行为影响，不能承诺安装后零延迟拦截或邮件准点发送。
- 当前只有本机管理员审核，没有远程审批服务、网页管理后台或云端配置恢复。
