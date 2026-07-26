package dev.launchfix;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;

import net.minecraft.launchwrapper.ITweaker;
import net.minecraft.launchwrapper.Launch;
import net.minecraft.launchwrapper.LaunchClassLoader;
import net.minecraft.launchwrapper.LogWrapper;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;

/**
 * launchwrapper 1.8 {@code Launch} 的等价实现，修掉 JDK 8u20+ 下的 ConcurrentModificationException。
 *
 * <p>原版 {@code Launch.launch()} 的第二个循环用 {@code Iterator.remove()} 遍历 tweaker 列表，
 * 而 1.6.4 的 {@code FMLInjectionAndSortingTweaker.acceptOptions()} 会在循环体内调用
 * {@code CoreModManager.sortTweakList()} → {@code Collections.sort(tweakers)}，
 * 排的正是 launchwrapper 正在遍历的那个 list。
 *
 * <p>8u20 之前的 {@code Collections.sort} 把元素拷进临时数组排完写回，不改 {@code modCount}，
 * 所以迭代器存活；JDK-8030848（随 8u20 发布）改成委托 {@code List.sort()} 后，
 * {@code ArrayList.sort()} 结尾无条件 {@code modCount++}，
 * 于是紧接着的 {@code it.remove()} 在 {@code checkForComodification()} 抛 CME，
 * FML 启动被打断（{@code Unable to launch}）。
 *
 * <p>Forge 在 1.7.10 时代修了 FML 那一侧（见其 {@code sortTweakList} 里引用 JDK-8032636 的注释），
 * 1.6.4 已停止维护没拿到，官方补救是 LegacyJavaFixer coremod。本类修的是同一个 bug 的另一端，
 * 好处是 dev 环境无需额外 jar；但它只作用于 {@code runClient}/{@code runServer}，
 * 生产环境（正式发布的 mod 被玩家用 8u20+ 运行）仍需 LegacyJavaFixer。
 *
 * <p>这里改成「每轮取列表头、处理完按引用移除」的排空写法：不持有跨回调的迭代器，
 * 因此 tweaker 在回调里排序或增删列表都不会炸。
 *
 * <p>行为等价性：原版在 Java 7 下 {@code it.remove()} 删的是索引 0（游标在 1 时回退到 0），
 * 下一轮 {@code next()} 又取排序后的新索引 0，即「始终处理列表头」。本实现语义相同，
 * 且按引用移除，保证移除的正是刚处理过的那个实例（FML 会把同一个 sorting tweaker 放进列表两次，
 * 按引用删可避免误删未处理的元素），同时保证循环必然收敛。
 */
public final class CmeSafeLaunch {
    private static final String DEFAULT_TWEAK = "net.minecraft.launchwrapper.VanillaTweaker";

    private CmeSafeLaunch() {
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static void launch(String[] args) {
        // 与原版一致：以当前 classpath 建 LaunchClassLoader，并初始化 blackboard。
        // Launch 的构造器是 private,这里直接写它的 public static 字段,
        // 保证 FML 侧读到的 Launch.classLoader / blackboard / minecraftHome / assetsDir 都有值。
        final URLClassLoader ucl = (URLClassLoader) CmeSafeLaunch.class.getClassLoader();
        final LaunchClassLoader classLoader = new LaunchClassLoader(ucl.getURLs());
        Launch.classLoader = classLoader;
        Launch.blackboard = new HashMap<String, Object>();

        final OptionParser parser = new OptionParser();
        parser.allowsUnrecognizedOptions();

        final OptionSpec<String> profileOption = parser.accepts("version", "The version we launched with").withRequiredArg();
        final OptionSpec<File> gameDirOption = parser.accepts("gameDir", "Alternative game directory").withRequiredArg().ofType(File.class);
        final OptionSpec<File> assetsDirOption = parser.accepts("assetsDir", "Assets directory").withRequiredArg().ofType(File.class);
        final OptionSpec<String> tweakClassOption = parser.accepts("tweakClass", "Tweak class(es) to load").withRequiredArg().defaultsTo(DEFAULT_TWEAK);
        final OptionSpec<String> nonOption = parser.nonOptions();

        final OptionSet options = parser.parse(args);
        Launch.minecraftHome = options.valueOf(gameDirOption);
        Launch.assetsDir = options.valueOf(assetsDirOption);
        final File minecraftHome = Launch.minecraftHome;
        final File assetsDir = Launch.assetsDir;
        final String profileName = options.valueOf(profileOption);
        final List<String> tweakClassNames = new ArrayList<String>(options.valuesOf(tweakClassOption));

        final List<String> argumentList = new ArrayList<String>();
        // tweaker 可以往这个列表里追加自己发现的 tweaker，实现级联加载
        Launch.blackboard.put("TweakClasses", tweakClassNames);
        // 所有 tweaker 共享这份参数列表，可检查某个参数是否已存在
        Launch.blackboard.put("ArgumentList", argumentList);

        // 防重复：万一某个 tweaker 把自己又加了一遍
        final Set<String> allTweakerNames = new HashSet<String>();
        // 最终确定的 tweaker 列表
        final List<ITweaker> allTweakers = new ArrayList<ITweaker>();
        try {
            final List<ITweaker> tweakers = new ArrayList<ITweaker>(tweakClassNames.size() + 1);
            Launch.blackboard.put("Tweaks", tweakers);
            // 命令行上第一个 tweaker 是 primary，负责给出最终的启动目标
            ITweaker primaryTweaker = null;
            do {
                // 第一阶段：把 tweak 类名排空并实例化。
                // 用 remove(0) 而不是迭代器：tweaker 构造器可能往 tweakClassNames 里加东西。
                while (!tweakClassNames.isEmpty()) {
                    final String tweakName = tweakClassNames.remove(0);
                    if (!allTweakerNames.add(tweakName)) {
                        // 已处理过就跳过；这里必须已经从列表里移除，否则外层 do-while 死循环
                        LogWrapper.log(Level.WARNING, "Tweak class name %s has already been visited -- skipping", tweakName);
                        continue;
                    }
                    LogWrapper.log(Level.INFO, "Loading tweak class name %s", tweakName);

                    // tweak 类必须由父加载器加载
                    classLoader.addClassLoaderExclusion(tweakName.substring(0, tweakName.lastIndexOf('.')));
                    final ITweaker tweaker = (ITweaker) Class.forName(tweakName, true, classLoader).newInstance();
                    tweakers.add(tweaker);

                    if (primaryTweaker == null) {
                        LogWrapper.log(Level.INFO, "Using primary tweak class name %s", tweakName);
                        primaryTweaker = tweaker;
                    }
                }

                // 第二阶段：依次调用刚实例化出来的 tweaker。
                // 每轮重新读列表头，回调里的排序/增删都安全（这就是 CME 的修复点）。
                while (!tweakers.isEmpty()) {
                    final ITweaker tweaker = tweakers.get(0);
                    LogWrapper.log(Level.INFO, "Calling tweak class %s", tweaker.getClass().getName());
                    tweaker.acceptOptions(options.valuesOf(nonOption), minecraftHome, assetsDir, profileName);
                    tweaker.injectIntoClassLoader(classLoader);
                    allTweakers.add(tweaker);
                    // 按引用移除刚处理完的那个实例（acceptOptions 可能已经把列表重排过）
                    removeFirstIdentity(tweakers, tweaker);
                }
                // injectIntoClassLoader 可能级联注入了新的 tweak 类名，继续下一轮
            } while (!tweakClassNames.isEmpty());

            for (final ITweaker tweaker : allTweakers) {
                argumentList.addAll(Arrays.asList(tweaker.getLaunchArguments()));
            }

            final String launchTarget = primaryTweaker.getLaunchTarget();
            final Class<?> clazz = Class.forName(launchTarget, false, classLoader);
            final Method mainMethod = clazz.getMethod("main", new Class[]{String[].class});

            LogWrapper.info("Launching wrapped minecraft {%s}", launchTarget);
            mainMethod.invoke(null, (Object) argumentList.toArray(new String[argumentList.size()]));
        } catch (Exception e) {
            LogWrapper.log(Level.SEVERE, e, "Unable to launch");
        }
    }

    /** 按引用（而非 equals）移除首个匹配元素，移除成功返回 true。 */
    private static boolean removeFirstIdentity(List<ITweaker> list, ITweaker target) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i) == target) {
                list.remove(i);
                return true;
            }
        }
        // 理论上不会发生；真发生了就删掉列表头，保证循环收敛而不是死循环
        if (!list.isEmpty()) {
            LogWrapper.log(Level.WARNING, "Tweaker %s vanished from the tweak list during its own callback", target.getClass().getName());
            list.remove(0);
        }
        return false;
    }
}
