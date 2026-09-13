# Lifeloom

插件化的模拟人生类游戏（开源）：高自由度、高可玩性，一切功能皆插件（含基础玩法）；Android 与桌面双端并行开发。

## 当前状态

核心 M0 运行骨架已跑通。桌面端可完整演示“装载插件 → 注册机制 → 调用钩子 → 卸载”流程；Android 外壳骨架已能构建 APK 并随包携带核心库。热替换（M1）、权限（M2）、调度与参数存储（M3）在后续里程碑中加入。

## 构建与运行

需要 JDK 17，使用项目自带的 Gradle Wrapper（`./gradlew`）。

构建桌面端与 Android 调试 APK：

```bash
./gradlew :core:build :shell-desktop:installDist :shell-android:assembleDebug
```

Android APK 产物位于 `shell-android/build/outputs/apk/debug/`。

桌面端演示（装载示例插件并调用钩子）：

```bash
./gradlew :examples:demo-plugin:jar
mkdir -p run/plugins
cp examples/demo-plugin/build/libs/*.jar run/plugins/
./gradlew :shell-desktop:run --args="--plugins $PWD/run/plugins --invoke dev.lifeloom.demo.greeting.hello"
```

## 文档入口

领域词表见 [CONTEXT.md](CONTEXT.md)；需求对齐与决策索引见 [docs/对齐状态.md](docs/对齐状态.md)；架构决策见 [docs/adr/](docs/adr)；核心设计与实施进展见 [docs/核心设计.md](docs/核心设计.md)；技术核查笔记见 [docs/技术核查-Java插件装载与热装载.md](docs/技术核查-Java插件装载与热装载.md)；本机构建要点见 [docs/构建环境笔记.md](docs/构建环境笔记.md)。