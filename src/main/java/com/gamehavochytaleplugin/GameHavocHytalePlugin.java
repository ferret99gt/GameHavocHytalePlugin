package com.gamehavochytaleplugin;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import java.util.logging.Level;

public final class GameHavocHytalePlugin extends JavaPlugin
{
  public GameHavocHytalePlugin(JavaPluginInit init)
  {
    super(init);
  }

  @Override
  protected void setup()
  {
    getLogger().at(Level.INFO).log("GameHavocHytalePlugin: setup()");
    registerSubPlugin(new HydrationTaleSubPlugin(getLogger()));
    registerSubPlugin(new SleepyTaleSubPlugin(getLogger()));
    registerSubPlugin(new IsekaiTaleSubPlugin(getLogger()));
  }

  private void registerSubPlugin(GameHavocSubPlugin subPlugin)
  {
    getLogger().at(Level.INFO).log("GameHavocHytalePlugin: enabling subplugin " + subPlugin.name());
    subPlugin.setup(this);
  }
}
