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
import com.ibm.wala.ipa.cha.IClassHierarchy;

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

    public static CallGraph getCallGraph(String targetClassPath, List<String> classPaths)
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

    public boolean isReachableFrom(Map<CGNode, OrdinalSet<CGNode>> callGraphTransitiveClosure, CGNode dst, CGNode src) {
        OrdinalSet<CGNode> reachable = callGraphTransitiveClosure.get(src);
        return reachable.contains(dst);
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

    public static void printTransitiveClosure(String targetClassPath, List<String> classPaths) throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {
        CallGraph cg = getCallGraph(targetClassPath, classPaths);
        Map<CGNode, OrdinalSet<CGNode>> transitiveClosure = getCallGraphTransitiveClosure(cg);
        for (Map.Entry<CGNode, OrdinalSet<CGNode>> entry : transitiveClosure.entrySet()) {
            CGNode keyMethod = entry.getKey();
            System.out.println("Key Method: "+keyMethod.getMethod().getDeclaringClass().toString()+" :: "+keyMethod.getMethod().getName().toString());
            OrdinalSet<CGNode> nodeSet = entry.getValue();
            Iterator<CGNode> nodeSetIter = nodeSet.iterator();
            while( nodeSetIter.hasNext()){
                CGNode node = nodeSetIter.next();
                System.out.println("    Reachable Method of Key Method: "+node.getMethod().getDeclaringClass().toString()+" :: "+node.getMethod().getName().toString());
            }
            System.out.println("\n");
        }
    }

    public static void main(String[] args) throws IOException, ClassHierarchyException, CallGraphBuilderCancelException {

        /*
        cmd = java -classpath {IDFLAKIES_DIR}/idflakies-core/target/idflakies-core-1.2.0-SNAPSHOT-jar-with-dependencies.jar edu.illinois.cs.dt.tools.detection.CallGraphGenerator
         */
        String targetClassPath = "/Users/jaeseonglee/projects/incremental_iDFlakies/_downloads/wikidata_wikidata-toolkit_539f522/wdtk-util/target/test-classes/org/wikidata/wdtk/util/DirectoryManagerFactoryTest.class";
        List<String> classPaths = new ArrayList<>();
        classPaths.add("/Users/jaeseonglee/projects/incremental_iDFlakies/_downloads/wikidata_wikidata-toolkit_539f522/wdtk-util/target/classes/org/wikidata/wdtk/util/DirectoryManagerFactory.class");
        printTransitiveClosure(targetClassPath, classPaths);
    }

}
