package dev.lifeloom.shell.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import dev.lifeloom.core.Core;
import dev.lifeloom.core.JvmPluginLoader;

/**
 * Android 外壳（M0 骨架）：验证核心库在 Android 上可加载运行。
 * 插件装载的 Android 实现（DexClassLoader）随 M0 后续加入。
 */
public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        StringBuilder text = new StringBuilder();
        text.append("Lifeloom 核心 M0 骨架\n\n");
        try {
            Core core = new Core(new JvmPluginLoader());
            text.append("核心实例：创建成功\n");
            text.append("已装载插件：").append(core.pluginIds().size()).append(" 个\n");
            text.append("已注册机制：").append(core.registry().mechanisms().size()).append(" 个\n\n");
            text.append("（Android 端插件装载器 DexClassLoader 待加入）");
        } catch (Throwable t) {
            text.append("核心初始化失败：").append(t);
        }

        TextView view = new TextView(this);
        view.setText(text.toString());
        view.setTextSize(16f);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        view.setPadding(pad, pad, pad, pad);
        setContentView(view);
    }
}