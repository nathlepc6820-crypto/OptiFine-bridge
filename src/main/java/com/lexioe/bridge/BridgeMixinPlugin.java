package com.lexioe.bridge;

import net.fabricmc.loader.api.FabricLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BridgeMixinPlugin implements IMixinConfigPlugin {
    private static final Logger LOGGER = LoggerFactory.getLogger("optifine-bridge");
    private static final AtomicBoolean RELAXED = new AtomicBoolean(false);
    private static final String MIXIN_CONFIG_CLASS = "org.spongepowered.asm.mixin.transformer.MixinConfig";
    private static final String MIXIN_PROCESSOR_CLASS = "org.spongepowered.asm.mixin.transformer.MixinProcessor";
    private static final int VISIBILITY = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE;
    private static final String[] DISABLED_CONFIG_PREFIXES = {
            "fabric-rendering-v1",
            "fabric-renderer-api-v1",
            "fabric-renderer-indigo",
            "fabric-rendering-fluids-v1",
            "fabric-model-loading-api-v1"
    };
    private Path patchedJar;

    @Override
    public void onLoad(String mixinPackage) {
        patchedJar = FabricLoader.getInstance().getGameDir()
                .resolve("optifine-bridge").resolve("cache").resolve("optifine-patche.jar");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        if (!BridgeState.optifineActive) {
            return;
        }

        relaxFabricMixinsOnce();

        if (!patchedJar.toFile().isFile()) {
            LOGGER.warn("Jar patche absent, aucun remplacement pour {}", targetClassName);
            return;
        }

        String entryName = "notch/" + targetClassName.replace('.', '/') + ".class";
        try (ZipFile zip = new ZipFile(patchedJar.toFile())) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                LOGGER.info("Pas de version OptiFine pour {}", targetClassName);
                return;
            }

            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }

            ClassNode replacement = new ClassNode();
            new ClassReader(bytes).accept(replacement, 0);

            int before = targetClass.methods.size();

            int originalClassAccess = targetClass.access;
            Map<String, Integer> originalMethods = new HashMap<>();
            for (MethodNode method : targetClass.methods) {
                originalMethods.put(method.name + method.desc, method.access);
            }
            Map<String, Integer> originalFields = new HashMap<>();
            for (FieldNode field : targetClass.fields) {
                originalFields.put(field.name + ":" + field.desc, field.access);
            }

            targetClass.fields.clear();
            targetClass.methods.clear();
            targetClass.innerClasses.clear();
            targetClass.nestMembers = null;
            targetClass.permittedSubclasses = null;
            targetClass.recordComponents = null;
            targetClass.visibleAnnotations = null;
            targetClass.invisibleAnnotations = null;

            replacement.accept(targetClass);

            int changed = 0;

            int mergedClass = mergeAccess(originalClassAccess, targetClass.access);
            if (mergedClass != targetClass.access) {
                targetClass.access = mergedClass;
                changed++;
            }
            for (MethodNode method : targetClass.methods) {
                Integer original = originalMethods.get(method.name + method.desc);
                if (original == null) {
                    continue;
                }
                int merged = mergeAccess(original, method.access);
                if (merged != method.access) {
                    method.access = merged;
                    changed++;
                }
            }
            for (FieldNode field : targetClass.fields) {
                Integer original = originalFields.get(field.name + ":" + field.desc);
                if (original == null) {
                    continue;
                }
                int merged = mergeAccess(original, field.access);
                if (merged != field.access) {
                    field.access = merged;
                    changed++;
                }
            }

            LOGGER.info("Remplacement de {} : {} -> {} methodes, {} acces repris de l'original",
                    targetClassName, before, targetClass.methods.size(), changed);
        } catch (Exception e) {
            LOGGER.error("Echec du remplacement de " + targetClassName, e);
        }
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    private static int rank(int access) {
        if ((access & Opcodes.ACC_PUBLIC) != 0) {
            return 3;
        }
        if ((access & Opcodes.ACC_PROTECTED) != 0) {
            return 2;
        }
        if ((access & Opcodes.ACC_PRIVATE) != 0) {
            return 0;
        }
        return 1;
    }

    private static int mergeAccess(int original, int replacement) {
        int result = replacement;
        if (rank(original) > rank(replacement)) {
            result = (result & ~VISIBILITY) | (original & VISIBILITY);
        }
        if ((original & Opcodes.ACC_FINAL) == 0) {
            result &= ~Opcodes.ACC_FINAL;
        }
        return result;
    }

    private static void relaxFabricMixinsOnce() {
        if (!RELAXED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Preparation des mixins Fabric : demarrage");
        try {
            Object transformer = MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
            LOGGER.info("Transformer Mixin : {}", transformer == null ? "null" : transformer.getClass().getName());

            int disabled = disableRenderConfigs(transformer);

            List<Object> configs = findMixinConfigs(transformer);
            int relaxed = 0;

            for (Object config : configs) {
                String name = configName(config);
                if (name == null || !name.startsWith("fabric-")) {
                    continue;
                }

                Field optionsField = findField(config.getClass(), "injectorOptions");
                optionsField.setAccessible(true);
                Object options = optionsField.get(config);

                Field requireField = findField(options.getClass(), "defaultRequireValue");
                requireField.setAccessible(true);
                requireField.setInt(options, 0);
                relaxed++;
            }

            LOGGER.info("Preparation terminee : {} configs vues, {} assouplies, {} desactivees", configs.size(), relaxed, disabled);
        } catch (Throwable e) {
            LOGGER.error("Impossible de preparer les mixins Fabric", e);
        }
    }

    private static int disableRenderConfigs(Object transformer) {
        int removed = 0;
        try {
            Object processor = findObjectByClassName(transformer, MIXIN_PROCESSOR_CLASS);
            if (processor == null) {
                LOGGER.warn("MixinProcessor introuvable, aucune config desactivee");
                return 0;
            }

            Field configsField = findField(processor.getClass(), "configs");
            configsField.setAccessible(true);
            Object value = configsField.get(processor);
            if (!(value instanceof List<?> list)) {
                LOGGER.warn("Le champ configs n'est pas une liste : {}", value == null ? "null" : value.getClass().getName());
                return 0;
            }

            Iterator<?> iterator = list.iterator();
            while (iterator.hasNext()) {
                Object config = iterator.next();
                String name = configName(config);
                if (name == null) {
                    continue;
                }
                for (String prefix : DISABLED_CONFIG_PREFIXES) {
                    if (name.startsWith(prefix)) {
                        iterator.remove();
                        removed++;
                        LOGGER.info("Config Mixin desactivee : {}", name);
                        break;
                    }
                }
            }
        } catch (Throwable e) {
            LOGGER.error("Impossible de desactiver les configs de rendu", e);
        }
        return removed;
    }

    private static Object findObjectByClassName(Object root, String className) {
        if (root == null) {
            return null;
        }

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] {root, 0});

        while (!queue.isEmpty()) {
            Object[] item = queue.poll();
            Object obj = item[0];
            int depth = (Integer) item[1];

            if (obj == null || !seen.add(obj)) {
                continue;
            }
            if (obj.getClass().getName().equals(className)) {
                return obj;
            }
            if (depth >= 3) {
                continue;
            }

            String typeName = obj.getClass().getName();
            if (typeName.startsWith("java.") || typeName.startsWith("jdk.") || typeName.startsWith("sun.")) {
                continue;
            }

            for (Class<?> current = obj.getClass(); current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        queue.add(new Object[] {field.get(obj), depth + 1});
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        return null;
    }

    private static String configName(Object config) {
        try {
            Method method = config.getClass().getDeclaredMethod("getName");
            method.setAccessible(true);
            return String.valueOf(method.invoke(config));
        } catch (Throwable e) {
            return null;
        }
    }

    private static List<Object> findMixinConfigs(Object root) {
        List<Object> found = new ArrayList<>();
        if (root == null) {
            return found;
        }

        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[] {root, 0});
        int visited = 0;

        while (!queue.isEmpty() && visited < 50000) {
            Object[] item = queue.poll();
            Object obj = item[0];
            int depth = (Integer) item[1];

            if (obj == null || !seen.add(obj)) {
                continue;
            }
            visited++;

            Class<?> type = obj.getClass();
            if (type.getName().equals(MIXIN_CONFIG_CLASS)) {
                found.add(obj);
                continue;
            }
            if (depth >= 4) {
                continue;
            }

            if (obj instanceof Iterable<?> iterable) {
                for (Object element : iterable) {
                    queue.add(new Object[] {element, depth + 1});
                }
                continue;
            }
            if (obj instanceof Map<?, ?> map) {
                for (Object element : map.values()) {
                    queue.add(new Object[] {element, depth + 1});
                }
                continue;
            }

            String typeName = type.getName();
            if (typeName.startsWith("java.") || typeName.startsWith("jdk.") || typeName.startsWith("sun.")) {
                continue;
            }

            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Field field : current.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        queue.add(new Object[] {field.get(obj), depth + 1});
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        return found;
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}