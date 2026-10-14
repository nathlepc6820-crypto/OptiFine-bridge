package com.lexioe.bridge;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.objectweb.asm.ClassReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixins;
import org.spongepowered.asm.mixin.transformer.Config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

public class BridgePreLaunch implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger("optifine-bridge");

    @Override
    public void onPreLaunch() {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        if (!Files.isDirectory(modsDir)) {
            LOGGER.warn("Dossier mods introuvable : {}", modsDir);
            return;
        }

        try (Stream<Path> files = Files.list(modsDir)) {
            Optional<Path> optifine = files
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".jar")
                                && name.contains("optifine")
                                && !name.contains("bridge");
                    })
                    .findFirst();

            if (optifine.isEmpty()) {
                LOGGER.warn("OptiFine introuvable dans le dossier mods");
                return;
            }
            LOGGER.info("OptiFine trouve : {}", optifine.get().getFileName());

            Optional<Path> vanilla = findVanillaJar();
            if (vanilla.isEmpty()) {
                LOGGER.warn("Jar Minecraft d'origine introuvable");
                return;
            }

            Optional<Path> patched = buildPatchedJar(optifine.get(), vanilla.get());
            if (patched.isPresent()) {
                LOGGER.info("Jar patche pret : {} ({} octets)",
                        patched.get(), Files.size(patched.get()));
                inspectPatchedJar(patched.get());

                Optional<Path> extra = buildExtraClassesJar(patched.get(), vanilla.get(), optifine.get());
                extra.ifPresent(path -> FabricLauncherBase.getLauncher().addToClassPath(path));
                BridgeState.optifineActive = extra.isPresent();

                if (BridgeState.optifineActive) {
                    relaxRendererMixins();
                }

                generateTargetsMixin(patched.get(), vanilla.get());
            } else {
                LOGGER.warn("Le jar patche n'a pas pu etre cree");
            }
        } catch (IOException e) {
            LOGGER.error("Erreur de lecture", e);
        }
    }

    private void relaxRendererMixins() {
        LOGGER.info("Assouplissement des mixins de rendu : demarrage");
        try {
            int total = 0;
            for (Config config : Mixins.getConfigs()) {
                total++;
                String name = config.getName();
                LOGGER.info("Config Mixin detectee : {}", name);
                if (!name.contains("renderer")) {
                    continue;
                }

                Object mixinConfig = config.getConfig();

                java.lang.reflect.Field optionsField = findField(mixinConfig.getClass(), "injectorOptions");
                optionsField.setAccessible(true);
                Object options = optionsField.get(mixinConfig);

                java.lang.reflect.Field requireField = findField(options.getClass(), "defaultRequireValue");
                requireField.setAccessible(true);
                int before = requireField.getInt(options);
                requireField.setInt(options, 0);

                LOGGER.info("Config Mixin assouplie : {} (defaultRequire {} -> 0)", name, before);
            }
            LOGGER.info("Assouplissement termine : {} configs Mixin vues", total);
        } catch (Exception e) {
            LOGGER.error("Impossible d'assouplir les mixins de rendu", e);
        }
    }

    private static java.lang.reflect.Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
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

    private Optional<Path> findVanillaJar() {
        String version = FabricLoader.getInstance()
                .getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("26.1.2");

        String appData = System.getenv("APPDATA");
        Path root = appData != null
                ? Path.of(appData, ".minecraft")
                : Path.of(System.getProperty("user.home"), ".minecraft");

        Path jar = root.resolve("versions").resolve(version).resolve(version + ".jar");
        return Files.isRegularFile(jar) ? Optional.of(jar) : Optional.empty();
    }

    private Optional<Path> buildPatchedJar(Path optifineJar, Path vanillaJar) {
        Path cacheDir = FabricLoader.getInstance().getGameDir().resolve("optifine-bridge").resolve("cache");
        Path output = cacheDir.resolve("optifine-patche.jar");

        try {
            Files.createDirectories(cacheDir);

            if (Files.isRegularFile(output)
                    && Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(optifineJar)) > 0
                    && Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(vanillaJar)) > 0) {
                LOGGER.info("Jar patche deja en cache, rien a refaire");
                return Optional.of(output);
            }

            Files.deleteIfExists(output);
            LOGGER.info("Creation du jar patche, ca peut prendre un moment...");

            String javaBin = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            ProcessBuilder builder = new ProcessBuilder(
                    javaBin,
                    "-cp", optifineJar.toAbsolutePath().toString(),
                    "optifine.Patcher",
                    vanillaJar.toAbsolutePath().toString(),
                    optifineJar.toAbsolutePath().toString(),
                    output.toAbsolutePath().toString()
            );
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);

            Process process = builder.start();
            int exitCode = process.waitFor();
            LOGGER.info("Patcher termine avec le code {}", exitCode);

            return Files.isRegularFile(output) ? Optional.of(output) : Optional.empty();
        } catch (IOException e) {
            LOGGER.error("Erreur pendant la creation du jar patche", e);
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("Creation du jar patche interrompue", e);
            return Optional.empty();
        }
    }

    private void inspectPatchedJar(Path jar) {
        int notch = 0;
        int srg = 0;
        int other = 0;
        int shown = 0;

        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (!name.endsWith(".class")) {
                    continue;
                }
                if (name.startsWith("notch/")) {
                    notch++;
                    if (shown < 5 && name.contains("net/minecraft/")) {
                        LOGGER.info("Exemple de classe : {}", name);
                        shown++;
                    }
                } else if (name.startsWith("srg/")) {
                    srg++;
                } else {
                    other++;
                }
            }
            LOGGER.info("Classes dans le jar patche : notch={}, srg={}, autres={}", notch, srg, other);
        } catch (IOException e) {
            LOGGER.error("Impossible de lire le jar patche", e);
        }
    }

    private Optional<Path> buildExtraClassesJar(Path patchedJar, Path vanillaJar, Path optifineJar) {
        Path output = patchedJar.getParent().resolve("optifine-extra.jar");

        try {
            if (Files.isRegularFile(output)
                    && Files.getLastModifiedTime(output).compareTo(Files.getLastModifiedTime(patchedJar)) > 0) {
                return Optional.of(output);
            }
            Files.deleteIfExists(output);

            int added = 0;
            int assets = 0;
            try (ZipFile patched = new ZipFile(patchedJar.toFile());
                 ZipFile vanilla = new ZipFile(vanillaJar.toFile());
                 ZipFile optifine = new ZipFile(optifineJar.toFile());
                 ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(output))) {

                var entries = patched.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("notch/") || !name.endsWith(".class")) {
                        continue;
                    }

                    String realName = name.substring("notch/".length());
                    if (vanilla.getEntry(realName) != null) {
                        continue;
                    }

                    out.putNextEntry(new ZipEntry(realName));
                    try (InputStream in = patched.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                    added++;
                }

                var optifineEntries = optifine.entries();
                while (optifineEntries.hasMoreElements()) {
                    ZipEntry entry = optifineEntries.nextElement();
                    String name = entry.getName();
                    if (entry.isDirectory() || !name.startsWith("assets/")) {
                        continue;
                    }

                    out.putNextEntry(new ZipEntry(name));
                    try (InputStream in = optifine.getInputStream(entry)) {
                        in.transferTo(out);
                    }
                    out.closeEntry();
                    assets++;
                }
            }

            LOGGER.info("Jar des classes OptiFine cree : {} classes et {} fichiers assets", added, assets);
            return Optional.of(output);
        } catch (IOException e) {
            LOGGER.error("Impossible de creer le jar des classes OptiFine", e);
            return Optional.empty();
        }
    }

    private void generateTargetsMixin(Path patchedJar, Path vanillaJar) {
        Path classesOut = patchedJar.getParent().resolve("OptifineTargetsMixin.java");
        Path interfacesOut = patchedJar.getParent().resolve("OptifineInterfaceTargetsMixin.java");

        try (ZipFile patched = new ZipFile(patchedJar.toFile());
             ZipFile vanilla = new ZipFile(vanillaJar.toFile())) {

            StringBuilder classes = new StringBuilder();
            StringBuilder interfaces = new StringBuilder();
            int classCount = 0;
            int interfaceCount = 0;

            var entries = patched.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith("notch/") || !name.endsWith(".class")) {
                    continue;
                }

                String realName = name.substring("notch/".length());
                ZipEntry vanillaEntry = vanilla.getEntry(realName);
                if (vanillaEntry == null) {
                    continue;
                }

                int access;
                try (InputStream in = vanilla.getInputStream(vanillaEntry)) {
                    access = new ClassReader(in.readAllBytes()).getAccess();
                }

                String className = realName
                        .substring(0, realName.length() - ".class".length())
                        .replace('/', '.');

                if ((access & 0x0200) != 0) {
                    if (interfaceCount > 0) {
                        interfaces.append(",\n");
                    }
                    interfaces.append("    \"").append(className).append("\"");
                    interfaceCount++;
                } else {
                    if (classCount > 0) {
                        classes.append(",\n");
                    }
                    classes.append("    \"").append(className).append("\"");
                    classCount++;
                }
            }

            Files.writeString(classesOut,
                    "package com.lexioe.bridge.client.mixin;\n\n"
                            + "import org.spongepowered.asm.mixin.Mixin;\n\n"
                            + "@Mixin(targets = {\n" + classes + "\n})\n"
                            + "public abstract class OptifineTargetsMixin {\n}\n");

            if (interfaceCount > 0) {
                Files.writeString(interfacesOut,
                        "package com.lexioe.bridge.client.mixin;\n\n"
                                + "import org.spongepowered.asm.mixin.Mixin;\n\n"
                                + "@Mixin(targets = {\n" + interfaces + "\n})\n"
                                + "public interface OptifineInterfaceTargetsMixin {\n}\n");
            } else {
                Files.deleteIfExists(interfacesOut);
            }

            LOGGER.info("Fichiers des cibles generes : {} classes, {} interfaces, dans {}",
                    classCount, interfaceCount, classesOut.getParent());
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Impossible de generer les fichiers des cibles", e);
        }
    }
}