package com.rookmender.kxit.module;

import android.system.Os;

import dalvik.system.DexClassLoader;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Loads trusted DEX modules at startup; external code is copied into private read-only storage first. */
public final class KXitModuleLoader {
    private static final int MAX_MODULES = 16;

    private static final class Loaded {
        final DexClassLoader classLoader;
        final KXitModule module;
        Loaded(DexClassLoader classLoader, KXitModule module) {
            this.classLoader = classLoader;
            this.module = module;
        }
    }

    private final List<Loaded> loaded = new ArrayList<>();

    public List<String> load(File externalDirectory, File privateFilesDirectory, KXitModuleApi api) throws Exception {
        if (!externalDirectory.isDirectory() && !externalDirectory.mkdirs()) {
            throw new IllegalStateException("Cannot create modules directory: " + externalDirectory);
        }
        File codeDirectory = new File(privateFilesDirectory, "kxit-module-code");
        File optimizedDirectory = new File(privateFilesDirectory, "kxit-module-optimized");
        ensureDirectory(codeDirectory);
        ensureDirectory(optimizedDirectory);

        File[] dexFiles = externalDirectory.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".dex"));
        if (dexFiles == null || dexFiles.length == 0) return Collections.emptyList();
        if (dexFiles.length > MAX_MODULES) throw new IllegalStateException("Too many modules: maximum is " + MAX_MODULES);

        List<String> names = new ArrayList<>();
        for (File externalDex : dexFiles) {
            String baseName = externalDex.getName().substring(0, externalDex.getName().length() - 4);
            File descriptor = new File(externalDirectory, baseName + ".kxm");
            if (!descriptor.isFile()) throw new IllegalStateException("Missing module descriptor: " + descriptor.getName());
            String className = readFirstClassName(descriptor);
            File privateDex = new File(codeDirectory, baseName + ".dex");
            copyAtomically(externalDex, privateDex);
            makeReadOnly(privateDex);

            DexClassLoader loader = new DexClassLoader(
                    privateDex.getAbsolutePath(),
                    optimizedDirectory.getAbsolutePath(),
                    null,
                    KXitModule.class.getClassLoader()
            );
            Class<?> moduleClass = loader.loadClass(className);
            Object instance = moduleClass.getDeclaredConstructor().newInstance();
            if (!(instance instanceof KXitModule)) {
                throw new IllegalStateException(className + " does not implement KXitModule.");
            }
            KXitModule module = (KXitModule) instance;
            module.onLoad(api);
            loaded.add(new Loaded(loader, module));
            names.add(moduleClass.getSimpleName());
        }
        return names;
    }

    public void update(float deltaSeconds) {
        for (Loaded item : loaded) item.module.onUpdate(deltaSeconds);
    }

    public void dispose() {
        for (int i = loaded.size() - 1; i >= 0; i--) {
            try { loaded.get(i).module.onDispose(); } catch (Throwable ignored) { }
        }
        loaded.clear();
    }

    private static String readFirstClassName(File descriptor) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(descriptor), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) return line;
            }
        }
        throw new IllegalStateException("Module descriptor has no class name: " + descriptor.getName());
    }

    private static void copyAtomically(File source, File destination) throws Exception {
        File temporary = new File(destination.getParentFile(), destination.getName() + ".tmp");
        temporary.setWritable(true, true);
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            output.getFD().sync();
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            throw new IllegalStateException("Cannot replace cached module: " + destination);
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IllegalStateException("Cannot move module into private storage: " + destination);
        }
    }

    private static void makeReadOnly(File file) throws Exception {
        Os.chmod(file.getAbsolutePath(), 0444);
        if (file.canWrite()) throw new IllegalStateException("Could not make module read-only: " + file);
    }

    private static void ensureDirectory(File directory) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create module cache directory: " + directory);
        }
    }
}
