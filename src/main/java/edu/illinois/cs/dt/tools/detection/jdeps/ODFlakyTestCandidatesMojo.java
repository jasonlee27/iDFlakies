package edu.illinois.cs.dt.tools.detection.jdeps;

import edu.illinois.cs.dt.tools.constants.StartsConstants;
import edu.illinois.cs.dt.tools.detection.helpers.Cache;
import edu.illinois.cs.dt.tools.detection.helpers.Loadables;
import edu.illinois.cs.dt.tools.detection.helpers.Writer;
import edu.illinois.cs.dt.tools.detection.Logger;
import edu.illinois.cs.dt.tools.detection.Pair;
import edu.illinois.cs.dt.tools.detection.detectors.ODFlakyTestDetector;
import edu.illinois.cs.testrunner.util.ProjectWrapper;
import edu.illinois.cs.testrunner.configuration.Configuration;

import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.apache.maven.surefire.booter.Classpath;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;

/**
 * Finds tests affected by a change and OD-flaky test candidates.
 */
//@Mojo(name = "flakytest", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
//@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class ODFlakyTestCandidatesMojo extends DiffMojo implements StartsConstants {
    /**
     * Set this to "true" to update test dependencies on disk. The default value of
     * "false" is useful for "dry runs" where one may want to see the affected
     * tests, without updating test dependencies.
     */
//    @Parameter(property = "updateSelectChecksums", defaultValue = FALSE)
//    private boolean updateSelectChecksums;
    protected boolean updateSelectChecksums = Configuration.config().getProperty("dt.detector.jdeps.updateSelectChecksums", true);

    private Logger logger;

    public static List<String> filterOriginalOrder(List<String> allTests, ProjectWrapper project) throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
        logger = Logger.getGlobal();
        long start = System.currentTimeMillis();
//        File classDir = getClassesDirectory();
//        File testClassDir = getTestClassesDirectory();
        Set<String> flakyTestCandidates = computeAffectedTests(project, getClassesDirectory(), getTestClassesDirectory());
        List<String> selectedTests = getSelectedTests(allTests, flakyTestCandidates);
        printResult(flakyTestCandidates, "FlakyTestCandidates");
        long end = System.currentTimeMillis();
        logger.log(Level.FINE, PROFILE_RUN_MOJO_TOTAL + Writer.millsToSeconds(end - start));
        logger.log(Level.FINE, PROFILE_TEST_RUNNING_TIME + 0.0);
        return selectedTests;
    }

    private List<String> getSelectedTests(List<String> allTests, Set<String> flakyTestClasses) {
        List<String> resultTests = new ArrayList<>();
        for (String testClass: flakyTestClasses) {
            String className = testClass.split(StartsConstants.CLASS_EXTENSION)[0];
            for (String test: allTests) {
                if (test.startsWith(className)) { resultTests.add(test); break; }
            }
        }
        return resultTests;
    }

    private Set<String> computeAffectedTests(ProjectWrapper project, File classDir, File testClassDir) throws MojoExecutionException {
        setIncludesExcludes();
        Set<String> allTests = new HashSet<>(getTestClasses(CHECK_IF_ALL_AFFECTED));
        Set<String> affectedTests = new HashSet<>(allTests);
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        Set<String> nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        // Set<String> changed = data == null ? new HashSet<String>() : data.getValue();

        affectedTests.removeAll(nonAffectedTests);
        if (allTests.equals(nonAffectedTests)) {
            logger.log(Level.INFO, STARS_RUN_STARS);
            logger.log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        printResult(affectedTests, "AffectedTests");

        Set<String> affectedClassesUnderTest = getDepClassesUnderTest(affectedTests);

        // Find OD-flaky test candidates
        Set<String> flakyTestCandidates = null;
        try {
            flakyTestCandidates = computeFlakyTestCandidates(project, classDir, testClassDir, affectedClassesUnderTest, affectedTests);
        } catch (IOException | DependencyResolutionRequiredException e) {
            e.printStackTrace();
        }
        long startUpdate = System.currentTimeMillis();
        if (updateSelectChecksums) {
            updateForNextRun(nonAffectedTests);
        }
        long endUpdate = System.currentTimeMillis();

        logger.log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
        return flakyTestCandidates;
    }

    private Set<String> computeFlakyTestCandidates(ProjectWrapper project, File classDir, File testClassDir,
                                                          Set<String> affectedClassesUnderTest, Set<String> affectedTests)
            throws IOException, DependencyResolutionRequiredException {
        long startFlaky = System.currentTimeMillis();
        List<String> classesWithStaticFields = ODFlakyTestDetector.staticFieldsFinder(affectedClassesUnderTest, project);
        List<String> odFlakyTestCandid = ODFlakyTestDetector.getFlakyTestCandidatesFromSelectedTests(classesWithStaticFields, project, classDir, testClassDir);
        long endFlaky = System.currentTimeMillis();
        logger.log(Level.FINE, PROFILE_STARTS_MOJO_FLAKY_TEST_TIME + Writer.millsToSeconds(endFlaky - startFlaky));
        return new HashSet<>(odFlakyTestCandid);
    }

    private Map<String, Set<String>> getDepMap(String sfPathString, Classpath sfClassPath, List<String> classesToAnalyze,
                                                      boolean computeUnreached) {
        String m2Repo = getLocalRepository().getBasedir();
        File jdepsCache = new File(graphCache);
        // Create the Loadables object early so we can use its helpers
        Loadables loadables = new Loadables(classesToAnalyze, artifactsDir, sfPathString,
                useThirdParty, filterLib, jdepsCache);
        // Surefire Classpath object is easier to iterate over without de-constructing
        // sfPathString (which we use in a number of other places)
        loadables.setSurefireClasspath(sfClassPath);
        Cache cache = new Cache(jdepsCache, m2Repo);
        // 1. Load non-reflection edges from third-party libraries in the classpath
        List<String> moreEdges = new ArrayList<>();
        if (useThirdParty) {
            moreEdges = cache.loadM2EdgesFromCache(sfPathString);
        }
        // 2. Get non-reflection edges from CUT and SDK; use (1) to build graph
        loadables.create(new ArrayList<>(moreEdges), sfClassPath, computeUnreached);

        // Map<String, Set<String>> transitiveClosure = loadables.getTransitiveClosure();
        return loadables.getTransitiveClosure(); // (test, deps)
    }

    private Set<String> getDepClassesUnderTest(Set<String> selectedTests) throws MojoExecutionException {
        Classpath sfClassPath = getSureFireClassPath();
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());
        boolean computeUnreached = true;
        Map<String, Set<String>> transistiveMap = getDepMap(sfPathString, sfClassPath, new ArrayList<>(selectedTests), computeUnreached);
        Set<String> result = new HashSet<>();
        for (String cls: selectedTests) {
            result.addAll(transistiveMap.get(cls));
        }
        return result;
    }

}
