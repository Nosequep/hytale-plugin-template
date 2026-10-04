package org.example;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

/**
 * Minimal Hytale plugin: it only registers {@link TestPluginCommand}.
 *
 * <p>Loaded through the classpath route set up by the {@code copyPluginClasses} and
 * {@code debugServer} Gradle tasks, so {@code hytale.plugin.mainClass} must point here.
 */
public final class TestPlugin extends JavaPlugin {

    public TestPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        getCommandRegistry().registerCommand(new TestPluginCommand());
    }
}
