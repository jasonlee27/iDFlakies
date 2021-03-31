/*
 * Copyright (c) 2015 - Present. The STARTS Team. All Rights Reserved.
 */

package edu.illinois.cs.dt.tools.detection.jdeps;

import edu.illinois.cs.dt.tools.constants.StartsConstants;
import edu.illinois.cs.dt.tools.detection.Pair;
import edu.illinois.cs.dt.tools.detection.helpers.*;
import edu.illinois.cs.dt.tools.detection.Logger;
import edu.illinois.yasgl.DirectedGraph;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.surefire.AbstractSurefireMojo;
import org.apache.maven.plugin.surefire.SurefirePlugin;
import org.apache.maven.plugin.surefire.util.DirectoryScanner;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.surefire.booter.Classpath;
import org.apache.maven.surefire.booter.SurefireExecutionException;
import org.apache.maven.surefire.testset.TestListResolver;
import org.apache.maven.surefire.util.DefaultScanResult;

import org.apache.maven.artifact.repository.ArtifactRepository;
import edu.illinois.cs.testrunner.configuration.Configuration;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.logging.Level;

/**
 * Base MOJO for the JDeps-Based STARTS.
 */
public class BaseMojo extends SurefirePlugin implements StartsConstants {
    static final String STAR = "*";
    /**
     * Set this to "false" to not filter out "sun.*" and "java.*" classes from jdeps parsing.
     */
    public boolean filterLib = Configuration.config().getProperty("dt.detector.jdeps.filterLib", true);

    /**
     * Set this to "false" to not add jdeps edges from 3rd party-libraries.
     */
    public boolean useThirdParty = Configuration.config().getProperty("dt.detector.jdeps.useThirdParty", false);

    /**
     * The directory in which to store STARTS artifacts that are needed between runs.
     */
    public String artifactsDir;

    /**
     * Allows to switch the format in which we want to store the test dependencies.
     * A full list of what we currently support can be found in
     * @see edu.illinois.starts.enums.DependencyFormat
     */
    public String depFormatString = Configuration.config().getProperty("dt.detector.jdeps.depFormat", "ZLC");
    public DependencyFormat depFormat = depFormatString=="ZLC" ? DependencyFormat.ZLC: DependencyFormat.CLZ;

    /**
     * Path to directory that contains the result of running jdeps on third-party
     * and standard library jars that an application may need, e.g., those in M2_REPO.
     */
    public String graphCache = Configuration.config().getProperty("dt.detector.jdeps.graphCache", "${basedir}${file.separator}jdeps-cache");

    /**
     * Set this to "false" to not print the graph obtained from jdeps parsing.
     * When "true" the graph is written to file after the run.
     */
    public boolean printGraph = Configuration.config().getProperty("dt.detector.jdeps.printGraph", true);

    /**
     * Output filename for the graph, if printGraph == true.
     */
    public String graphFile = Configuration.config().getProperty("dt.detector.jdeps.graphFile", "graph");

    /**
     * Log levels as defined in java.util.logging.Level.
     */
    public String loggingLevel = Configuration.config().getProperty("dt.detector.jdeps.startsLogging", "CONFIG");

    /**
     * Set this to "false" to disable smart hashing, i.e., to *not* strip
     * Bytecode files of debug info prior to computing checksums. See the "Smart
     * Checksums" Sections in the Ekstazi paper:
     * http://dl.acm.org/citation.cfm?id=2771784
     */
    public boolean cleanBytes = Configuration.config().getProperty("dt.detector.jdeps.cleanBytes", true);

    public Classpath sureFireClassPath;

    public MavenProject accessedProject;
    public File baseDir;
    public File classDir;
    public File testClassDir;
    public ArtifactRepository localRepository;

    public BaseMojo(){
        accessedProject = getProject();
        baseDir = basedir;
        classDir = getClassesDirectory();
        testClassDir = getTestClassesDirectory();
        localRepository = getLocalRepository();
    }

    public String getArtifactsDir() throws MojoExecutionException {
        if (artifactsDir == null) {
            artifactsDir = baseDir.getAbsolutePath() + File.separator + STARTS_DIRECTORY_PATH;
            File file = new File(artifactsDir);
            if (!file.mkdirs() && !file.exists()) {
                throw new MojoExecutionException("I could not create artifacts dir: " + artifactsDir);
            }
        }
        return artifactsDir;
    }

    public void setIncludesExcludes() throws MojoExecutionException { long start = System.currentTimeMillis();
        List<String> includes = PomUtil.getFromPom("include", accessedProject);
        List<String> excludes = PomUtil.getFromPom("exclude", accessedProject);
        Logger.getGlobal().log(Level.FINEST, "@@Excludes: " + excludes);
        Logger.getGlobal().log(Level.FINEST,"@@Includes: " + includes);
        setIncludes(includes);
        setExcludes(excludes);
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(setIncludesExcludes): "
                + Writer.millsToSeconds(end - start));
    }

    public List getTestClasses(String methodName) {
        long start = System.currentTimeMillis();
        DefaultScanResult defaultScanResult = null;
        try {
            Method scanMethod = AbstractSurefireMojo.class.getDeclaredMethod("scanForTestClasses", null);
            scanMethod.setAccessible(true);
            defaultScanResult = (DefaultScanResult) scanMethod.invoke(this, null);
        } catch (NoSuchMethodException nsme) {
            nsme.printStackTrace();
        } catch (InvocationTargetException ite) {
            ite.printStackTrace();
        } catch (IllegalAccessException iae) {
            iae.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] " + methodName + "(getTestClasses): "
                + Writer.millsToSeconds(end - start));
        return (List<String>) defaultScanResult.getFiles();
    }

    public ClassLoader createClassLoader(Classpath sfClassPath) {
        long start = System.currentTimeMillis();
        ClassLoader loader = null;
        try {
            loader = sfClassPath.createClassLoader(false, false, "MyRole");
        } catch (SurefireExecutionException see) {
            see.printStackTrace();
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(createClassLoader): "
                + Writer.millsToSeconds(end - start));
        return loader;
    }

    protected class Result {
        private Map<String, Set<String>> testDeps;
        private DirectedGraph<String> graph;
        private Set<String> affectedTests;
        private Set<String> unreachedDeps;

        public Result(Map<String, Set<String>> testDeps, DirectedGraph<String> graph,
                      Set<String> affectedTests, Set<String> unreached) {
            this.testDeps = testDeps;
            this.graph = graph;
            this.affectedTests = affectedTests;
            this.unreachedDeps = unreached;
        }

        public Map<String, Set<String>> getTestDeps() {
            return testDeps;
        }

        public DirectedGraph<String> getGraph() {
            return graph;
        }

        public Set<String> getAffectedTests() {
            return affectedTests;
        }

        public Set<String> getUnreachedDeps() {
            return unreachedDeps;
        }
    }

    public Classpath getSureFireClassPath() throws MojoExecutionException {
        long start = System.currentTimeMillis();
        if (sureFireClassPath == null) {
            try {
                sureFireClassPath = new Classpath(accessedProject.getTestClasspathElements());
            } catch (DependencyResolutionRequiredException drre) {
                drre.printStackTrace();
            }
        }
        Logger.getGlobal().log(Level.FINEST, "SF-CLASSPATH: " + sureFireClassPath.getClassPath());
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(getSureFireClassPath): "
                + Writer.millsToSeconds(end - start));
        return sureFireClassPath;
    }

    public Result prepareForNextRun(String sfPathString, Classpath sfClassPath, List<String> classesToAnalyze,
                                    Set<String> nonAffected, boolean computeUnreached) throws MojoExecutionException {
        long start = System.currentTimeMillis();
        String m2Repo = localRepository.getBasedir();
        File jdepsCache = new File(graphCache);
        // We store the jdk-graphs at the root of "jdepsCache" directory, with
        // jdk.graph being the file that merges all the graphs for all standard
        // library jars.
        File libraryFile = new File(jdepsCache, "jdk.graph");
        // Create the Loadables object early so we can use its helpers
        Loadables loadables = new Loadables(classesToAnalyze, artifactsDir, sfPathString,
                useThirdParty, filterLib, jdepsCache);
        // Surefire Classpath object is easier to iterate over without de-constructing
        // sfPathString (which we use in a number of other places)
        loadables.setSurefireClasspath(sfClassPath);

        long loadMoreEdges = System.currentTimeMillis();
        Cache cache = new Cache(jdepsCache, m2Repo);
        // 1. Load non-reflection edges from third-party libraries in the classpath
        List<String> moreEdges = new ArrayList<>();
        if (useThirdParty) {
            moreEdges = cache.loadM2EdgesFromCache(sfPathString);
        }
        long loadM2EdgesFromCache = System.currentTimeMillis();
        // 2. Get non-reflection edges from CUT and SDK; use (1) to build graph
        loadables.create(new ArrayList<>(moreEdges), sfClassPath, computeUnreached);

        Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();
        long createLoadables = System.currentTimeMillis();

        // We don't need to compute affected tests this way with ZLC format.
        // In RTSUtil.computeAffectedTests(), we find affected tests by (a) removing nonAffected tests from the set of
        // all tests and then (b) adding all tests that reach to * as affected if there has been a change. This is only
        // for CLZ which does not encode information about *. ZLC already encodes and reasons about * when it finds
        // nonAffected tests.
        Set<String> affected = depFormat == DependencyFormat.ZLC ? null
                : RTSUtil.computeAffectedTests(new HashSet<>(classesToAnalyze),
                nonAffected, transitiveClosure);
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(loadMoreEdges): "
                + Writer.millsToSeconds(loadMoreEdges - start));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(loadM2EdgesFromCache): "
                + Writer.millsToSeconds(loadM2EdgesFromCache - loadMoreEdges));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(createLoadable): "
                + Writer.millsToSeconds(createLoadables - loadM2EdgesFromCache));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] prepareForNextRun(computeAffectedTests): "
                + Writer.millsToSeconds(end - createLoadables));
        Logger.getGlobal().log(Level.FINE, "[PROFILE] updateForNextRun(prepareForNextRun(TOTAL)): "
                + Writer.millsToSeconds(end - start));
        return new Result(transitiveClosure, loadables.getGraph(), affected, loadables.getUnreached());
    }

    public List<String> getAllClasses() {
        DirectoryScanner testScanner = new DirectoryScanner(testClassDir, new TestListResolver(STAR));
        DirectoryScanner classScanner = new DirectoryScanner(classDir, new TestListResolver(STAR));
        DefaultScanResult scanResult = classScanner.scan().append(testScanner.scan());
        return scanResult.getFiles();
    }

    public void printResult(Set<String> set, String title) {
        Writer.writeToLog(set, title, Logger.getGlobal());
    }



    public Pair<Set<String>, Set<String>> computeChangeData(boolean writeChanged) throws MojoExecutionException {
        long start = System.currentTimeMillis();
        Pair<Set<String>, Set<String>> data = null;
        if (depFormat == DependencyFormat.ZLC) {
            ZLCHelper zlcHelper = new ZLCHelper();
            data = zlcHelper.getChangedData(getArtifactsDir(), cleanBytes);
        } else if (depFormat == DependencyFormat.CLZ) {
            data = EkstaziHelper.getNonAffectedTests(getArtifactsDir());
        }
        Set<String> changed = data == null ? new HashSet<String>() : data.getValue();
        if (writeChanged || Logger.getGlobal().getLoggingLevel().intValue() <= Level.FINEST.intValue()) {
            Writer.writeToFile(changed, CHANGED_CLASSES, getArtifactsDir());
        }
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, "[PROFILE] COMPUTING CHANGES: " + Writer.millsToSeconds(end - start));
        return data;
    }

    public void updateForNextRun(Set<String> nonAffected) throws MojoExecutionException {
        long start = System.currentTimeMillis();
        Classpath sfClassPath = getSureFireClassPath();
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());
        setIncludesExcludes();
        List<String> allTests = getTestClasses("updateForNextRun");
        Set<String> affectedTests = new HashSet<>(allTests);
        affectedTests.removeAll(nonAffected);
        DirectedGraph<String> graph = null;
        if (!affectedTests.isEmpty()) {
            ClassLoader loader = createClassLoader(sfClassPath);
            //TODO: set this boolean to true only for static reflectionAnalyses with * (border, string, naive)?
            boolean computeUnreached = true;
            Result result = prepareForNextRun(sfPathString, sfClassPath, allTests, nonAffected, computeUnreached);
            Map<String, Set<String>> testDeps = result.getTestDeps();
            graph = result.getGraph();
            Set<String> unreached = computeUnreached ? result.getUnreachedDeps() : new HashSet<String>();
            if (depFormat == DependencyFormat.ZLC) {
                ZLCHelper zlcHelper = new ZLCHelper();
                zlcHelper.updateZLCFile(testDeps, loader, getArtifactsDir(), unreached, useThirdParty);
            } else if (depFormat == DependencyFormat.CLZ) {
                // The next line is not needed with ZLC because '*' is explicitly tracked in ZLC
                affectedTests = result.getAffectedTests();
                if (affectedTests == null) {
                    throw new MojoExecutionException("Affected tests should not be null with CLZ format!");
                }
                RTSUtil.computeAndSaveNewCheckSums(getArtifactsDir(), affectedTests, testDeps, loader);
            }
        }
        save(getArtifactsDir(), affectedTests, allTests, sfPathString, graph);
        printToTerminal(allTests, affectedTests);
        long end = System.currentTimeMillis();
        Logger.getGlobal().log(Level.FINE, PROFILE_UPDATE_FOR_NEXT_RUN_TOTAL + Writer.millsToSeconds(end - start));
    }

    public void printToTerminal(List<String> testClasses, Set<String> affectedTests) {
        Logger.getGlobal().log(Level.INFO, STARTS_AFFECTED_TESTS + affectedTests.size());
        Logger.getGlobal().log(Level.INFO, "STARTS:TotalTests: " + testClasses.size());
    }

    public void save(String artifactsDir, Set<String> affectedTests, List<String> testClasses,
                     String sfPathString, DirectedGraph<String> graph) {
        int globalLogLevel = Logger.getGlobal().getLoggingLevel().intValue();
        if (globalLogLevel <= Level.FINER.intValue()) {
            Writer.writeToFile(testClasses, "all-tests", artifactsDir);
            Writer.writeToFile(affectedTests, "selected-tests", artifactsDir);
        }
        if (globalLogLevel <= Level.FINEST.intValue()) {
            RTSUtil.saveForNextRun(artifactsDir, graph, printGraph, graphFile);
            Writer.writeClassPath(sfPathString, artifactsDir);
        }
    }
}
