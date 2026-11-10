package com.rookmender.kxit;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.loader.ObjLoader;

/** Ready-to-use OBJ model loader. The starter world does not instantiate OBJ models yet. */
public final class KXitObjLoader {
    private final ObjLoader loader = new ObjLoader();

    public Model load(FileHandle file) {
        if (file == null || !file.exists()) throw new IllegalArgumentException("OBJ file does not exist.");
        return loader.loadModel(file);
    }
}
