package xyz.wagyourtail.unimined.cpl;

import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.Map;

public interface SimplePluginManagerAccessor {

    List<Plugin> cpl$getPlugins();

    Map<String, Plugin> cpl$getLookupNames();

    default void cpl$addPlugin(Plugin plugin) {
        cpl$getPlugins().add(plugin);
        cpl$getLookupNames().put(plugin.getDescription().getName(), plugin);
    }

}
