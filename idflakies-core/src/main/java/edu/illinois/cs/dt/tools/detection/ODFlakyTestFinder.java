package edu.illinois.cs.dt.tools.detection;

import edu.illinois.cs.dt.tools.asm.StaticFieldVisitor;
import edu.illinois.cs.dt.tools.asm.StaticFieldsCountManager;
import edu.illinois.cs.dt.tools.constants.StartsConstants;
import edu.illinois.cs.dt.tools.utility.FileUtil;
import edu.illinois.starts.asm.ClassReader;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.project.MavenProject;

import java.io.*;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ODFlakyTestFinder {

    public static Map<String, Integer> findStaticFieldFromJar(String jarFilePath, String className) {
        Map<String, Integer> resultMap = new HashMap<>();
        try (JarFile jarFile = new JarFile(jarFilePath)) {
            StaticFieldsCountManager manager = new StaticFieldsCountManager();
            Enumeration<JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry jarEntry = entries.nextElement();
                if (jarEntry.getName().endsWith(className)) {
                    try (InputStream inputStream = jarFile.getInputStream(jarEntry)) {
                        ClassReader cr = new ClassReader(inputStream);
                        cr.accept(new StaticFieldVisitor(manager), 0);
                    } catch (UnsupportedOperationException | IllegalArgumentException ignored) { }
                    break;
                }
            }
            resultMap = manager.nameToDeclaredCount;
        } catch (IOException e) {
            e.printStackTrace();
        }
        return resultMap;
    }

    public static Map<String, Integer> findStaticFieldFromJars(String jar, String cls) {
        Map<String, Integer> results = new HashMap<>();
        for (Map.Entry<String, Integer> entry : findStaticFieldFromJar(jar, cls).entrySet()) {
            String newKey = jar + StartsConstants.EXCLAMATION + StartsConstants.BACKSLASH + entry.getKey();
            results.putIfAbsent(newKey, entry.getValue());
        }
        return results;
    }

    public static Map<String, Integer> findStaticFieldFromClass(String classUnderTest) {
        StaticFieldsCountManager manager = new StaticFieldsCountManager();
        if (classUnderTest.endsWith(StartsConstants.CLASS_EXTENSION)) {
            try (InputStream inputStream = new FileInputStream(new File(classUnderTest))) {
                ClassReader cr = new ClassReader(inputStream);
                cr.accept(new StaticFieldVisitor(manager), 0);
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            } catch (IOException e) { e.printStackTrace(); }
        }
        return manager.nameToDeclaredCount;
    }

    public static Set<String> readJarPaths(MavenProject project) throws IOException, DependencyResolutionRequiredException {
        List<String> classPathElems = project.getCompileClasspathElements();
        Set<String> classes = new HashSet<>();
        for (String elem : classPathElems) {
            if (elem.endsWith(StartsConstants.JAR_EXTENSION)) {
                try (JarFile jarFile = new JarFile(elem)) {
                    Enumeration<JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry jarEntry = entries.nextElement();
                        if (jarEntry.getName().endsWith(StartsConstants.CLASS_EXTENSION)) {
                            // String newKey = jar + StartsConstants.EXCLAMATION + StartsConstants.BACKSLASH + entry.getKey();`
                            classes.add(elem+StartsConstants.EXCLAMATION+jarEntry.getName());
                        }
                    }
                }
            }
        }
        return classes;
    }

    public static Set<String> readProjPaths(MavenProject project) {
        List<File> clsFileList = FileUtil.findFileRec(project.getBasedir(), StartsConstants.CLASS_EXTENSION);
        Set<String> clsPaths = new HashSet<>();
        for (File clsFile: clsFileList) {
            clsPaths.add(clsFile.getAbsolutePath());
        }
        return clsPaths;
    }

    /*

     */
    public static Set<String> findAffectedClassPaths(Set<String> affectedClassesUnderTest, MavenProject project)
            throws IOException, DependencyResolutionRequiredException {
        Set<String> jarClassPaths = readJarPaths(project);
        Set<String> projClassPaths = readProjPaths(project);
        Set<String> result = new HashSet<>();
        boolean foundInJar = false;
        for (String clsPath: affectedClassesUnderTest) {
            String newAffectedClsPath = clsPath.replace(StartsConstants.DOT, File.separator)+StartsConstants.CLASS_EXTENSION;
            for (String jarCls: jarClassPaths) {
                if (jarCls.endsWith(newAffectedClsPath)) {
                    result.add(jarCls);
                    foundInJar = true;
                    break;
                }
            }
            if (!foundInJar) {
                for (String projCls: projClassPaths) {
                    if (projCls.endsWith(newAffectedClsPath)) { result.add(projCls); break; }
                }
            }
            foundInJar = false;
        }
        return result;
    }

    public static List<String> staticFieldsFinder(Set<String> affectedClassesUnderTest, MavenProject project) throws IOException, DependencyResolutionRequiredException {
        List<String> result = new ArrayList<>();
        Set<String> affectedClassPaths = findAffectedClassPaths(affectedClassesUnderTest, project);
        // Find classes with static fields in the input project
        for (String cls: affectedClassPaths) {
            String[] clsSplit = cls.split(StartsConstants.EXCLAMATION);
            if (clsSplit[0].endsWith(StartsConstants.JAR_EXTENSION)) {
                Map<String, Integer> resMapFromJar = findStaticFieldFromJars(clsSplit[0], clsSplit[1]);
                for (Map.Entry<String, Integer> entry: resMapFromJar.entrySet()) {
                    if (entry.getValue()>0) { result.add(entry.getKey()); }
                }
            } else {
                Map<String, Integer> resMapFromClass = findStaticFieldFromClass(cls);
                for (Map.Entry<String, Integer> entry: resMapFromClass.entrySet()) {
                    if (entry.getValue()>0) { result.add(entry.getKey()); }
                }
            }
        }
        return result;
    }

    public static Map<String, String[]> getDepTests(MavenProject project, File classDir, File testClassDir) throws FileNotFoundException {
        List<File> depFiles = FileUtil.findFileRec(project.getBasedir(), StartsConstants.STARTS_DIR_NAME+File.separator+StartsConstants.ZLC_FILE);
        Map<String, String[]> result = new HashMap<>();
        for (File df: depFiles) {
            List<String> depLines = FileUtil.readTxtFile(df);
            for (String l: depLines) {
                String[] splitLine = l.split(StartsConstants.WHITE_SPACE); // (classUnderTest, cksum, depTests)
                String[] depTests = splitLine[splitLine.length-1].split(StartsConstants.COMMA);
                String clsUnderTest;
                if(splitLine[0].startsWith(StartsConstants.DEPSZLC_JAR_FILE_PREFIX)){
                    String[] newSplitLine = splitLine[0].split(StartsConstants.JAR_EXTENSION+StartsConstants.EXCLAMATION);
                    clsUnderTest = newSplitLine[newSplitLine.length-1].substring(1);
                } else if(splitLine[0].startsWith(StartsConstants.DEPSZLC_CLS_FILE_PREFIX+classDir.toString())){
                    String[] newSplitLine = splitLine[0].split(StartsConstants.DEPSZLC_CLS_FILE_PREFIX+classDir.toString());
                    clsUnderTest = newSplitLine[newSplitLine.length-1].substring(1);
                } else {
                    String[] newSplitLine = splitLine[0].split(StartsConstants.DEPSZLC_CLS_FILE_PREFIX+testClassDir.toString());
                    clsUnderTest = newSplitLine[newSplitLine.length-1].substring(1);
                }
                result.putIfAbsent(clsUnderTest, depTests);
            }
        }
        return result;
    }

    public static List<String> getClassesUnderTestFromTest(String inTest, Map<String, String[]> depTestMap) {
        List<String> result = new ArrayList<>();
        String[] testSplit = inTest.split(StartsConstants.COLON); // (moduleName, testName)
        for(Map.Entry<String, String[]> entry : depTestMap.entrySet()) {
            boolean contains = Arrays.asList(entry.getValue()).contains(testSplit[testSplit.length-1]);
            if(contains){ result.add(entry.getKey()); }
        }
        return new ArrayList<>(new HashSet<>(result));
    }

    public static List<String> findClassWithStaticFieldsFromClassUnderTests(List<String> classesUnderTests, List<String> classesWithStaticFields) {
        List<String> result = new ArrayList<>();
        for (String cls: classesUnderTests) {
            for(String clsWsf: classesWithStaticFields){ if(cls.endsWith(clsWsf+StartsConstants.CLASS_EXTENSION)){ result.add(cls); } }
        }
        return result;
    }

    public static List<String> getClassesUnderTestThatAffectsSelectedTests(List<String> selectedTests, Map<String, String[]> depTestMap) {
        List<String> result = new ArrayList<>();
        for(String test: selectedTests){ result.addAll(getClassesUnderTestFromTest(test, depTestMap)); }
        return result;
    }

    public static List<String> getFlakyTestCandidatesFromSelectedTests(List<String> classesWithStaticFields, MavenProject project,
                                                                       File classDir, File testClassDir) throws FileNotFoundException {
        Map<String, String[]> depTestMap = getDepTests(project, classDir, testClassDir);
        // List<String> classesUnderTests = getClassesUnderTestThatAffectsSelectedTests(selectedTests, depTestMap);
        // List<String> clsWithSfFromSelectedTests = findClassWithStaticFieldsFromClassUnderTests(classesUnderTests, classesWithStaticFields);
        Set<String> result = new HashSet<>();
        for (String cls: classesWithStaticFields) {
            String[] clsSplit = cls.split(StartsConstants.EXCLAMATION+StartsConstants.BACKSLASH);
            String key;
            if (clsSplit.length==2){ // class in jar
                key = clsSplit[clsSplit.length-1]+StartsConstants.CLASS_EXTENSION;
            } else if (cls.startsWith(classDir.toString())) { // class in project code
                clsSplit = cls.split(classDir.toString()+StartsConstants.BACKSLASH);
                key = clsSplit[clsSplit.length-1]+StartsConstants.CLASS_EXTENSION;
            } else { // class in project test code
                clsSplit = cls.split(testClassDir.toString()+StartsConstants.BACKSLASH);
                key = clsSplit[clsSplit.length-1]+StartsConstants.CLASS_EXTENSION;
            }
            String[] depTests = depTestMap.get(key);
            if (depTests!=null) { Collections.addAll(result, depTestMap.get(key)); }
        }
        return new ArrayList<>(result);
    }

}
