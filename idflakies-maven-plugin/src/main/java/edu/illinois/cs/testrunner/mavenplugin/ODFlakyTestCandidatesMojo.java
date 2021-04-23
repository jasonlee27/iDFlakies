package edu.illinois.cs.testrunner.mavenplugin;

import edu.illinois.cs.dt.tools.constants.StartsConstants;
import edu.illinois.cs.dt.tools.detection.DetectorPlugin;
import edu.illinois.cs.dt.tools.detection.ODFlakyTestFinder;
import edu.illinois.cs.testrunner.coreplugin.TestPluginUtil;
import edu.illinois.starts.helpers.Cache;
import edu.illinois.starts.helpers.Loadables;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.util.Pair;
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
 * Finds tests affected by a change and OD-flaky test candidates and run iDFlakies.
 */
@Mojo(name = "select", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class ODFlakyTestCandidatesMojo extends DiffMojo implements StartsConstants {
    /**
     * Set this to "true" to update test dependencies on disk. The default value of
     * "false" is useful for "dry runs" where one may want to see the affected
     * tests, without updating test dependencies.
     */
    @Parameter(property = "updateSelectChecksums", defaultValue = TRUE)
    private boolean updateSelectChecksums;

    @Parameter(property = "runIdflakies", defaultValue = TRUE)
    private boolean runIdflakies;

    @Parameter(defaultValue = "")
    private String propertiesPath;

    private Set<String> nonAffectedTests;
    // private Set<String> changedClasses;
    private Set<String> flakyTestCandidates;

    public void execute() throws MojoExecutionException {
//        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));
//        logger = Logger.getGlobal();

        String cpString = Writer.pathToString(getSureFireClassPath().getClassPath());
        List<String> sfPathElements = getCleanClassPath(cpString);
        MavenProject project = getProject();
        long start = System.currentTimeMillis();
        if (!isSameClassPath(sfPathElements) || !hasSameJarChecksum(sfPathElements)) {
            // Force retestAll because classpath changed since last run
            // don't compute changed and non-affected classes
            dynamicallyUpdateExcludes(new ArrayList<String>());
            // Make nonAffected empty so dependencies can be updated
            nonAffectedTests = new HashSet<>();
            Writer.writeClassPath(cpString, artifactsDir);
            Writer.writeJarChecksums(sfPathElements, artifactsDir, jarCheckSums);
            flakyTestCandidates = new HashSet<>(getTestClasses(CHECK_IF_ALL_AFFECTED));

            long startUpdate = System.currentTimeMillis();
            if (updateSelectChecksums) {
                updateForNextRun(nonAffectedTests);
            }
            long endUpdate = System.currentTimeMillis();
            log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));

            Set<String> selectResult = new HashSet<>();
            selectResult.add("#AllTests: "+flakyTestCandidates.size());
            selectResult.add("#AffectedTests: "+flakyTestCandidates.size());
            selectResult.add("#CandidateTests: "+flakyTestCandidates.size());
            selectResult.add("********************");
            printResult(selectResult, "#Selection Results");
        } else {
//        File classDir = getClassesDirectory();
//        File testClassDir = getTestClassesDirectory();
            flakyTestCandidates = computeAffectedTests(project);
        }
        runDetectorMethod(project, flakyTestCandidates);
        long end = System.currentTimeMillis();
        log(Level.FINE, PROFILE_RUN_MOJO_TOTAL + Writer.millsToSeconds(end - start));
        log(Level.FINE, PROFILE_TEST_RUNNING_TIME + 0.0);
    }

    private void runDetectorMethod(MavenProject project, Set<String> flakyTestCandidates) {
        try{
            TestPluginUtil.setConfigs(this.propertiesPath);
            TestPluginUtil.project = new MavenProjectWrapper(project, new IdflakiesLog());
            DetectorPlugin detector = new DetectorPlugin();
            detector.executeSelectedIdflakies(TestPluginUtil.project, flakyTestCandidates, runIdflakies);
        } catch (IOException ioe) { ioe.printStackTrace(); }
    }

    private Set<String> computeAffectedTests(MavenProject project) throws MojoExecutionException {
        setIncludesExcludes();
        Set<String> allTests = new HashSet<>(getTestClasses(CHECK_IF_ALL_AFFECTED));
        Set<String> affectedTests = new HashSet<>(allTests);
        Pair<Set<String>, Set<String>> data = computeChangeData(true);
        nonAffectedTests = data == null ? new HashSet<String>() : data.getKey();
        // Set<String> changedClasses = data == null ? new HashSet<String>() : data.getValue();

        affectedTests.removeAll(nonAffectedTests);
        if (allTests.equals(nonAffectedTests)) {
            log(Level.INFO, STARS_RUN_STARS);
            log(Level.INFO, NO_TESTS_ARE_SELECTED_TO_RUN);
        }
        // printResult(affectedTests, "AffectedTests");

        // Update deps.zlc
        long startUpdate = System.currentTimeMillis();
        if (updateSelectChecksums) {
            updateForNextRun(nonAffectedTests);
        }
        long endUpdate = System.currentTimeMillis();
        log(Level.FINE, PROFILE_STARTS_MOJO_UPDATE_TIME + Writer.millsToSeconds(endUpdate - startUpdate));
        Set<String> selectResult = new HashSet<>();
        selectResult.add("#AllTests: "+allTests.size());
        selectResult.add("#AffectedTests: "+affectedTests.size());
        printResult(selectResult, "#Selection Results");
        Writer.writeToFile(allTests, StartsConstants.ALL_TEST, getArtifactsDir());
        Writer.writeToFile(affectedTests, StartsConstants.AFFECTED_TEST, getArtifactsDir());
        
        // Find flaky test candidates
        Set<String> affectedClassesUnderTest = null;
        Set<String> flakyTestCandidates = new HashSet<>();
        File zlcFile = new File(getArtifactsDir()+File.separator+StartsConstants.ZLC_FILE);
        if (affectedTests.size()>0 && zlcFile.exists()) {
            try {
                affectedClassesUnderTest = getDepClassesUnderTest(affectedTests);
                flakyTestCandidates = computeFlakyTestCandidates(affectedClassesUnderTest, project);
            } catch (IOException | DependencyResolutionRequiredException e) {
                e.printStackTrace();
            }
        }
        // printResult(flakyTestCandidates, "FlakyTestCandidates");
        Writer.writeToFile(flakyTestCandidates, StartsConstants.FLAKY_TEST, getArtifactsDir());

        selectResult = new HashSet<>();
        selectResult.add("#CandidateTests: "+flakyTestCandidates.size());
        selectResult.add("********************");
        printResult(selectResult, "#Candidates Results");
        return flakyTestCandidates;
    }

    private Set<String> computeFlakyTestCandidates(Set<String> affectedClassesUnderTest, MavenProject project)
            throws IOException, DependencyResolutionRequiredException, MojoExecutionException {
        long startFlaky = System.currentTimeMillis();
        List<String> classesWithStaticFields = ODFlakyTestFinder.staticFieldsFinder(affectedClassesUnderTest, project);
        List<String> odFlakyTestCandid = ODFlakyTestFinder.getFlakyTestCandidatesFromSelectedTests(classesWithStaticFields, project);
        long endFlaky = System.currentTimeMillis();
        Writer.writeToFile(classesWithStaticFields, StartsConstants.CLASSES_WITH_STATICFIELDS, getArtifactsDir());
        log(Level.FINE, PROFILE_STARTS_MOJO_FLAKY_TEST_TIME + Writer.millsToSeconds(endFlaky - startFlaky));
        return new HashSet<>(odFlakyTestCandid);
    }

    private Map<String, Set<String>> getDepMap(List<String> classesToAnalyze) throws MojoExecutionException {
        Classpath sfClassPath = getSureFireClassPath();
        String sfPathString = Writer.pathToString(sfClassPath.getClassPath());
        boolean computeUnreached = true;

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
        Map<String, Set<String>> transistiveMap = getDepMap(new ArrayList<>(selectedTests));
        Set<String> result = new HashSet<>();
        for (String cls: selectedTests) { result.addAll(transistiveMap.get(cls)); }
        return result;
    }

}
