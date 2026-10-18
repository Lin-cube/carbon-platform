# VSCode 满屏爆红排查记录

> 现象：在 VSCode 里打开项目，Java 文件大量爆红，报错内容是
> `The Java feature 'Flexible Constructor Bodies' is only available with source level 25 and above`。
> 但 `mvn compile` 一直是通过的。

---

## 一、结论：这是 IDE 配置问题，不是代码问题

**判断依据**：

```bash
cd carbon-platform/backend
mvn -s ../scripts/maven-settings.xml clean compile
# → BUILD SUCCESS，67 个源文件编译通过
```

代码本身没有任何编译错误。爆红全是 IDE 的 Java 语言服务器（redhat.java）造成的。

---

## 二、根因（实测日志确认）

### 直接原因：Lombok 没有被加载

所有报错都出现在标了 `@RequiredArgsConstructor` / `@Slf4j` / `@Data` 的行上：

| 文件 | 报错行 | 该行内容 |
|---|---|---|
| `MockAiServiceClient.java` | 31 | `@RequiredArgsConstructor` |
| `AnalyticsController.java` | 31 | `@RequiredArgsConstructor` |
| `AnalyticsService.java` | 30 | `@RequiredArgsConstructor` |
| `AiController.java` | 27 | `@RequiredArgsConstructor` |
| `ReportExportService.java` | — | `@RequiredArgsConstructor` |

Lombok 不生效 → 注解不展开 → 类里看不到生成的构造函数和字段 →
JDT 解析器误判为 Java 25 的新语法 *Flexible Constructor Bodies*（JEP 513），
于是报出「该特性需要 source level 25」，而项目是 21。

**这个报错信息和真实问题毫无关系**，所以看起来特别费解。

### 深层原因：Maven 项目导入失败

从 Java 语言服务器的 Eclipse 日志里找到：

```
!MESSAGE Importing Maven project(s)
java.net.http.HttpTimeoutException: request timed out
    at org.eclipse.jdt.ls.core.internal.managers.MavenCentralIdentifier.find(...)
    at ...MavenSourceDownloader.discoverSource(MavenSourceBuilder.java:141)
```

**Java 插件在导入 Maven 项目时去 Maven Central 下载源码包（-sources.jar），网络超时，
导致项目导入中断 → classpath 不完整 → Lombok 不在注解处理器路径上 → 注解不生效。**

本项目用的是阿里云镜像（`scripts/maven-settings.xml`），但 VSCode 的 Java 插件默认不读这个配置，
所以它直连 Maven Central，慢/超时。

### 加剧原因：Lombok 版本与新版 JDT 不兼容

- VSCode 的 `redhat.java` 1.57 要求 **JDK 25+** 启动语言服务器（内置 JRE 25）
- 它从**项目 classpath** 加载 Lombok（配置项 `java.jdt.ls.lombokSupport.enabled`，默认 true）
- 项目原来是 Lombok **1.18.40**（Spring Boot 3.5.6 的默认版本）
- 插件自带的是特制版 `lombok-1.18.39-4050.jar` —— 说明它对 JDT 版本有适配要求
- 1.18.40 的 Eclipse 代理与新 JDT 不完全兼容，加剧了解析异常

---

## 三、已做的修复

### 1. `pom.xml`：Lombok 升到 1.18.46

```xml
<lombok.version>1.18.46</lombok.version>
```

覆盖 Spring Boot 默认的 1.18.40。已验证 `mvn clean compile` 通过（67 个文件）。

> 注意：本地仓库里 1.18.46 是之前从其他仓库缓存来的，
> 首次构建需要联网从阿里云镜像重新校验一次 POM。

### 2. 新增 `.vscode/settings.json`

关键几项：

| 配置 | 作用 |
|---|---|
| `java.configuration.maven.userSettings` → `scripts/maven-settings.xml` | **让插件走阿里云镜像**，解决下载超时 |
| `java.maven.downloadSources: false` | **关闭源码包下载**（就是它超时的） |
| `java.eclipse.downloadSources: false` | 同上 |
| `java.jdt.ls.lombokSupport.enabled: true` | 加载 Lombok 注解处理器 |
| `java.configuration.runtimes` → JDK 21 | 项目声明的是 Java 21 |
| `files.watcherExclude` / `search.exclude` | 排除 1.5GB 数据集与 node_modules，避免索引卡死 |

---

## 四、你需要做的三步

改完配置后，VSCode 需要重新导入项目才会生效。

### 第 1 步：重载窗口

`Cmd+Shift+P` → 输入 `Reload Window` → 回车

### 第 2 步：清理 Java 工作区（关键）

`Cmd+Shift+P` → 输入 `Java: Clean Java Language Server Workspace` → 回车 → 选 **Reload and delete**

这一步会清掉之前失败的导入缓存。**不做这步，配置改了也不生效。**

### 第 3 步：等右下角 Java 进度条跑完

首次导入需要下载/索引依赖，视网络情况 1~5 分钟。
完成后右下角状态栏应显示 `Java: Ready`（截图里已经是这个状态）。

---

## 五、验证是否修好

### 快速验证

打开 `AnalyticsController.java`，看第 31 行 `@RequiredArgsConstructor` 是否还有红色波浪线。

### 彻底验证

1. 打开 `AnalyticsController.java`，把光标放到 `analyticsService` 字段上
2. 按 `F12`（跳转到定义）——如果能跳到 `AnalyticsService`，说明 Lombok 生成的构造函数已生效

或者：

```bash
# 在 VSCode 里打开终端
cd carbon-platform/backend
mvn -s ../scripts/maven-settings.xml clean compile
# 应输出 BUILD SUCCESS
```

---

## 六、如果还有报错

### 情况 A：还是同一批 Lombok 报错

说明导入仍然失败。打开 `查看 → 输出 → 选择 "Java Language Server"`，看有没有
`HttpTimeoutException`。若有：

1. 确认 `.vscode/settings.json` 里的 maven 路径正确（绝对路径更保险）：
   ```json
   "java.configuration.maven.userSettings": "<项目根目录>/scripts/maven-settings.xml"
   ```
2. `Java: Clean Java Language Server Workspace` 再来一次

### 情况 B：报「无法解析符号 AnalyticsService」这类真实引用错误

说明导入部分成功但 classpath 不全。检查：

```bash
# 依赖是否下载完整
cd carbon-platform/backend
mvn -s ../scripts/maven-settings.xml dependency:resolve
```

### 情况 C：整个 VSCode 很卡

根因是打开的工作区根目录 `~/carbon-2` 里有：
- `building-data-genome-project-2/`（1.5GB 数据集）
- `carbon-platform/.mysql/`（8GB 数据库文件）
- `carbon-platform/.venv/`（495MB）
- `node_modules/`

语言服务器会尝试索引这些。`.vscode/settings.json` 里已排除，但**更彻底的做法是直接打开后端目录**：

```
File → Open Folder → 选择 <项目根目录>/backend
```

这样只加载 Java 项目，最快最稳。前端另开一个窗口或用终端。

---

## 七、经验教训

| 教训 | 说明 |
|---|---|
| **IDE 报错 ≠ 代码错误** | 遇到大批量爆红先跑一次 `mvn compile`，能过就说明是 IDE 问题 |
| **报错信息可能完全误导** | 「Flexible Constructor Bodies 需要 Java 25」和真实问题（Lombok 未加载）毫无关系 |
| **Lombok 项目要确认注解处理生效** | 看能否 `F12` 跳转到 `@Data` / `@RequiredArgsConstructor` 生成的成员 |
| **IDE 也要用镜像** | `mvn` 走镜像不代表 VSCode 走镜像，要单独配 `java.configuration.maven.userSettings` |
| **编译时别开着服务** | 本项目还踩过「后端运行时编译导致 class 文件损坏、报 Unresolved compilation problem」，正确顺序是「先停服务 → clean compile → 再启动」 |
