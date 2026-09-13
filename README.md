# Lifeloom

插件化的模拟人生类游戏（开源）：高自由度、高可玩性，一切功能皆插件（含基础玩法）；Android 与桌面双端并行开发。

## 当前状态

核心 M2 权限已完成（权限为独立系统插件）：非系统插件的跨边界操作（访问他方机制状态、调用他方钩子）经核心极薄闸门转发给权限插件；权限插件负责放行决策与用户提示；未装权限插件时一律拒绝（fail-closed）。连同 M0 运行骨架、M1 热替换，桌面端可演示全流程，21 个单元测试覆盖；Android 外壳骨架可构建 APK 并随包携带核心库。底层系统插件层整体设计已定稿（草案 v0.3，14 个成员，见 [docs/底层插件设计.md](docs/底层插件设计.md)）；调度与参数存储（M3）在后续里程碑中加入。

## 构建与运行

需要 JDK 17，使用项目自带的 Gradle Wrapper（`./gradlew`）。

构建桌面端、插件与 Android 调试 APK：

```bash
./gradlew :core:test :shell-desktop:installDist \
  :examples:demo-plugin:jar :examples:demo-plugin-v2:jar \
  :examples:neighbor-plugin:jar :system-plugins:permissions-plugin:jar \
  :shell-android:assembleDebug
```

Android APK 产物位于 `shell-android/build/outputs/apk/debug/`。

### 演示一：M1 热替换（装载 → 调用 → 升级 → 数据不断档）

```bash
mkdir -p run/m1-plugins run/m1-updates
cp examples/demo-plugin/build/libs/*.jar run/m1-plugins/
cp examples/demo-plugin-v2/build/libs/*.jar run/m1-updates/
shell-desktop/build/install/shell-desktop/bin/shell-desktop \
  --plugins "$PWD/run/m1-plugins" \
  --invoke dev.lifeloom.demo.greeting.hello \
  --replace dev.lifeloom.demo="$PWD/run/m1-updates/demo-plugin-v2-0.1.0-SNAPSHOT.jar" \
  --invoke dev.lifeloom.demo.greeting.hello
```

### 演示二：M2 权限（fail-closed 与用户放行提示）

```bash
mkdir -p run/m2-with run/m2-without
# 有权限插件（0- 前缀保证先装载）
cp system-plugins/permissions-plugin/build/libs/*.jar run/m2-with/0-permissions-plugin.jar
cp examples/demo-plugin/build/libs/*.jar run/m2-with/1-demo-plugin.jar
cp examples/neighbor-plugin/build/libs/*.jar run/m2-with/2-neighbor-plugin.jar
# 无权限插件
cp examples/demo-plugin/build/libs/*.jar run/m2-without/1-demo-plugin.jar
cp examples/neighbor-plugin/build/libs/*.jar run/m2-without/2-neighbor-plugin.jar

# A：未装权限插件 —— 三方跨边界操作被拒绝（fail-closed）
shell-desktop/build/install/shell-desktop/bin/shell-desktop \
  --plugins "$PWD/run/m2-without" --invoke dev.lifeloom.neighbor.check

# B：装有权限插件 —— 询问用户（管道输入 y 放行、n 拒绝，决定被记忆）
printf 'y\nn\n' | shell-desktop/build/install/shell-desktop/bin/shell-desktop \
  --plugins "$PWD/run/m2-with" \
  --invoke dev.lifeloom.neighbor.check \
  --invoke dev.lifeloom.permissions.status
```

## 文档入口

领域词表见 [CONTEXT.md](CONTEXT.md)；需求对齐与决策索引见 [docs/对齐状态.md](docs/对齐状态.md)；架构决策见 [docs/adr/](docs/adr)；核心设计与实施进展见 [docs/核心设计.md](docs/核心设计.md)；底层插件层设计见 [docs/底层插件设计.md](docs/底层插件设计.md)；技术核查笔记见 [docs/技术核查-Java插件装载与热装载.md](docs/技术核查-Java插件装载与热装载.md)；本机构建要点见 [docs/构建环境笔记.md](docs/构建环境笔记.md)。
