package edu.illinois.cs.dt.tools.detection;

import com.ibm.wala.ipa.callgraph.CallGraph;
import com.ibm.wala.ipa.callgraph.CallGraphBuilderCancelException;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.AnalysisCacheImpl;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.ipa.cha.ClassHierarchyException;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class CallGraphGenerator {

    public CallGraph generateCallGraph(final String classpath)
            throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(classpath, exclusionFile);
        // IClassHierarchy cha = ClassHierarchy.make(scope);
        ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);

        final var entryPointsGenerator = new EntryPointsGenerator(cha);
        final var entryPoints = entryPointsGenerator.getEntryPoints();
        AnalysisOptions options = new AnalysisOptions();

        final var options = new AnalysisOptions(scope, entryPoints);
        final var cache = new AnalysisCacheImpl();
        final var builder = Util.makeZeroCFABuilder(Language.JAVA, options, cache, cha, scope);

        return builder.makeCallGraph(options, null);
    }

    public Map<IClass, Set<IMethod>> getMethodNamesInJar(String JarFilePath) {
        Map<IClass, Set<IMethod>> result = new HashMap<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(JarFilePath, exclusionFile);
        IClassHierarchy cha = ClassHierarchy.make(scope);
        for (IClass c : cha) {
            Set<IMethod> mthds = new HashSet<>();
            for (IMethod m : c.getAllMethods()) { mthdNames.add(m); }
            result.putIfAbsent(c, mthds);
        }
        return result;
    }

    public Set<IMethod> getMethodsInClass(String className) {
        Set<IMethod> result = new HashSet<>();
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
        System.out.println(exclusionFile.getAbsolutePath());
        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(className, exclusionFile);
        IClassHierarchy cha = ClassHierarchy.make(scope);
        for (IClass c : cha) {
            for (IMethod m : c.getAllMethods()) { result.add(m); }
        }
        return result;
    }

}
