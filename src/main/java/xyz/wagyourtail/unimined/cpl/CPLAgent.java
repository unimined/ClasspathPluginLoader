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

        inst.addTransformer(new CPLAgent(), inst.isRetransformClassesSupported());
    }

    public static void agentmain(String agentArgs, Instrumentation inst) throws IOException {
        premain(agentArgs, inst);
    }

    private final Set<String> classesToTransform = new HashSet<>(
        Arrays.asList(
            "org/bukkit/plugin/SimplePluginManager",
            "org/bukkit/plugin/java/JavaPlugin"
        )
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

        // Lxyz/wagyourtail/unimined/cpl/SimplePluginManagerAccessor;cpl$getPlugins()Ljava/util/List;
        MethodVisitor mv = cn.visitMethod(Opcodes.ACC_PUBLIC, "cpl$getPlugins", "()Ljava/util/List;", "()Ljava/util/List<Lorg/bukkit/plugin/Plugin;>;", null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/bukkit/plugin/SimplePluginManager", "plugins", "Ljava/util/List;");
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();

        // Lxyz/wagyourtail/unimined/cpl/SimplePluginManagerAccessor;cpl$getLookupNames()Ljava/util/Map;
        mv = cn.visitMethod(Opcodes.ACC_PUBLIC, "cpl$getLookupNames", "()Ljava/util/Map;", "()Ljava/util/Map<Ljava/lang/String;Lorg/bukkit/plugin/Plugin;>;", null);
        mv.visitCode();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, "org/bukkit/plugin/SimplePluginManager", "lookupNames", "Ljava/util/Map;");
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
        list.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Type.getInternalName(CPLAgent.class), "loadPlugins", "([Lorg/bukkit/plugin/Plugin;)[Lorg/bukkit/plugin/Plugin;", false));
        return list;
    }

    public static Plugin[] loadPlugins(Plugin[] prev) {
        List<Plugin> plugins = new ArrayList<>(Arrays.asList(prev));
        ClasspathPluginLoader loader = new ClasspathPluginLoader(Bukkit.getServer());
        plugins.addAll(loader.loadAll());
        return plugins.toArray(new Plugin[0]);
    }
}
