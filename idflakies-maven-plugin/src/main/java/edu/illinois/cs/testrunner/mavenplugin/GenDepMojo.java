package edu.illinois.cs.testrunner.mavenplugin;

import edu.illinois.cs.dt.tools.constants.StartsConstants;
import edu.illinois.starts.util.Logger;
import edu.illinois.starts.util.Pair;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.*;


import java.io.File;
import java.util.HashSet;
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
        File zlcFile = new File(StartsConstants.ZLC_FILE);
        if (zlcFile.exists()) { zlcFile.delete(); }
        Set<String> changed = new HashSet<>();
        Set<String> nonAffected = new HashSet<>();
        Pair<Set<String>, Set<String>> data = computeChangeData(false);
        String extraText = EMPTY;
        if (data != null) {
            nonAffected = data.getKey();
            changed = data.getValue();
        } else {
            extraText = " (no RTS artifacts; likely the first run)";
        }
        updateForNextRun(nonAffected);
    }

}
