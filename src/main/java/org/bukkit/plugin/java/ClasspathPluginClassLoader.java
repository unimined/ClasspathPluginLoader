//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package org.bukkit.plugin.java;

import com.google.common.io.ByteStreams;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.jar.Manifest;
import org.apache.commons.lang.Validate;
import org.bukkit.plugin.InvalidPluginException;
import org.bukkit.plugin.PluginDescriptionFile;

public final class ClasspathPluginClassLoader extends URLClassLoader {
    private final ClasspathPluginLoader loader;
    private final Map<String, Class<?>> classes = new HashMap();
    private final PluginDescriptionFile description;
    private final File dataFolder;
    private final Manifest manifest;
    final JavaPlugin plugin;
    private JavaPlugin pluginInit;
    private IllegalStateException pluginState;
    private final Set<File> files;

    ClasspathPluginClassLoader(ClasspathPluginLoader loader, ClassLoader parent, PluginDescriptionFile description, File dataFolder, Set<File> files) throws IOException, InvalidPluginException, MalformedURLException {
        super(files.stream().map(e -> {
            try {
                return e.toURI().toURL();
            } catch (MalformedURLException ex) {
                throw new RuntimeException(ex);
            }
        }).toArray(URL[]::new), parent);
        Validate.notNull(loader, "Loader cannot be null");
        this.loader = loader;
        this.description = description;
        this.dataFolder = dataFolder;
        this.files = files;
        Manifest manifest1 = null;
        for (File file : files) {
            File manifest = new File(file, "META-INF/MANIFEST.MF");
            if (manifest.exists()) {
                manifest1 = new Manifest(new FileInputStream(manifest));
                break;
            }
        }
        this.manifest = manifest1;
        try {
            Class jarClass;
            try {
                jarClass = Class.forName(description.getMain(), true, this);
            } catch (ClassNotFoundException var10) {
                throw new InvalidPluginException("Cannot find main class `" + description.getMain() + "'", var10);
            }

            Class pluginClass;
            try {
                pluginClass = jarClass.asSubclass(JavaPlugin.class);
            } catch (ClassCastException var9) {
                throw new InvalidPluginException("main class `" + description.getMain() + "' does not extend JavaPlugin", var9);
            }

            this.plugin = (JavaPlugin)pluginClass.newInstance();
        } catch (IllegalAccessException var11) {
            throw new InvalidPluginException("No public constructor", var11);
        } catch (InstantiationException var12) {
            throw new InvalidPluginException("Abnormal plugin type", var12);
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (this.getClassLoadingLock(name)) {
            // has the class loaded already?
            Class<?> loadedClass = findLoadedClass(name);
            if (loadedClass == null) {
                try {
                    // find the class from given jar urls
                    loadedClass = findClass(name);
                } catch (ClassNotFoundException e) {
                    // Hmmm... class does not exist in the given urls.
                    // Let's try finding it in our parent classloader.
                    // this'll throw ClassNotFoundException in failure.
                    loadedClass = super.loadClass(name, resolve);
                }
            }

            if (resolve) {
                resolveClass(loadedClass);
            }
            return loadedClass;
        }
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        if (name.startsWith("org.bukkit.") || name.startsWith("net.minecraft.")) {
            throw new ClassNotFoundException(name);
        }
        Class<?> result = classes.get(name);

        if (result == null) {
            String path = name.replace('.', '/').concat(".class");
            URL entry = this.findResource(path);

            if (entry != null) {
                byte[] classBytes;

                try (InputStream is = this.findResource(path).openStream()) {
                    classBytes = ByteStreams.toByteArray(is);
                } catch (IOException ex) {
                    throw new ClassNotFoundException(name, ex);
                }

                int dot = name.lastIndexOf('.');
                if (dot != -1) {
                    String pkgName = name.substring(0, dot);
                    if (getPackage(pkgName) == null) {
                        try {
                            if (manifest != null) {
                                definePackage(pkgName, manifest, null);
                            } else {
                                definePackage(pkgName, null, null, null, null, null, null, null);
                            }
                        } catch (IllegalArgumentException ex) {
                            if (getPackage(pkgName) == null) {
                                throw new IllegalStateException("Cannot find package " + pkgName);
                            }
                        }
                    }
                }

                result = defineClass(name, classBytes, 0, classBytes.length);
            }

            if (result == null) {
                result = super.findClass(name);
            }

            loader.setClass(name, result);
            classes.put(name, result);
        }

        return result;
    }

    public void close() throws IOException {
        super.close();
    }

    Set<String> getClasses() {
        return this.classes.keySet();
    }

    synchronized void initialize(JavaPlugin javaPlugin) {
        Validate.notNull(javaPlugin, "Initializing plugin cannot be null");
        Validate.isTrue(javaPlugin.getClass().getClassLoader() == this, "Cannot initialize plugin outside of this class loader");
        if (this.plugin == null && this.pluginInit == null) {
            this.pluginState = new IllegalStateException("Initial initialization");
            this.pluginInit = javaPlugin;
            javaPlugin.init(this.loader, this.loader.server, this.description, this.dataFolder, this.files.stream().findFirst().get(), this);
        } else {
            throw new IllegalArgumentException("Plugin already initialized!", this.pluginState);
        }
    }
}
