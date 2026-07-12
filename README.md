# MC1.6.4-Forge-Template

Minecraft 1.6.4 Forge Mod 开发模板(ForgeGradle 7.0.28 + Gradle 9.5 + Forge 9.11.1.960)。

## 快速开始

1. 替换 mod 标识:`build.gradle` 的 `group`/`base.archivesName`,以及 `src/main/java/.../ExampleMod.java` 与 `src/main/resources/mcmod.info` 里的 modid/name/package。
2. 构建:
   ```bash
   ./gradlew build
   ```
   产物在 `build/libs/`。

## dev 运行环境准备(首次必做)

1. **下载游戏资产**:
   ```bash
   ./gradlew downloadGameAssets
   ```
   下载 1.6.4 legacy 布局资产(语言文件/音效/字体)。dev 运行入口 slime-launcher 把资产目录固定解析为系统默认 `%APPDATA%\.minecraft\assets`(既不读工作目录,也无法用 run 的环境变量覆盖),因此任务直接下载到该目录。**缺少则游戏无法切换语言(只有英文)且没有任何声音。**
2. **放入 LegacyJavaFixer**:用 Java 8+ 运行时跑 `runClient`/`runServer` 时,必须在 `runs/main/client/mods/`(server 同理)放入 [`legacyjavafixer-1.0.jar`](https://github.com/MinecraftForge/LegacyJavaFixer),否则 FML 在新 JVM 上因模组排序问题启动崩溃(1.6.4 Forge 自身缺陷,与模板无关)。
3. 运行:
   ```bash
   ./gradlew runClient
   ./gradlew runServer
   ```

## 关键约束与设计说明

- **Java 7 是字节码硬上限**:1.6.4 FML 内置 ASM 4.1 只能解析 class 版本 ≤51。`-source/-target` 可保持 1.6 或提到 1.7,**但绝不可到 1.8**——Java 8 字节码(52)会让 FML 注解扫描抛 IllegalArgumentException 并丢弃整个 mod(dev 与生产皆然)。
- **资源输出目录**:`build.gradle` 用 `sourceSets.main.output.resourcesDir` 把资源指到 `build/classes/java/main`。因为 dev 环境 FML 把 `@Mod` 类所在目录当作资源包根,若资源留在 Gradle 默认的 `build/resources/main`,游戏内 mod 贴图会紫黑、语言键不翻译。jar 打包不受影响。
- **版本号占位符**:`mcmod.info` 的 `${version}` 由 `processResources` 在构建期展开,不要手写死。
