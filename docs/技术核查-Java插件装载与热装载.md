# 技术核查：Java 插件装载与热装载（初版）

日期：2026-09-12

需求（已对齐）：运行时安装插件立即生效；已装插件整体替换/升级（drain：停止接受新请求 → 等待执行完成 → 替换 → 恢复）；数据不断档；不做执行中代码的原地热补丁；Java 非硬要求、尽可能满足。

## 结论

可行。做法是“一个插件一个类加载器，换装＝换加载器”：桌面（JVM）与 Android（ART/dex）都支持这条路径；它与既定的 drain + 整体替换 + 数据按机制归属的设计天然吻合，不需要任何“原地热补丁”能力。

## 桌面（JVM）

- 插件以 JAR 分发；核心为每个插件建立独立的类加载器（URLClassLoader 体系），实现类空间隔离。
- 替换＝停用插件 → 断开对旧加载器的引用 → 用新加载器加载新版本 → 重新初始化。JVM 规定：当定义类的加载器不可达时，类可被卸载（GC 回收）。
- 已知坑：插件起的线程必须可停止；核心不得长期持有插件类/实例引用；静态状态随加载器走；注意 ThreadLocal、JDBC 驱动等常见泄漏点。
- 可参考：PF4J（pf4j/pf4j，约 2.75k star，Apache-2.0，Java）——轻量插件框架，提供插件生命周期与类加载隔离，是这类场景的成熟先例。

## Android（ART）

- 插件以 dex/jar（含 classes.dex）分发，用 DexClassLoader 从应用存储加载；换装＝换新的 DexClassLoader 实例（旧类随不可达加载器回收；具体释放时机受系统版本与可达性影响，需实测）。
- Android 14（targetSdk 34）起：动态加载的代码文件必须标记为只读，否则系统抛异常——加载前将文件设为只读即可（官方行为变更文档已核实）。
- 首次加载有 dex 优化（dexopt）开销，可预热；InMemoryDexClassLoader（API 26+）是可选形态。
- 本项目为自分发开源游戏，不受 Google Play“动态代码加载”政策限制；受影响的只是系统层面的上述只读要求。

## 共同注意

- 需要制定“插件开发规约”：线程必须可停止、资源必须关闭、不得依赖跨代静态状态——否则替换会泄漏内存（表现为进程内存缓慢增长）。
- 支持“新旧两代短暂共存”：替换过程中新旧加载器短暂并存，drain 机制正是为此。
- 性能：JVM/ART 执行效率满足需求；待实测项：加载耗时、替换后内存回收表现、大量插件下的类加载开销。

## 待定

- 插件打包与分发格式（各端产物如何组织；签名与完整性校验放在加载器的哪一层）。
- “声明式 UI”的载体（UI 插件桥接的目标）——等插件细谈。
- drain 的实现细节（调度器在何种执行边界暂停）。
- 原型验证：在 Android + 桌面各做一个“加载 → 替换 → 回收”最小实验。

## 引用

- Android 14 行为变更（动态代码加载只读要求）：https://developer.android.com/about/versions/14/behavior-changes-14
- Android 动态代码加载风险说明：https://developer.android.com/privacy-and-security/risks/dynamic-code-loading
- PF4J：https://github.com/pf4j/pf4j
- JVM 类加载器与类卸载（排查思路）：https://adhdecode.com/articles/jvm/jvm-classloader-memory-leak