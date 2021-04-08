package edu.illinois.cs.dt.tools.detection;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.core.tests.callGraph.CallGraphTestUtil;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.intset.OrdinalSet;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;

public class CallGraphGenerator {

    public static <T> Set<T> iteratorToSet(Iterator<T> iter) {
        Set<T> set = HashSetFactory.make();
        while (iter.hasNext()) {
            set.add(iter.next());
        }
        return set;
    }

    public static CallGraph getCallGraph(String classpath)
            throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(classpath, exclusionFile);
        // ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
        ClassHierarchy cha = ClassHierarchyFactory.make(scope);
        Iterable<Entrypoint> entrypoints = Util.makeMainEntrypoints(scope, cha, classpath);
        AnalysisOptions options = CallGraphTestUtil.makeAnalysisOptions(scope, entrypoints);
        CallGraphBuilder builder = Util.makeZeroCFABuilder(Language.JAVA, options, new AnalysisCacheImpl(), cha, scope);
        return builder.makeCallGraph(options, null);
    }

    public static Map<CGNode, OrdinalSet<CGNode>> getCallGraphTransitiveClosure(CallGraph callGraph) {
        assert callGraph!=null : "Call graph is now null.";
        Map<CGNode, Collection<CGNode>> resultMap = CallGraphTransitiveClosure.collectNodeResults(callGraph,
                new Function<CGNode, Collection<CGNode>>() {
                    public Set<CGNode> apply(CGNode node) {
                        return iteratorToSet(callGraph.getSuccNodes(node));
                    }
                });
        return CallGraphTransitiveClosure.transitiveClosure(callGraph, resultMap);
    }

    public boolean isReachableFrom(Map<CGNode, OrdinalSet<CGNode>> callGraphTransitiveClosure, CGNode dst, CGNode src) {
        OrdinalSet<CGNode> reachable = callGraphTransitiveClosure.get(src);
        return reachable.contains(dst);
    }

//    public Map<IClass, Set<IMethod>> getMethodNamesInJar(String JarFilePath) {
//        Map<IClass, Set<IMethod>> result = new HashMap<>();
//        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
//        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
//        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(JarFilePath, exclusionFile);
//        IClassHierarchy cha = ClassHierarchy.make(scope);
//        for (IClass c : cha) {
//            Set<IMethod> mthds = new HashSet<>();
//            for (IMethod m : c.getAllMethods()) { mthdNames.add(m); }
//            result.putIfAbsent(c, mthds);
//        }
//        return result;
//    }
//
//    public Set<IMethod> getMethodsInClass(String className) {
//        Set<IMethod> result = new HashSet<>();
//        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
//        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
//        System.out.println(exclusionFile.getAbsolutePath());
//        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(className, exclusionFile);
//        IClassHierarchy cha = ClassHierarchy.make(scope);
//        for (IClass c : cha) {
//            for (IMethod m : c.getAllMethods()) { result.add(m); }
//        }
//        return result;
//    }

    public static void printTransitiveClosure(final String classpath) throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        CallGraph cg = getCallGraph(classpath);
        Map<CGNode, OrdinalSet<CGNode>> transitiveClosure = getCallGraphTransitiveClosure(cg);
        for (Map.Entry<CGNode, OrdinalSet<CGNode>> entry : transitiveClosure.entrySet()) {
            CGNode keyMethod = entry.getKey();
            System.out.println("Key Method: "+keyMethod.getMethod().getName().toString());
            OrdinalSet<CGNode> nodeSet = entry.getValue();
            Iterator<CGNode> nodeSetIter = nodeSet.iterator();
            while( nodeSetIter.hasNext()){
                CGNode node = nodeSetIter.next();
                System.out.println("    Reachable Method of Key Method: "+node.getMethod().getName().toString());
            }
            System.out.println("\n");
        }
    }

    public static void main(String[] args) throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        String classpath = args[0];
        printTransitiveClosure(classpath);
    }

}
