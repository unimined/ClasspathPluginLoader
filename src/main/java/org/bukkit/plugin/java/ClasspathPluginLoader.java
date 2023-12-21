package org.bukkit.plugin.java;

import org.apache.commons.lang.Validate;
import org.bukkit.Server;
import org.bukkit.Warning;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.configuration.serialization.ConfigurationSerialization;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.*;
import org.bukkit.plugin.java.ClasspathPluginClassLoader;
import org.bukkit.plugin.java.JavaPlugin;
import org.yaml.snakeyaml.error.YAMLException;
import xyz.wagyourtail.unimined.cpl.SimplePluginManagerAccessor;

import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class ClasspathPluginLoader implements PluginLoader {
    private static final String PLUGIN_FOLDER_GROUPS = "cpl.pluginGroups";
    final Server server;
    private final SimplePluginManagerAccessor pluginManager;
    private final Map<String, Class<?>> classes = new HashMap<>();
    private final List<ClasspathPluginClassLoader> loaders = new CopyOnWriteArrayList<>();


    public ClasspathPluginLoader(Server server) {
        this.pluginManager = (SimplePluginManagerAccessor) server.getPluginManager();
        this.server = server;
    }

    @Override
    public Plugin loadPlugin(File file) throws UnknownDependencyException {
        return null;
    }

    public List<Plugin> loadAll() {
        String pluginFolderGroups = System.getProperty(PLUGIN_FOLDER_GROUPS);
        if (pluginFolderGroups == null) {
            return Collections.emptyList();
        }
        List<Plugin> plugins = new ArrayList<>();

        for (String group : pluginFolderGroups.split("::")) {
            String[] files = group.split(":");
            File[] pluginFiles = new File[files.length];
            for (int i = 0; i < files.length; i++) {
                pluginFiles[i] = new File(files[i]);
            }
            try {
                plugins.add(loadPlugin(new HashSet<>(Arrays.asList(pluginFiles))));
            } catch (InvalidPluginException e) {
                this.server.getLogger().log(Level.SEVERE, "Could not load '" + group + "'", e);
            }
        }

        for (Plugin plugin : plugins) {
            pluginManager.cpl$addPlugin(plugin);
        }

        return plugins;
    }

    public Plugin loadPlugin(Set<File> files) throws InvalidPluginException {
        Validate.notNull(files, "Files cannot be null");

        final PluginDescriptionFile description;
        try {
            description = getPluginDescription(files);
        } catch (InvalidDescriptionException ex) {
            throw new InvalidPluginException(ex);
        }

        final File parentFile = server.getUpdateFolderFile().getParentFile();
        final File dataFolder = new File(parentFile, description.getName());
        @SuppressWarnings("deprecation")
        final File oldDataFolder = new File(parentFile, description.getRawName());

        // Found old data folder
        if (dataFolder.equals(oldDataFolder)) {
            // They are equal -- nothing needs to be done!
        } else if (dataFolder.isDirectory() && oldDataFolder.isDirectory()) {
            server.getLogger().warning(String.format(
                "While loading %s (%s) found old-data folder: `%s' next to the new one `%s'",
                description.getFullName(),
                files,
                oldDataFolder,
                dataFolder
            ));
        } else if (oldDataFolder.isDirectory() && !dataFolder.exists()) {
            if (!oldDataFolder.renameTo(dataFolder)) {
                throw new InvalidPluginException("Unable to rename old data folder: `" + oldDataFolder + "' to: `" + dataFolder + "'");
            }
            server.getLogger().log(Level.INFO, String.format(
                "While loading %s (%s) renamed data folder: `%s' to `%s'",
                description.getFullName(),
                files,
                oldDataFolder,
                dataFolder
            ));
        }

        if (dataFolder.exists() && !dataFolder.isDirectory()) {
            throw new InvalidPluginException(String.format(
                "Projected datafolder: `%s' for %s (%s) exists and is not a directory",
                dataFolder,
                description.getFullName(),
                files
            ));
        }

        for (final String pluginName : description.getDepend()) {
            Plugin current = server.getPluginManager().getPlugin(pluginName);

            if (current == null) {
                throw new UnknownDependencyException("Unknown dependency " + pluginName + ". Please download and install " + pluginName + " to run this plugin.");
            }
        }

        final ClasspathPluginClassLoader loader;
        try {
            loader = new ClasspathPluginClassLoader(this, getClass().getClassLoader(), description, dataFolder, files);
        } catch (InvalidPluginException ex) {
            throw ex;
        } catch (Throwable ex) {
            throw new InvalidPluginException(ex);
        }

        loaders.add(loader);

        return loader.plugin;
    }

    public PluginDescriptionFile getPluginDescription(File file) throws InvalidDescriptionException {
        return null;
    }

    public PluginDescriptionFile getPluginDescription(Set<File> files) throws InvalidDescriptionException {
        Validate.notNull(files, "Files cannot be null");

        JarFile jar = null;

        for (File file : files) {
            if (!file.exists()) continue;

            File plugin = new File(file, "plugin.yml");
            if (!plugin.exists()) continue;

            try (InputStream stream = new FileInputStream(plugin)) {
                return new PluginDescriptionFile(stream);
            } catch (IOException | YAMLException e) {
                throw new InvalidDescriptionException(e);
            }

        }

        throw new InvalidDescriptionException(new FileNotFoundException("Jar does not contain plugin.yml"));
    }

    public Pattern[] getPluginFileFilters() {
        return new Pattern[0];
    }

    Class<?> getClassByName(String name) {
        Class<?> cachedClass = (Class)this.classes.get(name);
        if (cachedClass != null) {
            return cachedClass;
        } else {
            Iterator var4 = this.loaders.iterator();

            while(var4.hasNext()) {
                ClasspathPluginClassLoader loader = (ClasspathPluginClassLoader)var4.next();

                try {
                    cachedClass = loader.findClass(name);
                } catch (ClassNotFoundException var5) {
                }

                if (cachedClass != null) {
                    return cachedClass;
                }
            }

            return null;
        }
    }

    void setClass(final String name, final Class<?> clazz) {
        if (!classes.containsKey(name)) {
            classes.put(name, clazz);

            if (ConfigurationSerializable.class.isAssignableFrom(clazz)) {
                Class<? extends ConfigurationSerializable> serializable = clazz.asSubclass(ConfigurationSerializable.class);
                ConfigurationSerialization.registerClass(serializable);
            }
        }
    }

    private void removeClass(String name) {
        Class<?> clazz = classes.remove(name);

        try {
            if ((clazz != null) && (ConfigurationSerializable.class.isAssignableFrom(clazz))) {
                Class<? extends ConfigurationSerializable> serializable = clazz.asSubclass(ConfigurationSerializable.class);
                ConfigurationSerialization.unregisterClass(serializable);
            }
        } catch (NullPointerException ex) {
            // Boggle!
            // (Native methods throwing NPEs is not fun when you can't stop it before-hand)
        }
    }

    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(Listener listener, final Plugin plugin) {
        Validate.notNull(plugin, "Plugin can not be null");
        Validate.notNull(listener, "Listener can not be null");

        boolean useTimings = server.getPluginManager().useTimings();
        Map<Class<? extends Event>, Set<RegisteredListener>> ret = new HashMap<Class<? extends Event>, Set<RegisteredListener>>();
        Set<Method> methods;
        try {
            Method[] publicMethods = listener.getClass().getMethods();
            methods = new HashSet<Method>(publicMethods.length, Float.MAX_VALUE);
            for (Method method : publicMethods) {
                methods.add(method);
            }
            for (Method method : listener.getClass().getDeclaredMethods()) {
                methods.add(method);
            }
        } catch (NoClassDefFoundError e) {
            plugin.getLogger().severe("Plugin " + plugin.getDescription().getFullName() + " has failed to register events for " + listener.getClass() + " because " + e.getMessage() + " does not exist.");
            return ret;
        }

        for (final Method method : methods) {
            final EventHandler eh = method.getAnnotation(EventHandler.class);
            if (eh == null) continue;
            final Class<?> checkClass;
            if (method.getParameterTypes().length != 1 || !Event.class.isAssignableFrom(checkClass = method.getParameterTypes()[0])) {
                plugin.getLogger().severe(plugin.getDescription().getFullName() + " attempted to register an invalid EventHandler method signature \"" + method.toGenericString() + "\" in " + listener.getClass());
                continue;
            }
            final Class<? extends Event> eventClass = checkClass.asSubclass(Event.class);
            method.setAccessible(true);
            Set<RegisteredListener> eventSet = ret.get(eventClass);
            if (eventSet == null) {
                eventSet = new HashSet<RegisteredListener>();
                ret.put(eventClass, eventSet);
            }

            for (Class<?> clazz = eventClass; Event.class.isAssignableFrom(clazz); clazz = clazz.getSuperclass()) {
                // This loop checks for extending deprecated events
                if (clazz.getAnnotation(Deprecated.class) != null) {
                    Warning warning = clazz.getAnnotation(Warning.class);
                    Warning.WarningState warningState = server.getWarningState();
                    if (!warningState.printFor(warning)) {
                        break;
                    }
                    plugin.getLogger().log(
                        Level.WARNING,
                        String.format(
                            "\"%s\" has registered a listener for %s on method \"%s\", but the event is Deprecated." +
                                " \"%s\"; please notify the authors %s.",
                            plugin.getDescription().getFullName(),
                            clazz.getName(),
                            method.toGenericString(),
                            (warning != null && warning.reason().length() != 0) ? warning.reason() : "Server performance will be affected",
                            Arrays.toString(plugin.getDescription().getAuthors().toArray())),
                        warningState == Warning.WarningState.ON ? new AuthorNagException(null) : null);
                    break;
                }
            }

            EventExecutor executor = new EventExecutor() {
                public void execute(Listener listener, Event event) throws EventException {
                    try {
                        if (!eventClass.isAssignableFrom(event.getClass())) {
                            return;
                        }
                        method.invoke(listener, event);
                    } catch (InvocationTargetException ex) {
                        throw new EventException(ex.getCause());
                    } catch (Throwable t) {
                        throw new EventException(t);
                    }
                }
            };
            if (useTimings) {
                eventSet.add(new TimedRegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
            } else {
                eventSet.add(new RegisteredListener(listener, executor, eh.priority(), plugin, eh.ignoreCancelled()));
            }
        }
        return ret;
    }

    public void enablePlugin(Plugin plugin) {
        Validate.isTrue(plugin instanceof JavaPlugin, "Plugin is not associated with this PluginLoader");
        if (!plugin.isEnabled()) {
            plugin.getLogger().info("Enabling " + plugin.getDescription().getFullName());
            JavaPlugin jPlugin = (JavaPlugin)plugin;
            ClasspathPluginClassLoader pluginLoader = (ClasspathPluginClassLoader)jPlugin.getClassLoader();
            if (!this.loaders.contains(pluginLoader)) {
                this.loaders.add(pluginLoader);
                this.server.getLogger().log(Level.WARNING, "Enabled plugin with unregistered PluginClassLoader " + plugin.getDescription().getFullName());
            }

            try {
                jPlugin.setEnabled(true);
            } catch (Throwable var5) {
                this.server.getLogger().log(Level.SEVERE, "Error occurred while enabling " + plugin.getDescription().getFullName() + " (Is it up to date?)", var5);
            }

            this.server.getPluginManager().callEvent(new PluginEnableEvent(plugin));
        }

    }

    public void disablePlugin(Plugin plugin) {
        Validate.isTrue(plugin instanceof JavaPlugin, "Plugin is not associated with this PluginLoader");

        if (plugin.isEnabled()) {
            String message = String.format("Disabling %s", plugin.getDescription().getFullName());
            plugin.getLogger().info(message);

            server.getPluginManager().callEvent(new PluginDisableEvent(plugin));

            JavaPlugin jPlugin = (JavaPlugin) plugin;
            ClassLoader cloader = jPlugin.getClassLoader();

            try {
                jPlugin.setEnabled(false);
            } catch (Throwable ex) {
                server.getLogger().log(Level.SEVERE, "Error occurred while disabling " + plugin.getDescription().getFullName() + " (Is it up to date?)", ex);
            }

            loaders.remove(jPlugin.getDescription().getName());

            if (cloader instanceof ClasspathPluginClassLoader) {
                ClasspathPluginClassLoader loader = (ClasspathPluginClassLoader) cloader;
                Set<String> names = loader.getClasses();

                for (String name : names) {
                    removeClass(name);
                }
            }
        }
    }

}
