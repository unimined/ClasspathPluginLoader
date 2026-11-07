package xyz.wagyourtail.unimined.cpl;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.ClasspathPluginClassLoader;
import org.bukkit.plugin.java.ClasspathPluginLoader;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.ProtectionDomain;
import java.util.*;
import java.util.jar.JarFile;

public class CPLAgent implements ClassFileTransformer {

    public static void premain(String agentArgs, Instrumentation inst) throws IOException {
        // insert self into normal classpath
        URL location = CPLAgent.class.getProtectionDomain().getCodeSource().getLocation();
        JarFile jar = new JarFile(location.getPath());
        inst.appendToSystemClassLoaderSearch(jar);

        CPLAgent agent = new CPLAgent();
        inst.addTransformer(agent, inst.isRetransformClassesSupported());

        // eagerly define every transform target now — classes defined inside
        // a transformer call (e.g. ClassWriter.COMPUTE_FRAMES resolving
        // superclasses while patching another class) bypass transformation
        // entirely, so a lazy-loaded target can slip through unpatched.
        // initialize=false: define+transform without running <clinit>
        for (String name : agent.classesToTransform) {
            try {
                Class.forName(name.replace('/', '.'), false, CPLAgent.class.getClassLoader());
            } catch (Throwable ignored) {}
        }
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws IOException {
        premain(agentArgs, inst);
    }

    // ordered for the premain eager-load: supertypes first — a transform's
    // COMPUTE_FRAMES pass can nested-load the classes below, and nested
    // loads bypass the transformer entirely
    private final List<String> classesToTransform = Arrays.asList(
        "org/bukkit/plugin/java/JavaPlugin",
        "org/bukkit/plugin/SimplePluginManager",
        // paper's plugin pipeline — SimplePluginManager.loadPlugins
        // is never invoked there; boot goes through the PLUGIN
        // entrypoint dispatch, and the PluginManager API through
        // the instance manager's loadPlugins(Path)/loadPlugins(File[])
        "io/papermc/paper/plugin/manager/PaperPluginInstanceManager",
        "io/papermc/paper/plugin/entrypoint/LaunchEntryPointHandler"
    );


    // Lorg/bukkit/plugin/SimplePluginManager;
    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!classesToTransform.contains(className)) return null;
        System.out.println("[CPL] TRANSFORMING: " + className);

        // transform loadPlugins(Ljava/io/File;)[Lorg/bukkit/plugin/Plugin;
        // to call our plugin loader
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        if (className.equals("org/bukkit/plugin/SimplePluginManager")) transformSPM(cn);
        else if (className.equals("org/bukkit/plugin/java/JavaPlugin")) transformJP(cn);
        else if (className.equals("io/papermc/paper/plugin/manager/PaperPluginInstanceManager")) transformPPIM(cn);
        else if (className.equals("io/papermc/paper/plugin/entrypoint/LaunchEntryPointHandler")) transformLEPH(cn);

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);

        // dump class
        try {
            Path out = new File("./.cpl/", cn.name + ".class").toPath();
            Files.createDirectories(out.getParent());
            Files.write(out, cw.toByteArray());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return cw.toByteArray();
    }

    public void transformSPM(ClassNode cn) {
        cn.interfaces.add(Type.getInternalName(SimplePluginManagerAccessor.class));

        cn.methods.stream().filter(m -> m.name.equals("loadPlugins") && m.desc.equals("(Ljava/io/File;)[Lorg/bukkit/plugin/Plugin;")).forEach(m -> {
            // insert before ARETURN
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    m.instructions.insertBefore(insn, getLoadPluginsCall());
                    break;
                }
            }
        });

        addAccessorMethods(cn, "org/bukkit/plugin/SimplePluginManager");
    }

    /**
     * Paper equivalent of [transformSPM] — PaperPluginInstanceManager
     * carries the same `plugins`/`lookupNames` fields; its
     * `loadPlugins(Path)` is where the PLUGIN entrypoint lands.
     */
    public void transformPPIM(ClassNode cn) {
        cn.interfaces.add(Type.getInternalName(SimplePluginManagerAccessor.class));

        cn.methods.stream().filter(m -> m.name.equals("loadPlugins") &&
            (m.desc.equals("(Ljava/nio/file/Path;)[Lorg/bukkit/plugin/Plugin;") ||
             m.desc.equals("([Ljava/io/File;)[Lorg/bukkit/plugin/Plugin;"))).forEach(m -> {
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn.getOpcode() == Opcodes.ARETURN) {
                    m.instructions.insertBefore(insn, getLoadPluginsCall());
                    break;
                }
            }
        });

        addAccessorMethods(cn, "io/papermc/paper/plugin/manager/PaperPluginInstanceManager");
    }

    /**
     * Paper's boot-time plugin load path: {@code CraftServer.loadPlugins()}
     * delegates to {@code LaunchEntryPointHandler.INSTANCE.enter(PLUGIN)},
     * which dispatches to providers registered by {@code PluginInitializerManager}
     * — none of which know about classpath plugins. Injected at the head of
     * {@code enter(Entrypoint)}: when the entered entrypoint is PLUGIN, run
     * the classpath loader so its plugins land in the instance manager's
     * registry before {@code enablePlugins(STARTUP/POSTWORLD)} iterates it.
     */
    public void transformLEPH(ClassNode cn) {
        cn.methods.stream().filter(m -> m.name.equals("enter") &&
            m.desc.equals("(Lio/papermc/paper/plugin/entrypoint/Entrypoint;)V")).forEach(m -> {
            InsnList list = new InsnList();
            LabelNode skip = new LabelNode();
            list.add(new VarInsnNode(Opcodes.ALOAD, 1));
            list.add(new FieldInsnNode(Opcodes.GETSTATIC,
                "io/papermc/paper/plugin/entrypoint/Entrypoint", "PLUGIN",
                "Lio/papermc/paper/plugin/entrypoint/Entrypoint;"));
            list.add(new JumpInsnNode(Opcodes.IF_ACMPNE, skip));
            list.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                Type.getInternalName(CPLAgent.class), "loadPluginsPaper", "()V", false));
            list.add(skip);
            m.instructions.insert(list);
        });
    }

    private void addAccessorMethods(ClassNode cn, String owner) {

        // Lxyz/wagyourtail/unimined/cpl/SimplePluginManagerAccessor;cpl$getPlugins()Ljava/util/List;
        MethodVisitor mv = cn.visitMethod(Opcodes.ACC_PUBLIC, "cpl$getPlugins", "()Ljava/util/List;", "()Ljava/util/List<Lorg/bukkit/plugin/Plugin;>;", null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "plugins", "Ljava/util/List;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Lxyz/wagyourtail/unimined/cpl/SimplePluginManagerAccessor;cpl$getLookupNames()Ljava/util/Map;
        mv = cn.visitMethod(Opcodes.ACC_PUBLIC, "cpl$getLookupNames", "()Ljava/util/Map;", "()Ljava/util/Map<Ljava/lang/String;Lorg/bukkit/plugin/Plugin;>;", null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, owner, "lookupNames", "Ljava/util/Map;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    private void transformJP(ClassNode cn) {
        // in <init>()V find instanceof PluginClassLoader and add || instanceof ClasspathPluginClassLoader
        cn.methods.stream().filter(m -> m.name.equals("<init>") && m.desc.equals("()V")).forEach(m -> {
            // val arg1 = this.getClass().getClassLoader()
            // if (arg1 instanceof ClasspathPluginClassLoader) {
            //    ((ClasspathPluginClassLoader) arg1).initialize(this);
            // }
            InsnList list = new InsnList();
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
            list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
            LabelNode label = new LabelNode();
            list.add(new TypeInsnNode(Opcodes.INSTANCEOF, Type.getInternalName(ClasspathPluginClassLoader.class)));
            list.add(new JumpInsnNode(Opcodes.IFEQ, label));
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Object", "getClass", "()Ljava/lang/Class;", false));
            list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader", "()Ljava/lang/ClassLoader;", false));
            list.add(new TypeInsnNode(Opcodes.CHECKCAST, Type.getInternalName(ClasspathPluginClassLoader.class)));
            list.add(new VarInsnNode(Opcodes.ALOAD, 0));
            list.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, Type.getInternalName(ClasspathPluginClassLoader.class), "initialize", "(Lorg/bukkit/plugin/java/JavaPlugin;)V", false));
            list.add(new InsnNode(Opcodes.RETURN));
            list.add(label);
            // find existing super call and insert after
            for (int i = 0; i < m.instructions.size(); i++) {
                AbstractInsnNode insn = m.instructions.get(i);
                if (insn.getOpcode() == Opcodes.INVOKESPECIAL) {
                    m.instructions.insert(insn, list);
                    break;
                }
            }
        });
    }

    public static InsnList getLoadPluginsCall() {
        InsnList list = new InsnList();
        list.add(new VarInsnNode(Opcodes.ALOAD, 0));
        list.add(new InsnNode(Opcodes.SWAP));
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(CPLAgent.class), "loadPlugins", "(Ljava/lang/Object;[Lorg/bukkit/plugin/Plugin;)[Lorg/bukkit/plugin/Plugin;", false));
        return list;
    }

    /**
     * Injected before `loadPlugins` returns — [manager] is the
     * transformed registry (SimplePluginManagerAccessor). Appends the
     * classpath plugins to the result array.
     */
    public static Plugin[] loadPlugins(Object manager, Plugin[] prev) {
        List<Plugin> plugins = new ArrayList<>(Arrays.asList(prev));
        ClasspathPluginLoader loader = new ClasspathPluginLoader(
            Bukkit.getServer(), (SimplePluginManagerAccessor) manager);
        plugins.addAll(loader.loadAll());
        return plugins.toArray(new Plugin[0]);
    }

    /**
     * Injected at the end of {@code LaunchEntryPointHandler.enter} when the
     * entered entrypoint is PLUGIN — paper's boot-time load path. Reaches the
     * instance manager through {@code PaperPluginManagerImpl.instanceManager}
     * (private, hence reflection) since the PluginManager interface doesn't
     * expose it. The instance manager implements the accessor interface via
     * the PPIM transform; loaded plugins land in its registry ahead of the
     * {@code enablePlugins} passes, exactly like the SimplePluginManager path.
     */
    public static void loadPluginsPaper() {
        try {
            org.bukkit.Server server = Bukkit.getServer();
            if (server == null) return;
            // getPluginManager() is the legacy SimplePluginManager facade —
            // paper's real registry lives in CraftServer.paperPluginManager
            // (public) .instanceManager (package-private)
            Object paperPluginManager = server.getClass()
                .getField("paperPluginManager").get(server);
            java.lang.reflect.Field field = paperPluginManager.getClass()
                .getDeclaredField("instanceManager");
            field.setAccessible(true);
            List<Plugin> loaded = new ClasspathPluginLoader(
                server, (SimplePluginManagerAccessor) field.get(paperPluginManager)).loadAll();
            if (!loaded.isEmpty()) {
                server.getLogger().info("[CPL] loaded " + loaded.size()
                    + " classpath plugin(s): " + loaded);
            }
        } catch (Throwable t) {
            try {
                Bukkit.getServer().getLogger().log(java.util.logging.Level.SEVERE,
                    "[CPL] failed to load classpath plugins", t);
            } catch (Throwable ignored) {
                t.printStackTrace();
            }
        }
    }
}
