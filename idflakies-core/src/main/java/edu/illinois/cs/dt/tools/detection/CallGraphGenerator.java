package edu.illinois.cs.dt.tools.detection;

import com.ibm.wala.classLoader.IClass;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.classLoader.Language;
import com.ibm.wala.ipa.callgraph.*;
import com.ibm.wala.ipa.callgraph.impl.Util;
import com.ibm.wala.ipa.cha.ClassHierarchyException;
import com.ibm.wala.ipa.cha.ClassHierarchyFactory;
import com.ibm.wala.ipa.cha.ClassHierarchy;
import com.ibm.wala.ipa.callgraph.impl.DefaultEntrypoint;
import com.ibm.wala.util.config.AnalysisScopeReader;
import com.ibm.wala.util.collections.HashSetFactory;
import com.ibm.wala.util.intset.OrdinalSet;
import com.ibm.wala.types.ClassLoaderReference;
import edu.illinois.cs.dt.tools.constants.StartsConstants;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


public class CallGraphGenerator {

    public static <T> Set<T> iteratorToSet(Iterator<T> iter) {
        Set<T> set = HashSetFactory.make();
        while (iter.hasNext()) {
            set.add(iter.next());
        }
        return set;
    }

    /**
     * Check if given class is public.
     *
     * @param klass Class to check
     * @return true if class is public, false otherwise
     */
    private static boolean isPublicClass(final IClass klass) {
        return isApplication(klass)
                && !klass.isInterface()
                && klass.isPublic();
    }

    /**
     * Check if given method is public.
     *
     * @param method Method to check
     * @return true if method is public, false otherwise
     */
    private static boolean isPublicMethod(final IMethod method) {
        return isApplication(method.getDeclaringClass())
                && method.isPublic()
                && !method.isAbstract();
    }

    /**
     * Check if given class "belongs" to the application loader.
     *
     * @param klass Class to check
     * @return true if class "belongs", false otherwise
     */
    private static Boolean isApplication(final IClass klass) {
        return klass.getClassLoader().getReference().equals(ClassLoaderReference.Application);
    }

    public static ArrayList<Entrypoint> getEntryPoints(ClassHierarchy cha) {
        return StreamSupport.stream(cha.spliterator(), false)
                .filter(CallGraphGenerator::isPublicClass)
                .flatMap(klass -> klass.getAllMethods().parallelStream())
                .filter(CallGraphGenerator::isPublicMethod)
                .map(m -> new DefaultEntrypoint(m, cha))
                .collect(Collectors.toCollection(ArrayList::new));
    }

    public static String convertToFullClassName(String className) {
        if (className.startsWith("L")) className = className.substring(1);
        if (className.endsWith(";")) className = className.substring(0, className.length()-1);
        return className.replaceAll("/", "\\.");
    }


    public static CallGraph buildCallGraph(String targetClassPath, List<String> classPaths)
            throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());

        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(targetClassPath, exclusionFile);
        for(String classPath: classPaths) {
            AnalysisScope mainScope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(classPath, exclusionFile);
            scope.addToScope(mainScope);
        }

        // ClassHierarchy cha = ClassHierarchyFactory.makeWithRoot(scope);
        ClassHierarchy cha = ClassHierarchyFactory.make(scope);

        ArrayList<Entrypoint> entryPoints = getEntryPoints(cha);
        AnalysisOptions options = new AnalysisOptions(scope, entryPoints);
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

    public static boolean isNodeReachableFrom(Map<CGNode, OrdinalSet<CGNode>> callGraphTransitiveClosure, CGNode src, CGNode dst) {
        OrdinalSet<CGNode> reachable = callGraphTransitiveClosure.get(src);
        return reachable.contains(dst);
    }

    public static Map<String, Set<String>> getSubTransitiveClosure(Set<String> classScope, Map<String, Set<String>> callGraphTransitiveClosure) {
        Map<String, Set<String>> resultMap = new HashMap<>();
        for (Map.Entry<String, Set<String>> entry: callGraphTransitiveClosure.entrySet()) {
            String klass = entry.getKey().substring(0, entry.getKey().lastIndexOf(StartsConstants.DOT));
            if (classScope.contains(klass)) {
                resultMap.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }
        return resultMap;
    }

    public static Set<String> getReachableMethodsToDstClasses(Set<String> srcMethods, Set<String> dstClasses) {
        Set<String> result = new HashSet<>();
        for (String dstClass:dstClasses){
            for (String srcMethod: srcMethods) { if (srcMethod.startsWith(dstClass)) { result.add(srcMethod); } }
        }
        return result;
    }

    public static Set<String> getReachableMethodsToDstClasses(Map<String, Set<String>> callGraphTransitiveClosure, String srcClass, Set<String> dstClasses) {
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry: callGraphTransitiveClosure.entrySet()) {
            String klass = entry.getKey().substring(0, entry.getKey().lastIndexOf(StartsConstants.DOT));
            if(klass.equals(srcClass)) {
                result.addAll(getReachableMethodsToDstClasses(entry.getValue(), dstClasses));
            }
        }
        return result;
    }

    public static Map<String, Set<String>> getTransistiveClosureMap(String targetClassPath, List<String> classPathsScope)
            throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        Map<String, Set<String>> result = new HashMap<>();
        CallGraph cg = buildCallGraph(targetClassPath, classPathsScope);
        Map<CGNode, OrdinalSet<CGNode>> transitiveClosure = getCallGraphTransitiveClosure(cg);
        for (CGNode entryNode : cg.getEntrypointNodes()) {
            String entryElem = convertToFullClassName(entryNode.getMethod().getDeclaringClass().getName().toString())+StartsConstants.COLON_WO_SPACE+entryNode.getMethod().getName().toString();
            Set<String> calledMethods = new HashSet<>();
            OrdinalSet<CGNode> reachableNodes = transitiveClosure.get(entryNode);
            Iterator<CGNode> nodeSetIter = reachableNodes.iterator();
            while( nodeSetIter.hasNext()){
                CGNode node = nodeSetIter.next();
                String elem = convertToFullClassName(node.getMethod().getDeclaringClass().getName().toString())+StartsConstants.COLON_WO_SPACE+node.getMethod().getName().toString();
                calledMethods.add(elem);
            }
            result.putIfAbsent(entryElem, calledMethods);
        }
        return result;
    }

    public static Set<String> getFlakyTestMethodCandidates(Map<String, Set<String>> transistiveClosureMap,
                                                           Set<String> flakyTestCandidates, Set<String> classWithStaticFields) {
        Set<String> result = new HashSet<>();
        Map<String, Set<String>> subTransitiveClosure = getSubTransitiveClosure(flakyTestCandidates, transistiveClosureMap);
        for (String testCandid: flakyTestCandidates) {
            Set<String> testMethodCandidates = getReachableMethodsToDstClasses(subTransitiveClosure, testCandid, classWithStaticFields);
            result.addAll(testMethodCandidates);
        }
        return result;
    }

//    public Map<IClass, Set<IMethod>> getMethodNamesInJar(String JarFilePath) throws IOException, ClassHierarchyException {
//        Map<IClass, Set<IMethod>> result = new HashMap<>();
//        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
//        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
//        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(JarFilePath, exclusionFile);
//        ClassHierarchy cha = ClassHierarchyFactory.make(scope);
//        for (IClass c : cha) {
//            Set<IMethod> mthds = new HashSet<>();
//            for (IMethod m : c.getAllMethods()) { mthds.add(m); }
//            result.putIfAbsent(c, mthds);
//        }
//        return result;
//    }
//
//    public Set<IMethod> getMethodsInClass(String className) throws ClassHierarchyException, IOException {
//        Set<IMethod> result = new HashSet<>();
//        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
//        File exclusionFile = new File(Objects.requireNonNull(classLoader.getResource("Java60RegressionExclusions.txt")).getFile());
//        System.out.println(exclusionFile.getAbsolutePath());
//        AnalysisScope scope = AnalysisScopeReader.makeJavaBinaryAnalysisScope(className, exclusionFile);
//        ClassHierarchy cha = ClassHierarchyFactory.make(scope);
//        for (IClass c : cha) {
//            for (IMethod m : c.getAllMethods()) { result.add(m); }
//        }
//        return result;
//    }

//    public static void printTransitiveClosure(String targetClassPath, List<String> classPaths) throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
//        CallGraph cg = buildCallGraph(targetClassPath, classPaths);
//        Map<CGNode, OrdinalSet<CGNode>> transitiveClosure = getCallGraphTransitiveClosure(cg);
//        for (CGNode entryNode: cg.getEntrypointNodes()) {
//            String entryElem = convertToFullClassName(entryNode.getMethod().getDeclaringClass().getName().toString())+"::"+entryNode.getMethod().getName().toString();
//            System.out.println(entryElem);
//            Set<String> depMethods = new HashSet<>();
//            OrdinalSet<CGNode> reachableNodes = transitiveClosure.get(entryNode);
//            Iterator<CGNode> nodeSetIter = reachableNodes.iterator();
//            while( nodeSetIter.hasNext()){
//                CGNode node = nodeSetIter.next();
//                String elem = convertToFullClassName(node.getMethod().getDeclaringClass().getName().toString())+"::"+node.getMethod().getName().toString();
//                if (!depMethods.contains(elem)) {
//                    System.out.println("        "+elem);
//                    depMethods.add(elem);
//                }
//            }
//            System.out.println("\n");
//        }
//    }
    
//    public static void main(String[] args) throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
//
//        /*
//        cmd = java -classpath {IDFLAKIES_DIR}/idflakies-core/target/idflakies-core-1.2.0-SNAPSHOT-jar-with-dependencies.jar edu.illinois.cs.dt.tools.detection.CallGraphGenerator
//        */
//        String targetClassPath = "/Users/jaeseonglee/projects/incremental_iDFlakies/_downloads/wikidata_wikidata-toolkit_539f522/wdtk-util/target/test-classes/org/wikidata/wdtk/util/DirectoryManagerFactoryTest.class";
//        List<String> classPaths = new ArrayList<>();
//        classPaths.add("/Users/jaeseonglee/projects/incremental_iDFlakies/_downloads/wikidata_wikidata-toolkit_539f522/wdtk-util/target/classes/org/wikidata/wdtk/util/DirectoryManagerFactory.class");
//        printTransitiveClosure(targetClassPath, classPaths);
//    }

}
