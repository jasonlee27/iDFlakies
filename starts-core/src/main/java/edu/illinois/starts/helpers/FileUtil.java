/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.starts.helpers;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Scanner;

/**
 * File handling utility methods.
 */
public class FileUtil {
    public static void delete(File file) {
        if (file.isDirectory()) {
            for (File childFile : file.listFiles()) {
                delete(childFile);
            }
        }
        file.delete();
    }

    public static List<File> findFileRec(File inDir, String fileExt) {
        if (inDir.exists()) {
            List<File> result = new ArrayList<File>();
            for (File f : Objects.requireNonNull(inDir.listFiles())) {
                if (f.isDirectory()) {
                    result.addAll(findFileRec(f, fileExt));
                } else if (f.getAbsolutePath().endsWith(fileExt)) {
                    result.add(f);
                }
            }
            return result;
        } else {
            throw new IllegalArgumentException("No such directory: "+ inDir.toString());
        }
    }

    public static List<String> readTxtFile(File inFile) throws FileNotFoundException {
        List<String> result = null;
        if(inFile.exists() && inFile.length()>0) {
            result = new ArrayList<>();
            Scanner reader = new Scanner(inFile);
            while (reader.hasNextLine()) {
                result.add(reader.nextLine().trim());
            }
        }
        return result;
    }
}
