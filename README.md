# MC1.6.4-Forge-Template

Minecraft 1.6.4 Forge Mod 开发模板(ForgeGradle 7.0.28 + Gradle 9.5 + Forge 9.11.1.960)。

开箱即可在现代 JDK(Java 8u20 及以上)上跑 `runClient`/`runServer`,无需手动放入 LegacyJavaFixer。

## 快速开始

1. 替换 mod 标识:`build.gradle` 的 `group`/`base.archivesName`,以及 `src/main/java/.../ExampleMod.java` 与 `src/main/resources/mcmod.info` 里的 modid/name/package。
2. 构建:
   ```bash
   ./gradlew build
   ```
   产物在 `build/libs/`。

## dev 运行环境准备

**首次必做**——下载游戏资产:

```bash
./gradlew downloadGameAssets
```

下载 1.6.4 legacy 布局资产(语言文件/音效/字体)。dev 运行入口 slime-launcher 把资产目录固定解析为系统默认 `%APPDATA%\.minecraft\assets`(既不读工作目录,也无法用 run 的环境变量覆盖),因此任务直接下载到该目录。**缺少则游戏无法切换语言(只有英文)且没有任何声音。**

然后运行:

```bash
./gradlew runClient
./gradlew runServer
```

## 关键约束与设计说明

### Java 7 是字节码硬上限

1.6.4 FML 内置 ASM 4.1 只能解析 class 版本 ≤51。`-source/-target` 可保持 1.6 或提到 1.7,**但绝不可到 1.8**——Java 8 字节码(52)会让 FML 注解扫描抛 `IllegalArgumentException` 并丢弃整个 mod(dev 与生产皆然)。

注意这条约束针对**编译产物**,与跑 Gradle 用的 JDK 无关:Gradle daemon 用 JDK 25、toolchain 取 Java 8 编译出 1.6 字节码,是可以的(本模板即在此组合下验证)。游戏本身仍由 toolchain 的 Java 8 运行。

### 资源输出目录

`build.gradle` 用 `sourceSets.main.output.resourcesDir` 把资源指到 `build/classes/java/main`。因为 dev 环境 FML 把 `@Mod` 类所在目录当作资源包根,若资源留在 Gradle 默认的 `build/resources/main`,游戏内 mod 贴图会紫黑、语言键不翻译。jar 打包不受影响。

### CME 补丁启动器(`src/launchPatch/`)

在 **Java 8u20 及以上**的 JVM 上,原版 1.6.4 启动链必崩于:

```
java.util.ConcurrentModificationException
    at java.util.ArrayList$Itr.checkForComodification(ArrayList.java:911)
    at java.util.ArrayList$Itr.remove(ArrayList.java:875)
    at net.minecraft.launchwrapper.Launch.launch(Launch.java:114)
```

成因是三方共同作用:

1. launchwrapper 1.8 的 `Launch.launch()` 在循环体内调 `tweaker.acceptOptions(...)`,紧接着调 `it.remove()`——迭代器跨回调存活。
2. 1.6.4 FML 的 `CoreModManager.sortTweakList()` 里是裸的 `Collections.sort(tweakers, cmp)`,而这个 `tweakers` 正是上面那个迭代器遍历的 list。
3. [JDK-8030848](https://bugs.openjdk.org/browse/JDK-8030848) 在 **8u20** 改了 `Collections.sort`:此前是拷进临时数组排完写回、不动 `modCount`,改成委托 `List.sort()` 后,`ArrayList.sort()` 结尾无条件 `modCount++`。

于是 `it.remove()` 在 `checkForComodification()` 抛 CME。分界线是 8u20 而非"Java 8",早期 8u5 之类是能跑的。Forge 在 1.7.10 时代修了(`sortTweakList` 改用 `Arrays.sort` + `set` 回写,代码注释里还留着 JDK bug 链接),1.6.4 已停止维护没拿到。

本模板在 launchwrapper 一侧修:`src/launchPatch/java/dev/launchfix/CmeSafeLaunch.java` 是 `Launch` 的等价实现,把「持迭代器跨回调」改成「每轮取列表头、处理完按引用移除」,回调里怎么排序增删都不会炸。接入方式:

```groovy
sourceSets.register('launchPatch')
sourceSets.launchPatch.compileClasspath = sourceSets.main.compileClasspath
sourceSets.main.runtimeClasspath += sourceSets.launchPatch.output

// run 块内
environment.put("mainClass", "dev.launchfix.CmeSafeLaunch")
```

三个细节:

- **为什么改 env 的 `mainClass` 而不是 run DSL 的 `mainClass`**:slime-launcher 的 `LegacyDev` 只在外层 main 以 `net.minecraftforge.legacydev.` 开头时才生效,由它读 env `mainClass` 取真实入口,并负责补 `--tweakClass`/`--version`/`--assetsDir` 等参数。改 DSL 的 `mainClass` 会绕过整条 legacydev 路径,导致 `--tweakClass` 丢失、退回 `VanillaTweaker`。
- **为什么挂到 `main.runtimeClasspath`**:run 任务的 JVM classpath = slime-launcher 工具 jar + `main.runtimeClasspath`。run DSL 的 `classpath` 是**整体覆盖**语义(插件内部 `setClasspath`),用它会把 slime-launcher 自己挤掉,报 `找不到或无法加载主类 net.minecraftforge.launcher.Main`。
- **为什么用独立 sourceSet**:`jar` 任务打的是 `sourceSets.main.output`,补丁在 `launchPatch` 里,两者输出目录分离,**不会进发布 jar**(已验证)。追加 `runtimeClasspath` 只影响 run/test,不是 `jar` 的输入。顺带也避免 FML 在 dev 下把它当 mod 类扫描。

**发布须知**:本补丁只覆盖 dev 的 `runClient`/`runServer`。如果要发布 modpack、或让用户在生产 Forge 上跑,8u20+ 的终端用户仍需 [`legacyjavafixer-1.0.jar`](https://github.com/MinecraftForge/LegacyJavaFixer)(它在 FML 一侧替换 `sortTweakList`,与本补丁修的是同一个 bug 的两端)。

### 版本号占位符

`mcmod.info` 的 `${version}` 由 `processResources` 在构建期展开,不要手写死。

## 常见问题

**`runServer` 报 `UnsatisfiedLinkError: no lwjgl in java.library.path`**
server run 块的 `tweakClass` 写成了客户端的 `FMLTweaker`。它的 `getLaunchTarget()` 返回 `net.minecraft.client.main.Main`,于是 `runServer` 实际去启动了客户端,倒在 LWJGL 原生库上。应为 `FMLServerTweaker`。

**游戏只有英文、没有声音**
没跑 `./gradlew downloadGameAssets`。

**mod 贴图紫黑、语言键不翻译**
资源没输出到 `build/classes/java/main`,见上文「资源输出目录」。

**FML 日志报 `Unable to read a class file correctly` / `IllegalArgumentException`**
classpath 上有 Java 8+ 字节码的 jar,ASM 4.1 读不了。FML 会跳过该条目继续,通常不致命;若丢的是你自己的 mod,检查 `-source/-target` 是否被提到了 1.8。
