package edu.illinois.cs.testrunner.mavenplugin;

import edu.illinois.cs.dt.tools.constants.StartsConstants;
import edu.illinois.starts.helpers.Writer;
import edu.illinois.starts.util.Logger;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Execute;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * delete if deps.zlc exists, and generate new deps.zlc file.
 */
@Mojo(name = "gendep", requiresDirectInvocation = true, requiresDependencyResolution = ResolutionScope.TEST)
@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class GenDepMojo  extends DiffMojo implements StartsConstants {

    public void execute() throws MojoExecutionException {
        Logger.getGlobal().setLoggingLevel(Level.parse(loggingLevel));

        // get classpath and jarchecksums
        String cpString = Writer.pathToString(getSureFireClassPath().getClassPath());
        List<String> sfPathElements = getCleanClassPath(cpString);

        dynamicallyUpdateExcludes(new ArrayList<String>());
        Writer.writeClassPath(cpString, artifactsDir);
        Writer.writeJarChecksums(sfPathElements, artifactsDir, jarCheckSums);

        // get deps.zlc
        Set<String> nonAffected = new HashSet<>();
        File zlcFile = new File(StartsConstants.ZLC_FILE);
        if (zlcFile.exists()) { zlcFile.delete(); }
//        Pair<Set<String>, Set<String>> data = computeChangeData(false);
//        String extraText = EMPTY;
//        if (data != null) {
//            nonAffected = data.getKey();
//        }
        updateForNextRun(nonAffected);
    }

}
